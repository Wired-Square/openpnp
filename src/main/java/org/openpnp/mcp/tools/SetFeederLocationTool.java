package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

import com.google.gson.JsonObject;

public final class SetFeederLocationTool implements McpTool {
    private static final String[] AXES = {"x", "y", "z", "rotation"};

    private final Supplier<Machine> machine;

    public SetFeederLocationTool(Supplier<Machine> machine) {
        this.machine = machine;
    }

    @Override
    public String getName() {
        return "set_feeder_location";
    }

    @Override
    public String getDescription() {
        return "Changes a feeder's stored location (the location list_feeders reports, in mm and "
                + "degrees, machine coordinates), as its configuration panel would. Only the "
                + "axes given change. Nothing moves; test the pick in OpenPnP afterwards. Photon "
                + "feeders are refused: their pick comes from the slot (set_photon_slot_rotation).";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string(FeederLookup.FEEDER_ID, "The feeder id from list_feeders")
                .number("x", "New X in mm")
                .number("y", "New Y in mm")
                .number("z", "New Z in mm")
                .number("rotation", "New rotation in degrees")
                .required(FeederLookup.FEEDER_ID)
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.WRITE;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Machine machine = this.machine.get();
        Feeder feeder = FeederLookup.require(machine, arguments);
        if (feeder instanceof PhotonFeeder || !(feeder instanceof ReferenceFeeder)) {
            throw new IllegalArgumentException(feeder.getClass().getSimpleName()
                    + " feeders don't have a stored location the MCP server can set");
        }
        // A feed reads the location on the machine thread.
        if (machine.isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        ReferenceFeeder referenceFeeder = (ReferenceFeeder) feeder;
        Location before = referenceFeeder.getLocation().convertToUnits(LengthUnit.Millimeters);
        Double[] values = new Double[AXES.length];
        boolean any = false;
        for (int i = 0; i < AXES.length; i++) {
            if (arguments.has(AXES[i]) && !arguments.get(AXES[i]).isJsonNull()) {
                values[i] = AXES[i].equals("rotation")
                        ? McpJson.requiredRotation(arguments, AXES[i])
                        : McpJson.requiredNumber(arguments, AXES[i]);
                any = true;
            }
        }
        if (!any) {
            throw new IllegalArgumentException("Give at least one of x, y, z or rotation");
        }
        Location after = before.derive(values[0], values[1], values[2], values[3]);
        referenceFeeder.setLocation(after);

        JsonObject result = new JsonObject();
        result.add("before", McpJson.location(before));
        result.add("after", McpJson.location(after));
        try {
            result.add("pickLocation", McpJson.location(feeder.getPickLocation()));
        }
        catch (Exception e) {
            // The location is set; only the derived pick location couldn't be computed.
            result.addProperty("pickLocationError", e.getMessage());
        }
        return McpToolResult.json(result);
    }
}
