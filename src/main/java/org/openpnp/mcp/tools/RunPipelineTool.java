package org.openpnp.mcp.tools;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.PipelineHolder;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class RunPipelineTool implements McpTool {
    private static final String IMAGE_FILE = "imageFile";
    private static final String LAST_CAPTURE = "lastCapture";
    private static final String SAVE_IMAGE_AS = "saveImageAs";

    private final Supplier<Configuration> configuration;

    public RunPipelineTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "run_pipeline";
    }

    @Override
    public String getDescription() {
        return "Runs a copy of a vision pipeline once, with optional temporary property "
                + "overrides. Nothing moves and nothing is saved. A feeder's pipeline runs at "
                + "wherever the top camera is now (compare cameraLocation with feederLocation). "
                + "Bottom vision (partId, optionally visionSettingsId) is prepared and read as "
                + "the first pass of an alignment of the part at angle (post-rotate settings "
                + "only align at 0); alignment gives the detected feature's offset from the "
                + "camera centre in mm, the angle offset, the part centre's offset after "
                + "subtracting the vision offset, and for pre-rotate whether a real alignment "
                + "would accept the first pass. Single-shot compositing only. imageFile, a frame "
                + "saved with save_camera_frame, replaces the camera capture; so does "
                + "lastCapture, the frame the pipeline last captured, which is the one the "
                + "pipeline editor shows when frozen. saveImageAs keeps that frame in the "
                + "snapshots folder. Returns every stage's "
                + "result (contours and rectangles converted to mm, errors as text) and the "
                + "images of the stages listed in images (default: the last stage). Runs on "
                + "the machine thread; refused while the machine is busy.";
    }

    @Override
    public JsonObject getInputSchema() {
        return PipelineSupport.targetSchema(new McpSchema())
                .string(BottomVisionTarget.NOZZLE, "For bottom vision: the nozzle whose tip "
                        + "sets the context (default: the one holding the part, else one with "
                        + "a suitable tip)")
                .number(BottomVisionTarget.ANGLE,
                        "For bottom vision: the part's expected angle in degrees (default 0)")
                .string(IMAGE_FILE, "A file name in the snapshots folder to use instead of a "
                        + "camera capture")
                .bool(LAST_CAPTURE, "Use the frame this pipeline last captured, e.g. the one "
                        + "frozen in the pipeline editor, instead of a camera capture")
                .string(SAVE_IMAGE_AS, "Also save the frame used (lastCapture or imageFile) "
                        + "as this .png file name in the snapshots folder")
                .objectArray("overrides", "Temporary stage property values", new McpSchema()
                        .string("stage", "Stage name")
                        .string("property", "Property name, or enabled")
                        .any("value", "Number, boolean or string")
                        .required("stage")
                        .required("property")
                        .required("value"))
                .stringArray("images", "Stage names whose images to return")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    /** Runs on the machine thread instead, so capture waits for the camera there. */
    @Override
    public boolean runsOnEdt() {
        return false;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Machine machine = configuration.get().getMachine();
        if (machine.isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        // As a feed or an alignment prepares and processes its pipeline.
        return MachineTasks.call(machine, () -> run(arguments));
    }

    private McpToolResult run(JsonObject arguments) throws Exception {
        Configuration configuration = this.configuration.get();
        Machine machine = configuration.getMachine();
        PipelineHolder holder = PipelineSupport.requireHolder(configuration, arguments);
        PipelineSupport.requireNoMachineActions(holder.getPipeline());
        BottomVisionTarget bottomVision = holder instanceof BottomVisionTarget
                ? (BottomVisionTarget) holder
                : null;
        Camera camera = bottomVision != null
                ? bottomVision.getCamera()
                : machine.getDefaultHead().getDefaultCamera();
        String imageFile = McpJson.optionalString(arguments, IMAGE_FILE);
        boolean lastCapture = McpJson.optionalBoolean(arguments, LAST_CAPTURE, false);
        BufferedImage image = null;
        String imageSource = null;
        if (imageFile != null && lastCapture) {
            throw new IllegalArgumentException(
                    "Use " + IMAGE_FILE + " or " + LAST_CAPTURE + ", not both");
        }
        if (imageFile != null) {
            image = Snapshots.read(configuration, imageFile);
            imageSource = imageFile;
        }
        if (lastCapture) {
            image = holder.getPipeline().getLastCapturedImage();
            imageSource = "The last captured frame";
            if (image == null) {
                throw new IllegalArgumentException("This pipeline hasn't captured a frame since "
                        + "OpenPnP started; run it in the pipeline editor first");
            }
        }
        if (image != null && (image.getWidth() != camera.getWidth()
                || image.getHeight() != camera.getHeight())) {
            throw new IllegalArgumentException(String.format(
                    "%s is %d × %d px but camera %s captures %d × %d px", imageSource,
                    image.getWidth(), image.getHeight(), camera.getName(), camera.getWidth(),
                    camera.getHeight()));
        }
        String saveImageAs = McpJson.optionalString(arguments, SAVE_IMAGE_AS);
        if (saveImageAs != null) {
            if (image == null) {
                throw new IllegalArgumentException(
                        SAVE_IMAGE_AS + " needs " + LAST_CAPTURE + " or " + IMAGE_FILE);
            }
            Snapshots.write(configuration, saveImageAs, image);
        }

        try (CvPipeline pipeline = holder.getPipeline().clone()) {
            holder.preparePipeline(pipeline, camera);
            JsonArray overrides = arguments.has("overrides") && arguments.get("overrides").isJsonArray()
                    ? arguments.getAsJsonArray("overrides")
                    : new JsonArray();
            for (JsonElement element : overrides) {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("Each override must be an object");
                }
                JsonObject override = element.getAsJsonObject();
                PipelineSupport.setProperty(
                        PipelineSupport.requireStage(pipeline,
                                McpJson.requiredString(override, "stage")),
                        McpJson.requiredString(override, "property"), override.get("value"));
            }
            List<CvStage> imageStages = imageStages(pipeline, arguments);
            if (image != null) {
                pipeline.setProperty(CvPipelineEditor.FROZEN_IMAGE_PROPERTY, image);
            }

            JsonObject result = new JsonObject();
            try {
                pipeline.process();
            }
            catch (Exception e) {
                result.addProperty("error", e.toString());
            }
            result.add("cameraLocation", McpJson.location(camera.getLocation()));
            if (bottomVision != null) {
                result.add("visionSettings", bottomVision.describe());
                result.add("alignment", bottomVision.describeAlignment(pipeline, camera));
            }
            if (holder instanceof ReferenceFeeder) {
                result.add("feederLocation",
                        McpJson.location(((ReferenceFeeder) holder).getLocation()));
            }
            JsonArray stages = new JsonArray();
            for (CvStage stage : pipeline.getStages()) {
                stages.add(PipelineSupport.summariseResult(stage, pipeline.getResult(stage),
                        camera));
            }
            result.add("stages", stages);

            McpToolResult toolResult = McpToolResult.json(result);
            for (CvStage stage : imageStages) {
                CvStage.Result stageResult = pipeline.getResult(stage);
                if (stageResult != null && stageResult.image != null) {
                    toolResult.withImage("Stage " + stage.getName(),
                            OpenCvUtils.toBufferedImage(stageResult.image));
                }
            }
            return toolResult;
        }
    }

    private static List<CvStage> imageStages(CvPipeline pipeline, JsonObject arguments) {
        List<CvStage> stages = new ArrayList<>();
        JsonElement names = arguments.get("images");
        if (names == null || names.isJsonNull()) {
            List<CvStage> all = pipeline.getStages();
            if (!all.isEmpty()) {
                stages.add(all.get(all.size() - 1));
            }
            return stages;
        }
        if (!names.isJsonArray()) {
            throw new IllegalArgumentException("images must be an array of stage names");
        }
        for (JsonElement name : names.getAsJsonArray()) {
            if (!name.isJsonPrimitive()) {
                throw new IllegalArgumentException("images must be an array of stage names");
            }
            stages.add(PipelineSupport.requireStage(pipeline, name.getAsString()));
        }
        return stages;
    }
}
