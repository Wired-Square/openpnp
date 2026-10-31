package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.vision.pipeline.PipelineHolder;

import com.google.gson.JsonObject;

public final class GetPipelineTool implements McpTool {
    private final Supplier<Configuration> configuration;

    public GetPipelineTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "get_pipeline";
    }

    @Override
    public String getDescription() {
        return "Describes a vision pipeline: each stage in order with its type, whether it is "
                + "enabled and its properties. Name a feeder (loose part feeders so far), or a "
                + "part and/or vision settings for bottom vision, whose settings are described "
                + "too.";
    }

    @Override
    public JsonObject getInputSchema() {
        return PipelineSupport.targetSchema(new McpSchema()).build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        PipelineHolder holder = PipelineSupport.requireHolder(configuration.get(), arguments);
        JsonObject result = new JsonObject();
        if (holder instanceof BottomVisionTarget) {
            result.add("visionSettings", ((BottomVisionTarget) holder).describe());
        }
        result.add("stages", PipelineSupport.describeStages(holder.getPipeline()));
        return McpToolResult.json(result);
    }
}
