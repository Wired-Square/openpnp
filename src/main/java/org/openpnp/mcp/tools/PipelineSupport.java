package org.openpnp.mcp.tools;

import java.beans.PropertyDescriptor;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.opencv.core.KeyPoint;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.imgproc.Imgproc;
import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.PipelineHolder;
import org.openpnp.vision.pipeline.stages.ActuatorWrite;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Reads, changes and summarises pipeline stages for the pipeline tools. */
final class PipelineSupport {
    /** Enough of a list model to judge a result without flooding the reply. */
    private static final int MAX_LISTED_ITEMS = 10;
    private static final int MAX_TEXT_LENGTH = 200;

    private PipelineSupport() {
    }

    /** A feeder's pipeline, or bottom vision named by part or vision settings. */
    static PipelineHolder requireHolder(Configuration configuration, JsonObject arguments) {
        boolean feederNamed = McpJson.optionalString(arguments, FeederLookup.FEEDER_ID) != null;
        if (feederNamed == BottomVisionTarget.isNamed(arguments)) {
            throw new IllegalArgumentException("Name either a " + FeederLookup.FEEDER_ID
                    + ", or a " + BottomVisionTarget.PART_ID + " and/or "
                    + BottomVisionTarget.VISION_SETTINGS_ID + " for bottom vision");
        }
        if (!feederNamed) {
            return BottomVisionTarget.require(configuration, arguments);
        }
        Feeder feeder = FeederLookup.require(configuration.getMachine(), arguments);
        if (!(feeder instanceof PipelineHolder)) {
            throw new IllegalArgumentException(feeder.getClass().getSimpleName()
                    + " feeders don't expose their pipeline to the MCP server yet");
        }
        return (PipelineHolder) feeder;
    }

    /**
     * Refuses pipelines with stages that act on the machine: running or editing them must not
     * bypass the operator's confirmation, which only the machine tier asks for.
     */
    static void requireNoMachineActions(CvPipeline pipeline) {
        for (CvStage stage : pipeline.getStages()) {
            if (stage instanceof ActuatorWrite) {
                throw new IllegalArgumentException("Stage " + stage.getName()
                        + " drives an actuator, so this pipeline can't be run or edited through "
                        + "the MCP server");
            }
        }
    }

    /** Adds the properties that say which pipeline was meant. */
    static McpSchema targetSchema(McpSchema schema) {
        return schema
                .string(FeederLookup.FEEDER_ID, "A feeder id from list_feeders")
                .string(BottomVisionTarget.PART_ID,
                        "For bottom vision: a part id; its effective bottom vision settings")
                .string(BottomVisionTarget.VISION_SETTINGS_ID,
                        "For bottom vision: a vision settings id, instead of the part's own");
    }

    /**
     * The holder's live pipeline, once it is safe to edit: a feed or an alignment processes it on
     * the machine thread, and machine actions must not bypass the operator's confirmation.
     */
    static CvPipeline requireEditablePipeline(Configuration configuration,
            PipelineHolder holder) {
        if (configuration.getMachine().isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        CvPipeline pipeline = holder.getPipeline();
        requireNoMachineActions(pipeline);
        return pipeline;
    }

    /** The index a stage goes to: before the named stage, or at the end when none is named. */
    static int insertionIndex(CvPipeline pipeline, String before) {
        if (before == null) {
            return pipeline.getStages().size();
        }
        return pipeline.getStages().indexOf(requireStage(pipeline, before));
    }

    static CvStage requireStage(CvPipeline pipeline, String name) {
        CvStage stage = pipeline.getStage(name);
        if (stage == null) {
            throw new IllegalArgumentException("No stage " + name);
        }
        return stage;
    }

    static JsonArray describeStages(CvPipeline pipeline) throws Exception {
        JsonArray stages = new JsonArray();
        for (CvStage stage : pipeline.getStages()) {
            JsonObject json = new JsonObject();
            json.addProperty("name", stage.getName());
            json.addProperty("type", stage.getClass().getSimpleName());
            json.addProperty("enabled", stage.isEnabled());
            JsonObject properties = new JsonObject();
            for (PropertyDescriptor descriptor : stage.getBeanInfo().getPropertyDescriptors()) {
                Method read = descriptor.getReadMethod();
                if (read == null) {
                    properties.addProperty(descriptor.getName(), "(set by the pipeline caller)");
                    continue;
                }
                properties.add(descriptor.getName(), toJson(read.invoke(stage)));
            }
            json.add("properties", properties);
            stages.add(json);
        }
        return stages;
    }

    /**
     * Sets a stage property from JSON, converting to the property's type.
     */
    static void setProperty(CvStage stage, String property, JsonElement value) throws Exception {
        if ("enabled".equals(property)) {
            stage.setEnabled(toValue(boolean.class, property, value).equals(Boolean.TRUE));
            return;
        }
        for (PropertyDescriptor descriptor : stage.getBeanInfo().getPropertyDescriptors()) {
            if (!descriptor.getName().equals(property)) {
                continue;
            }
            Method write = descriptor.getWriteMethod();
            if (write == null) {
                throw new IllegalArgumentException(
                        property + " is read-only or set by the pipeline caller");
            }
            write.invoke(stage, toValue(descriptor.getPropertyType(), property, value));
            return;
        }
        throw new IllegalArgumentException(stage.getName() + " has no property " + property);
    }

    static JsonObject summariseResult(CvStage stage, CvStage.Result result, Camera camera) {
        JsonObject json = new JsonObject();
        json.addProperty("stage", stage.getName());
        json.addProperty("enabled", stage.isEnabled());
        if (result == null) {
            json.addProperty("result", "none");
            return json;
        }
        json.addProperty("milliseconds", result.processingTimeNs / 1e6);
        json.addProperty("hasImage", result.image != null);
        if (result.model != null) {
            json.add("model", summariseModel(result.model, camera));
        }
        return json;
    }

    private static JsonElement summariseModel(Object model, Camera camera) {
        if (model instanceof List) {
            List<?> list = new ArrayList<>((List<?>) model);
            if (!list.isEmpty() && list.get(0) instanceof MatOfPoint) {
                list.sort(Comparator.comparingDouble(
                        (Object contour) -> Imgproc.contourArea((MatOfPoint) contour)).reversed());
            }
            JsonObject json = new JsonObject();
            json.addProperty("count", list.size());
            JsonArray items = new JsonArray();
            for (Object item : list.subList(0, Math.min(MAX_LISTED_ITEMS, list.size()))) {
                items.add(summariseItem(item, camera));
            }
            json.add(list.size() > MAX_LISTED_ITEMS ? "first" : "items", items);
            return json;
        }
        return summariseItem(model, camera);
    }

    private static JsonElement summariseItem(Object item, Camera camera) {
        Location unitsPerPixel = camera.getUnitsPerPixel().convertToUnits(LengthUnit.Millimeters);
        double mmPerPixelX = unitsPerPixel.getX();
        double mmPerPixelY = unitsPerPixel.getY();
        JsonObject json = new JsonObject();
        if (item instanceof RotatedRect) {
            RotatedRect rect = (RotatedRect) item;
            json.addProperty("centreXPx", McpJson.round(rect.center.x));
            json.addProperty("centreYPx", McpJson.round(rect.center.y));
            json.addProperty("widthMm", McpJson.round(rect.size.width * mmPerPixelX));
            json.addProperty("heightMm", McpJson.round(rect.size.height * mmPerPixelY));
            json.addProperty("angle", McpJson.round(rect.angle));
            return json;
        }
        if (item instanceof MatOfPoint) {
            MatOfPoint contour = (MatOfPoint) item;
            double area = Imgproc.contourArea(contour);
            Rect bounds = Imgproc.boundingRect(contour);
            json.addProperty("areaPx", McpJson.round(area));
            json.addProperty("areaMm2", McpJson.round(area * mmPerPixelX * mmPerPixelY));
            json.addProperty("boundsWidthMm", McpJson.round(bounds.width * mmPerPixelX));
            json.addProperty("boundsHeightMm", McpJson.round(bounds.height * mmPerPixelY));
            return json;
        }
        if (item instanceof KeyPoint) {
            KeyPoint point = (KeyPoint) item;
            json.addProperty("xPx", McpJson.round(point.pt.x));
            json.addProperty("yPx", McpJson.round(point.pt.y));
            json.addProperty("sizeMm", McpJson.round(point.size * mmPerPixelX));
            return json;
        }
        return new JsonPrimitive(truncate(String.valueOf(item)));
    }

    private static JsonElement toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof Enum) {
            return new JsonPrimitive(((Enum<?>) value).name());
        }
        return new JsonPrimitive(truncate(value.toString()));
    }

    private static Object toValue(Class<?> type, String property, JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(property + " needs a number, boolean or string");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        try {
            if (type == int.class || type == Integer.class) {
                return requireNumber(primitive, property).intValueExact();
            }
            if (type == long.class || type == Long.class) {
                return requireNumber(primitive, property).longValueExact();
            }
            if (type == double.class || type == Double.class) {
                return requireFinite(requireNumber(primitive, property).doubleValue(), property);
            }
            if (type == float.class || type == Float.class) {
                return (float) requireFinite(
                        requireNumber(primitive, property).floatValue(), property);
            }
            if (type == boolean.class || type == Boolean.class) {
                if (!primitive.isBoolean()) {
                    throw new IllegalArgumentException(property + " needs true or false");
                }
                return primitive.getAsBoolean();
            }
            if (type == String.class) {
                return primitive.getAsString();
            }
            if (type.isEnum()) {
                return enumConstant(type, property, primitive.getAsString());
            }
        }
        catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException(property + " needs a whole number in range");
        }
        throw new IllegalArgumentException(String.format(Locale.ROOT,
                "%s is a %s, which the MCP server can't set yet", property,
                type.getSimpleName()));
    }

    private static BigDecimal requireNumber(JsonPrimitive primitive, String property) {
        if (!primitive.isNumber()) {
            throw new IllegalArgumentException(property + " needs a number");
        }
        return primitive.getAsBigDecimal();
    }

    private static double requireFinite(double value, String property) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(property + " needs a finite number");
        }
        return value;
    }

    private static Object enumConstant(Class<?> type, String property, String name) {
        List<String> names = new ArrayList<>();
        for (Object constant : type.getEnumConstants()) {
            String constantName = ((Enum<?>) constant).name();
            if (constantName.equals(name)) {
                return constant;
            }
            names.add(constantName);
        }
        throw new IllegalArgumentException(property + " must be one of " + names);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH) + "…";
    }
}
