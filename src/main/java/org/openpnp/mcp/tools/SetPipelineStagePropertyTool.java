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

public final class SetPipelineStagePropertyTool implements McpTool {
    private final Supplier<Configuration> configuration;

    public SetPipelineStagePropertyTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "set_pipeline_stage_property";
    }

    @Override
    public String getDescription() {
        return "Saves one property of a stage in a vision pipeline, as editing it in the "
                + "pipeline editor would. Bottom vision settings can be shared by several parts "
                + "and packages. Try values with run_pipeline overrides first. \"enabled\" "
                + "switches the stage on or off. A pipeline editor open on the pipeline selects "
                + "the stage and processes again, so the operator sees the change; its Cancel "
                + "undoes it.";
    }

    @Override
    public JsonObject getInputSchema() {
        return PipelineSupport.targetSchema(new McpSchema())
                .string("stage", "The stage name from get_pipeline")
                .string("property", "The property name from get_pipeline")
                .any("value", "The new value: a number, boolean or string (enum name)")
                .required("stage")
                .required("property")
                .required("value")
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
        PipelineSupport.setProperty(stage, McpJson.requiredString(arguments, "property"),
                arguments.get("value"));
        JsonObject result = new JsonObject();
        result.addProperty("shownInEditor", CvPipelineEditor.showStageChange(pipeline, stage));
        result.add("stages", PipelineSupport.describeStages(pipeline));
        return McpToolResult.json(result);
    }
}
