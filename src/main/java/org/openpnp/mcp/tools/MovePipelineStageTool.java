package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.PipelineHolder;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;

import com.google.gson.JsonObject;

public final class MovePipelineStageTool implements McpTool {
    private final Supplier<Configuration> configuration;

    public MovePipelineStageTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "move_pipeline_stage";
    }

    @Override
    public String getDescription() {
        return "Moves a stage in a vision pipeline to just before another stage, or to the end, "
                + "and saves it, as dragging it in the pipeline editor would. A pipeline editor "
                + "open on the pipeline shows the change; its Cancel undoes it.";
    }

    @Override
    public JsonObject getInputSchema() {
        return PipelineSupport.targetSchema(new McpSchema())
                .string("stage", "The stage name from get_pipeline")
                .string("before", "The stage to move it before (default: the end)")
                .required("stage")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.WRITE;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Configuration configuration = this.configuration.get();
        PipelineHolder holder = PipelineSupport.requireHolder(configuration, arguments);
        CvPipeline pipeline = PipelineSupport.requireEditablePipeline(configuration, holder);
        CvStage stage = PipelineSupport.requireStage(pipeline,
                McpJson.requiredString(arguments, "stage"));
        String before = McpJson.optionalString(arguments, "before");
        if (stage.getName().equals(before)) {
            throw new IllegalArgumentException("A stage can't move before itself");
        }
        // Resolve the target first so a bad name leaves the pipeline untouched.
        PipelineSupport.insertionIndex(pipeline, before);
        pipeline.remove(stage);
        pipeline.insert(stage, PipelineSupport.insertionIndex(pipeline, before));

        JsonObject result = new JsonObject();
        result.addProperty("shownInEditor",
                CvPipelineEditor.showStructureChange(pipeline, stage));
        result.add("stages", PipelineSupport.describeStages(pipeline));
        return McpToolResult.json(result);
    }
}
