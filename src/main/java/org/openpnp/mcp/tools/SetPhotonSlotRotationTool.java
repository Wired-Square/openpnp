package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonFeederSlots;
import org.openpnp.machine.photon.PhotonProperties;
import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.spi.Machine;

import com.google.gson.JsonObject;

public final class SetPhotonSlotRotationTool implements McpTool {
    private final Supplier<Machine> machine;

    public SetPhotonSlotRotationTool(Supplier<Machine> machine) {
        this.machine = machine;
    }

    @Override
    public String getName() {
        return "set_photon_slot_rotation";
    }

    @Override
    public String getDescription() {
        return "Sets a Photon slot's rotation without moving the pick of the feeder inserted in "
                + "it: that feeder's offset turns the other way, keeping its pick position and "
                + "pick rotation, and its learned pick offset correction. Refused while the "
                + "machine is busy. A slot with no detected feeder is refused unless allowEmpty "
                + "is true, because a feeder inserted later would turn by the change.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .integer("address", "The slot address")
                .number("rotation", "The slot rotation in degrees, machine coordinates")
                .bool("allowEmpty", "Rotate the slot even if no feeder is detected in it")
                .required("address")
                .required("rotation")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.WRITE;
    }

    @Override
    public McpToolResult call(JsonObject arguments) {
        double address = McpJson.requiredNumber(arguments, "address");
        if (address != Math.rint(address) || address < 1 || address > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("address must be a positive whole number");
        }
        double rotation = McpJson.requiredRotation(arguments, "rotation");
        boolean allowEmpty = McpJson.optionalBoolean(arguments, "allowEmpty", false);
        // The slot and the feeder offset change separately, so a pick between them would be wrong.
        if (machine.get().isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        PhotonFeederSlots.Slot slot =
                new PhotonProperties(machine.get()).getFeederSlots().findSlot((int) address);
        if (slot == null || slot.getLocation() == null) {
            throw new IllegalArgumentException("Slot " + (int) address + " is not configured");
        }
        if (!allowEmpty && PhotonFeeder.findBySlotAddress(slot.getAddress()) == null) {
            throw new IllegalArgumentException("No feeder is detected in slot " + slot.getAddress()
                    + "; scan for feeders first, or pass allowEmpty");
        }
        slot.setRotationKeepingPick(rotation);

        JsonObject result = new JsonObject();
        result.addProperty("address", slot.getAddress());
        result.add("location", McpJson.location(slot.getLocation()));
        PhotonFeeder feeder = PhotonFeeder.findBySlotAddress(slot.getAddress());
        if (feeder != null) {
            result.add("feeder", ListFeedersTool.describe(feeder));
        }
        return McpToolResult.json(result);
    }
}
