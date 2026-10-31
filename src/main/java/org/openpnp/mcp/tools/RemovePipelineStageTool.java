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

public final class RemovePipelineStageTool implements McpTool {
    private final Supplier<Configuration> configuration;

    public RemovePipelineStageTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "remove_pipeline_stage";
    }

    @Override
    public String getDescription() {
        return "Removes a stage from a vision pipeline and saves it, as the pipeline editor's "
                + "Remove would. Check first that no later stage names it (e.g. as its "
                + "contoursStageName). A pipeline editor open on the pipeline shows the change; "
                + "its Cancel undoes it.";
    }

    @Override
    public JsonObject getInputSchema() {
        return PipelineSupport.targetSchema(new McpSchema())
                .string("stage", "The stage name from get_pipeline")
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
        pipeline.remove(stage);

        JsonObject result = new JsonObject();
        result.addProperty("shownInEditor",
                CvPipelineEditor.showStructureChange(pipeline, null));
        result.add("stages", PipelineSupport.describeStages(pipeline));
        return McpToolResult.json(result);
    }
}
