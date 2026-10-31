package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;

import com.google.gson.JsonObject;

public final class SetPackageBodySizeTool implements McpTool {
    /** Larger than any part a desktop machine's nozzle can carry; beyond it is a typo. */
    static final double MAX_BODY_MM = 150;

    private final Supplier<Configuration> configuration;

    public SetPackageBodySizeTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "set_package_body_size";
    }

    @Override
    public String getDescription() {
        return "Sets a package footprint's body width (along the footprint's X) and height in "
                + "mm, as the package's footprint panel would, typically from a "
                + "resolve_part_sizes proposal the operator confirmed. Bottom vision uses it "
                + "where its part size check or compositing works on the body size, and "
                + "check_job_picks uses it to find the part. Nothing moves.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string("packageId", "The package id from list_packages")
                .number("width", "Body width in mm, along the footprint's X")
                .number("height", "Body height in mm, along the footprint's Y")
                .required("packageId")
                .required("width")
                .required("height")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.WRITE;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Configuration configuration = this.configuration.get();
        String id = McpJson.requiredString(arguments, "packageId");
        Package pkg = configuration.getPackage(id);
        if (pkg == null) {
            throw new IllegalArgumentException("No package " + id);
        }
        double width = requiredSize(arguments, "width");
        double height = requiredSize(arguments, "height");
        // Bottom vision reads the body on the machine thread.
        if (configuration.getMachine().isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        Footprint footprint = pkg.getFootprint();
        LengthUnit units = footprint.getUnits();
        JsonObject before = size(footprint);
        footprint.setBodyWidth(new Length(width, LengthUnit.Millimeters).convertToUnits(units)
                .getValue());
        footprint.setBodyHeight(new Length(height, LengthUnit.Millimeters).convertToUnits(units)
                .getValue());

        JsonObject result = new JsonObject();
        result.add("before", before);
        result.add("after", size(footprint));
        return McpToolResult.json(result);
    }

    private static double requiredSize(JsonObject arguments, String name) {
        double value = McpJson.requiredNumber(arguments, name);
        if (value <= 0 || value > MAX_BODY_MM) {
            throw new IllegalArgumentException(String.format(
                    "%s must be more than 0 and at most %.0f mm", name, MAX_BODY_MM));
        }
        return value;
    }

    private static JsonObject size(Footprint footprint) {
        JsonObject json = new JsonObject();
        json.addProperty("width", McpJson.millimetres(footprint.getBodyWidth(),
                footprint.getUnits()));
        json.addProperty("height", McpJson.millimetres(footprint.getBodyHeight(),
                footprint.getUnits()));
        return json;
    }
}
