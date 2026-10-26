package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.base.AbstractFeeder;

import com.google.gson.JsonObject;

public final class SetFeederNoteTool implements McpTool {
    private final Supplier<Machine> machine;

    public SetFeederNoteTool(Supplier<Machine> machine) {
        this.machine = machine;
    }

    @Override
    public String getName() {
        return "set_feeder_note";
    }

    @Override
    public String getDescription() {
        return "Sets the free-text note on a feeder, shown in the Feeders table, for what the "
                + "configuration can't express, such as how loose parts must be laid out (pin 1 "
                + "direction). Replaces the whole note; an empty note clears it. expectedNote must "
                + "be the current note from list_feeders (empty if none), so the operator's text "
                + "is never overwritten unseen; a mismatch is refused.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string(FeederLookup.FEEDER_ID, "The feeder id from list_feeders")
                .string("note", "The new note, or empty to clear it")
                .string("expectedNote", "The note as list_feeders last reported it, or empty")
                .required(FeederLookup.FEEDER_ID)
                .required("note")
                .required("expectedNote")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.SUGGEST;
    }

    @Override
    public McpToolResult call(JsonObject arguments) {
        Feeder feeder = FeederLookup.require(machine.get(), arguments);
        String note = McpJson.optionalString(arguments, "note");
        String expectedNote = McpJson.optionalString(arguments, "expectedNote");
        if (note == null || expectedNote == null) {
            throw new IllegalArgumentException(
                    "note and expectedNote are required; pass empty strings for none");
        }
        String current = feeder.getNote() == null ? "" : feeder.getNote();
        if (!current.equals(AbstractFeeder.normaliseLineEndings(expectedNote))) {
            throw new IllegalArgumentException("The note has changed since it was read; read it "
                    + "again with list_feeders. Current note: " + current);
        }
        feeder.setNote(note);
        return McpToolResult.json(ListFeedersTool.describe(feeder));
    }
}
