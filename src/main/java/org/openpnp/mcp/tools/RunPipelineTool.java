package org.openpnp.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.PipelineHolder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class RunPipelineTool implements McpTool {
    private final Supplier<Machine> machine;

    public RunPipelineTool(Supplier<Machine> machine) {
        this.machine = machine;
    }

    @Override
    public String getName() {
        return "run_pipeline";
    }

    @Override
    public String getDescription() {
        return "Runs a copy of a feeder's vision pipeline once, at wherever the top camera is "
                + "now (it does not move; compare cameraLocation with feederLocation), with "
                + "optional temporary property overrides. Nothing is saved. Returns every "
                + "stage's result (contours and rectangles converted to mm, errors as text) and "
                + "the images of the stages listed in images (default: the last stage). Refused "
                + "while the machine is busy.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string(FeederLookup.FEEDER_ID, "The feeder id from list_feeders")
                .objectArray("overrides", "Temporary stage property values", new McpSchema()
                        .string("stage", "Stage name")
                        .string("property", "Property name, or enabled")
                        .any("value", "Number, boolean or string")
                        .required("stage")
                        .required("property")
                        .required("value"))
                .stringArray("images", "Stage names whose images to return")
                .required(FeederLookup.FEEDER_ID)
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Machine machine = this.machine.get();
        PipelineHolder holder = PipelineSupport.requireHolder(machine, arguments);
        if (machine.isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        PipelineSupport.requireNoMachineActions(holder.getPipeline());
        Camera camera = machine.getDefaultHead().getDefaultCamera();

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

            JsonObject result = new JsonObject();
            try {
                pipeline.process();
            }
            catch (Exception e) {
                result.addProperty("error", e.toString());
            }
            result.add("cameraLocation", McpJson.location(camera.getLocation()));
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
