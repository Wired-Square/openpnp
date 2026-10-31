package org.openpnp.mcp.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class SaveCameraFrameTool implements McpTool {
    private static final String FILE = "file";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);

    private final Supplier<Configuration> configuration;

    public SaveCameraFrameTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "save_camera_frame";
    }

    @Override
    public String getDescription() {
        return "Captures one full-resolution frame from a camera where it is now, without "
                + "moving anything, and saves it as a PNG in the snapshots folder for "
                + "run_pipeline's imageFile. Defaults to the head's top camera. light switches "
                + "the camera's light on for the capture and off afterwards, as a pipeline's "
                + "ImageCapture does. For an up-looking camera the reply lists each nozzle's "
                + "location and part, so the angle the part was at is on record. Refused while "
                + "the machine is busy.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string("camera", "Camera name, e.g. \"Top Down\" or \"Bottom Up\"")
                .string(FILE, "File name ending in .png (default: camera name and time); "
                        + "an existing file is not overwritten")
                .bool("light", "Switch the camera's light on for the capture (default false)")
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
        Configuration configuration = this.configuration.get();
        Machine machine = configuration.getMachine();
        if (machine.isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        Camera camera = CameraLookup.find(machine, McpJson.optionalString(arguments, "camera"));
        String name = fileName(arguments, camera);
        if (Snapshots.resolve(configuration, name).exists()) {
            throw new IllegalArgumentException(name + " already exists");
        }
        boolean light = McpJson.optionalBoolean(arguments, "light", false);

        // Settling waits for the motion planner, which only the machine thread may use.
        BufferedImage image = MachineTasks.call(machine, () -> capture(camera, light));
        File file = Snapshots.write(configuration, name, image);

        JsonObject result = new JsonObject();
        result.addProperty(FILE, file.getName());
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("camera", camera.getName());
        result.addProperty("widthPx", image.getWidth());
        result.addProperty("heightPx", image.getHeight());
        result.addProperty("light", light);
        result.add("location", McpJson.location(camera.getLocation()));
        result.add("mmPerPixel",
                McpJson.location(camera.getUnitsPerPixel().convertToUnits(LengthUnit.Millimeters)));
        if (camera.getLooking() == Camera.Looking.Up) {
            result.add("nozzles", describeNozzles(machine));
        }
        return McpToolResult.json(result).withImage(file.getName(), image);
    }

    private static String fileName(JsonObject arguments, Camera camera) {
        String name = McpJson.optionalString(arguments, FILE);
        if (name == null) {
            return camera.getName().replaceAll("[^A-Za-z0-9_-]", "_") + "-"
                    + LocalDateTime.now().format(TIMESTAMP) + Snapshots.PNG_SUFFIX;
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(Snapshots.PNG_SUFFIX)) {
            throw new IllegalArgumentException(FILE + " must end in " + Snapshots.PNG_SUFFIX);
        }
        return name;
    }

    private static BufferedImage capture(Camera camera, boolean light) throws Exception {
        if (!light) {
            return camera.settleAndCapture();
        }
        camera.actuateLightBeforeCapture();
        try {
            return camera.settleAndCapture();
        }
        finally {
            camera.actuateLightAfterCapture();
        }
    }

    private static JsonArray describeNozzles(Machine machine) {
        JsonArray nozzles = new JsonArray();
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                JsonObject json = new JsonObject();
                json.addProperty("name", nozzle.getName());
                json.add("location", McpJson.location(nozzle.getLocation()));
                json.addProperty("partId",
                        nozzle.getPart() != null ? nozzle.getPart().getId() : null);
                nozzles.add(json);
            }
        }
        return nozzles;
    }
}
