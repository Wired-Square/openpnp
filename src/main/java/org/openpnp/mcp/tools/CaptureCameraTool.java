package org.openpnp.mcp.tools;

import java.awt.image.BufferedImage;
import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;

import com.google.gson.JsonObject;

public final class CaptureCameraTool implements McpTool {
    private final Supplier<Machine> machine;

    public CaptureCameraTool(Supplier<Machine> machine) {
        this.machine = machine;
    }

    @Override
    public String getName() {
        return "capture_camera";
    }

    @Override
    public String getDescription() {
        return "Captures one frame from a camera where it is now, without moving anything. "
                + "Defaults to the head's top camera. Refused while the machine is busy.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string("camera", "Camera name, e.g. \"Top Down\" or \"Bottom Up\"")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    /** Capturing waits for the camera, so it stays off the EDT. */
    @Override
    public boolean runsOnEdt() {
        return false;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Machine machine = this.machine.get();
        if (machine.isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        Camera camera = CameraLookup.find(machine, McpJson.optionalString(arguments, "camera"));
        BufferedImage image = camera.capture();

        JsonObject result = new JsonObject();
        result.addProperty("camera", camera.getName());
        result.addProperty("widthPx", image.getWidth());
        result.addProperty("heightPx", image.getHeight());
        result.add("location", McpJson.location(camera.getLocation()));
        result.add("mmPerPixel",
                McpJson.location(camera.getUnitsPerPixel().convertToUnits(LengthUnit.Millimeters)));
        return McpToolResult.json(result).withImage(camera.getName(), image);
    }
}
