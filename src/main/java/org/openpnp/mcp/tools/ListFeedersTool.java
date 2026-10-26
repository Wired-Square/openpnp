package org.openpnp.mcp.tools;

import java.util.OptionalInt;
import java.util.function.Supplier;

import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonFeederSlots;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.feeder.ReferenceLoosePartFeeder;
import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;
import org.openpnp.model.PickOffsetCorrection;
import org.openpnp.model.RotationSuggestion;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class ListFeedersTool implements McpTool {
    private final Supplier<Machine> machine;

    public ListFeedersTool(Supplier<Machine> machine) {
        this.machine = machine;
    }

    @Override
    public String getName() {
        return "list_feeders";
    }

    @Override
    public String getDescription() {
        return "Lists the machine's feeders with their part, package and pick location. "
                + "pickLocation is the feeder's computed pick pose: its rotation is the "
                + "orientation of the part's footprint zero as it sits in the feeder, in machine "
                + "coordinates. For a Photon feeder pickLocation is offset applied in the frame of "
                + "slot.location, plus pickOffsetCorrection.offset in X and Y, so its rotation is "
                + "slot.location.rotation + offset.rotation. location, where present, is the feeder's stored location, "
                + "which for strip, tray and other geometric feeders is not the pick pose. "
                + "remainingParts, where present, is how many parts the feeder has left; feeders "
                + "that can't count omit it.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string("partId", "Only feeders holding this part")
                .bool("enabledOnly", "Only enabled feeders")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    @Override
    public McpToolResult call(JsonObject arguments) {
        String partId = McpJson.optionalString(arguments, "partId");
        boolean enabledOnly = McpJson.optionalBoolean(arguments, "enabledOnly", false);

        JsonArray feeders = new JsonArray();
        for (Feeder feeder : machine.get().getFeeders()) {
            Part part = feeder.getPart();
            if (partId != null && (part == null || !partId.equals(part.getId()))) {
                continue;
            }
            if (enabledOnly && !feeder.isEnabled()) {
                continue;
            }
            feeders.add(describe(feeder));
        }
        JsonObject result = new JsonObject();
        result.add("feeders", feeders);
        return McpToolResult.json(result);
    }

    static JsonObject describe(Feeder feeder) {
        JsonObject json = new JsonObject();
        json.addProperty("id", feeder.getId());
        json.addProperty("name", feeder.getName());
        json.addProperty("type", feeder.getClass().getSimpleName());
        json.addProperty("enabled", feeder.isEnabled());
        Part part = feeder.getPart();
        if (part != null) {
            json.addProperty("partId", part.getId());
            if (part.getPackage() != null) {
                json.addProperty("packageId", part.getPackage().getId());
            }
        }
        if (feeder.getNote() != null) {
            json.addProperty("note", feeder.getNote());
        }
        json.addProperty("canApplyPickRotation", feeder.canApplyPickRotation());
        OptionalInt remaining = feeder.getRemainingParts();
        if (remaining.isPresent()) {
            json.addProperty("remainingParts", remaining.getAsInt());
        }
        RotationSuggestion suggestion = feeder.getRotationSuggestion();
        if (suggestion != null) {
            JsonObject suggestionJson = new JsonObject();
            suggestionJson.addProperty("rotation", McpJson.round(suggestion.getRotation()));
            suggestionJson.addProperty("confidence", suggestion.getConfidence().toString());
            suggestionJson.addProperty("rationale", suggestion.getRationale());
            json.add("rotationSuggestion", suggestionJson);
        }
        try {
            json.add("pickLocation", McpJson.location(feeder.getPickLocation()));
        }
        catch (Exception e) {
            json.addProperty("pickLocationError",
                    e.getMessage() != null ? e.getMessage() : e.toString());
        }

        if (feeder instanceof PhotonFeeder) {
            describePhoton((PhotonFeeder) feeder, json);
        }
        else if (feeder instanceof ReferenceLoosePartFeeder) {
            describeSearch((ReferenceLoosePartFeeder) feeder, json);
        }
        else if (feeder instanceof ReferenceFeeder) {
            ReferenceFeeder referenceFeeder = (ReferenceFeeder) feeder;
            if (referenceFeeder.getLocation() != null) {
                json.add("location", McpJson.location(referenceFeeder.getLocation()));
            }
        }
        return json;
    }

    private static void describeSearch(ReferenceLoosePartFeeder feeder, JsonObject json) {
        JsonObject search = new JsonObject();
        search.addProperty("widthMm", McpJson.millimetres(feeder.getSearchWidth()));
        search.addProperty("heightMm", McpJson.millimetres(feeder.getSearchHeight()));
        search.addProperty("partSizeMm", McpJson.millimetres(feeder.getPartSize()));
        try {
            Camera camera = Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
            search.addProperty("frames", feeder.getSearchFrames(camera).size());
        }
        catch (Exception e) {
            search.addProperty("framesError", e.getMessage() != null ? e.getMessage() : e.toString());
        }
        json.add("searchArea", search);
    }

    private static void describePhoton(PhotonFeeder feeder, JsonObject json) {
        json.addProperty("hardwareId", feeder.getHardwareId());
        if (feeder.getOffset() != null) {
            json.add("offset", McpJson.location(feeder.getOffset()));
        }
        PickOffsetCorrection correction = feeder.getPickOffsetCorrection();
        JsonObject correctionJson = new JsonObject();
        correctionJson.addProperty("enabled", correction.isEnabled());
        correctionJson.add("offset", McpJson.location(correction.getOffset()));
        json.add("pickOffsetCorrection", correctionJson);

        PhotonFeederSlots.Slot slot = feeder.getSlot();
        if (slot != null) {
            JsonObject slotJson = new JsonObject();
            slotJson.addProperty("address", feeder.getSlotAddress());
            if (slot.getLocation() != null) {
                slotJson.add("location", McpJson.location(slot.getLocation()));
            }
            json.add("slot", slotJson);
        }
    }
}
