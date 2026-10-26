package org.openpnp.mcp.tools;

import java.util.Locale;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.mcp.MotionConfirmer;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

import com.google.gson.JsonObject;

public final class MoveCameraToFeederTool implements McpTool {
    private final Supplier<Machine> machine;
    private final MotionConfirmer confirmer;
    /** One question at a time, so the operator never answers for a move other than the one shown. */
    private final AtomicBoolean asking = new AtomicBoolean();

    public MoveCameraToFeederTool(Supplier<Machine> machine, MotionConfirmer confirmer) {
        this.machine = machine;
        this.confirmer = confirmer;
    }

    @Override
    public String getName() {
        return "move_camera_to_feeder";
    }

    @Override
    public String getDescription() {
        return "Moves the top camera over a feeder's location (X and Y only) at safe Z, as a "
                + "feed does before its vision. The operator must allow the move in OpenPnP "
                + "first, and the call waits until they choose and the move ends; a refusal "
                + "returns an error and nothing moves. One request at a time.";
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
        return Tier.MACHINE;
    }

    /** It waits for the operator and then for the machine thread, so it stays off the EDT. */
    @Override
    public boolean runsOnEdt() {
        return false;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Machine machine = this.machine.get();
        Feeder feeder = FeederLookup.require(machine, arguments);
        if (!(feeder instanceof ReferenceFeeder)
                || ((ReferenceFeeder) feeder).getLocation() == null) {
            throw new IllegalArgumentException(feeder.getName() + " has no location to move to");
        }
        if (!machine.isEnabled() || !machine.isHomed()) {
            throw new IllegalArgumentException("The machine must be enabled and homed");
        }
        if (machine.isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        Camera camera = machine.getDefaultHead().getDefaultCamera();
        // Only X and Y after rising to safe Z, as parking does, so the move is what the operator
        // reads.
        Location target = ((ReferenceFeeder) feeder).getLocation()
                .derive(null, null, Double.NaN, Double.NaN);
        Location targetMm = target.convertToUnits(LengthUnit.Millimeters);
        String description = String.format(Locale.ROOT,
                "Requested at %s: move camera \"%s\" over feeder \"%s\" to X %.2f, Y %.2f mm, "
                        + "rising to safe Z first and staying there. Rotation is unchanged.",
                LocalTime.now().truncatedTo(ChronoUnit.SECONDS), camera.getName(),
                feeder.getName(), targetMm.getX(), targetMm.getY());

        if (!asking.compareAndSet(false, true)) {
            throw new IllegalArgumentException(
                    "The operator is still deciding on an earlier move; wait for that answer");
        }
        try {
            if (!confirmer.confirm(description)) {
                return McpToolResult.error("The operator did not allow the move: " + description);
            }
        }
        finally {
            asking.set(false);
        }
        // The machine may have changed while the operator was deciding.
        if (!machine.isEnabled() || !machine.isHomed() || machine.isBusy()) {
            return McpToolResult.error("The machine is no longer enabled, homed and idle; "
                    + "nothing moved");
        }
        // No timeout: the operator allowed the move, and reporting a move still under way as
        // failed would invite a second one.
        machine.submit(() -> {
            camera.getHead().moveToSafeZ();
            camera.moveTo(target);
            return null;
        }).get();

        JsonObject result = new JsonObject();
        result.addProperty("moved", description);
        result.add("cameraLocation", McpJson.location(camera.getLocation()));
        return McpToolResult.json(result);
    }
}
