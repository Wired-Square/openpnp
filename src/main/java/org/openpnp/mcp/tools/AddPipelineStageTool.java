package org.openpnp.mcp.tools;

import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.PipelineHolder;
import org.openpnp.vision.pipeline.stages.ActuatorWrite;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class AddPipelineStageTool implements McpTool {
    private final Supplier<Configuration> configuration;

    public AddPipelineStageTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "add_pipeline_stage";
    }

    @Override
    public String getDescription() {
        return "Adds a stage to a vision pipeline and saves it, as the pipeline editor's Add "
                + "would, with optional property values. type is the stage class's simple name "
                + "(e.g. FindContours); an unknown type lists the valid ones. A pipeline editor "
                + "open on the pipeline shows the new stage selected; its Cancel undoes it.";
    }

    @Override
    public JsonObject getInputSchema() {
        return PipelineSupport.targetSchema(new McpSchema())
                .string("type", "Stage class simple name, e.g. FindContours")
                .string("name", "A name unique in the pipeline")
                .string("before", "The stage to insert before (default: the end)")
                .any("properties", "Object of property name to value, set before inserting")
                .required("type")
                .required("name")
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
        String name = McpJson.requiredString(arguments, "name");
        if (pipeline.getStage(name) != null) {
            throw new IllegalArgumentException("There is already a stage " + name);
        }
        int index = PipelineSupport.insertionIndex(pipeline,
                McpJson.optionalString(arguments, "before"));
        CvStage stage = createStage(McpJson.requiredString(arguments, "type"));
        stage.setName(name);
        JsonElement properties = arguments.get("properties");
        if (properties != null && !properties.isJsonNull()) {
            if (!properties.isJsonObject()) {
                throw new IllegalArgumentException("properties must be an object");
            }
            for (Map.Entry<String, JsonElement> property : properties.getAsJsonObject()
                    .entrySet()) {
                PipelineSupport.setProperty(stage, property.getKey(), property.getValue());
            }
        }
        pipeline.insert(stage, index);

        JsonObject result = new JsonObject();
        result.addProperty("shownInEditor",
                CvPipelineEditor.showStructureChange(pipeline, stage));
        result.add("stages", PipelineSupport.describeStages(pipeline));
        return McpToolResult.json(result);
    }

    private static CvStage createStage(String type) throws Exception {
        TreeSet<String> names = new TreeSet<>();
        for (Class<? extends CvStage> stageClass : CvPipelineEditor.getStageClasses()) {
            if (stageClass.getSimpleName().equals(type)) {
                if (ActuatorWrite.class.isAssignableFrom(stageClass)) {
                    throw new IllegalArgumentException(type
                            + " drives an actuator, so the MCP server can't add it");
                }
                return stageClass.getDeclaredConstructor().newInstance();
            }
            names.add(stageClass.getSimpleName());
        }
        throw new IllegalArgumentException("No stage type " + type + "; valid types: " + names);
    }
}
