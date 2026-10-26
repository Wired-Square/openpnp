package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.spi.Machine;
import org.openpnp.vision.pipeline.PipelineHolder;

import com.google.gson.JsonObject;

public final class GetPipelineTool implements McpTool {
    private final Supplier<Machine> machine;

    public GetPipelineTool(Supplier<Machine> machine) {
        this.machine = machine;
    }

    @Override
    public String getName() {
        return "get_pipeline";
    }

    @Override
    public String getDescription() {
        return "Describes a feeder's vision pipeline: each stage in order with its type, whether "
                + "it is enabled and its properties. Supported so far: loose part feeders.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string(FeederLookup.FEEDER_ID, "The feeder id from list_feeders")
                .required(FeederLookup.FEEDER_ID)
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        PipelineHolder holder = PipelineSupport.requireHolder(machine.get(), arguments);
        JsonObject result = new JsonObject();
        result.add("stages", PipelineSupport.describeStages(holder.getPipeline()));
        return McpToolResult.json(result);
    }
}
