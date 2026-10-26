package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

import com.google.gson.JsonObject;

public final class ApplyFeederRotationSuggestionTool implements McpTool {
    private final Supplier<Machine> machine;

    public ApplyFeederRotationSuggestionTool(Supplier<Machine> machine) {
        this.machine = machine;
    }

    @Override
    public String getName() {
        return "apply_feeder_rotation_suggestion";
    }

    @Override
    public String getDescription() {
        return "Applies a feeder's pending rotation suggestion, as the operator's Apply would.";
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
        return Tier.WRITE;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Feeder feeder = FeederLookup.require(machine.get(), arguments);
        if (feeder.getRotationSuggestion() == null) {
            throw new IllegalArgumentException(feeder.getName() + " has no rotation suggestion");
        }
        feeder.applyRotationSuggestion();
        return McpToolResult.json(ListFeedersTool.describe(feeder));
    }
}
