package org.openpnp.mcp.tools;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.RotationSuggestion;
import org.openpnp.model.RotationSuggestion.Confidence;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

import com.google.gson.JsonObject;

public final class SuggestFeederRotationTool implements McpTool {
    private final Supplier<Machine> machine;
    private final BooleanSupplier applyDirectly;

    public SuggestFeederRotationTool(Supplier<Machine> machine, BooleanSupplier applyDirectly) {
        this.machine = machine;
        this.applyDirectly = applyDirectly;
    }

    @Override
    public String getName() {
        return "suggest_feeder_rotation";
    }

    @Override
    public String getDescription() {
        return "Records a suggested pick rotation on a feeder for the operator to review and "
                + "apply from the Feeders table. rotation is the pick rotation (as "
                + "list_feeders pickLocation.rotation reports it): the orientation of the part's "
                + "footprint zero as it sits in the feeder, in machine coordinates. For a loose "
                + "part feeder, whose vision adds each part's measured angle, it is the rotation "
                + "of a part found square (its location rotation). If the "
                + "operator chose to apply suggestions directly, it is applied at once; the "
                + "result says which. Replaces any earlier suggestion for the feeder.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string(FeederLookup.FEEDER_ID, "The feeder id from list_feeders")
                .number("rotation", "Suggested pick rotation in degrees, machine coordinates")
                .oneOf("confidence", "How sure the suggestion is", "high", "medium", "low")
                .string("rationale", "Why, with sources, for the operator to check")
                .required(FeederLookup.FEEDER_ID)
                .required("rotation")
                .required("confidence")
                .required("rationale")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.SUGGEST;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Feeder feeder = FeederLookup.require(machine.get(), arguments);
        double rotation = McpJson.requiredRotation(arguments, "rotation");
        Confidence confidence = parseConfidence(McpJson.requiredString(arguments, "confidence"));
        String rationale = McpJson.requiredString(arguments, "rationale");
        if (!feeder.canApplyPickRotation()) {
            throw new IllegalArgumentException(feeder.getClass().getSimpleName()
                    + " feeders cannot apply a pick rotation, so a suggestion could not be used");
        }

        feeder.setRotationSuggestion(new RotationSuggestion(rotation, confidence, rationale));
        boolean applied = applyDirectly.getAsBoolean();
        if (applied) {
            feeder.applyRotationSuggestion();
        }

        JsonObject result = ListFeedersTool.describe(feeder);
        result.addProperty("applied", applied);
        return McpToolResult.json(result);
    }

    private static Confidence parseConfidence(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "high":
                return Confidence.High;
            case "medium":
                return Confidence.Medium;
            case "low":
                return Confidence.Low;
            default:
                throw new IllegalArgumentException("confidence must be high, medium or low");
        }
    }
}
