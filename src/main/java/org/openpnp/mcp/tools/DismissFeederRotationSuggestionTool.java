package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

import com.google.gson.JsonObject;

public final class DismissFeederRotationSuggestionTool implements McpTool {
    private final Supplier<Machine> machine;

    public DismissFeederRotationSuggestionTool(Supplier<Machine> machine) {
        this.machine = machine;
    }

    @Override
    public String getName() {
        return "dismiss_feeder_rotation_suggestion";
    }

    @Override
    public String getDescription() {
        return "Withdraws a feeder's pending rotation suggestion without applying it.";
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
        return Tier.SUGGEST;
    }

    @Override
    public McpToolResult call(JsonObject arguments) {
        Feeder feeder = FeederLookup.require(machine.get(), arguments);
        if (feeder.getRotationSuggestion() != null) {
            feeder.setRotationSuggestion(null);
        }
        return McpToolResult.json(ListFeedersTool.describe(feeder));
    }
}
