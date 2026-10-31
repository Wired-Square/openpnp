package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;

import com.google.gson.JsonObject;

public final class SetPackagePickOffsetTool implements McpTool {
    private final Supplier<Configuration> configuration;

    public SetPackagePickOffsetTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "set_package_pick_offset";
    }

    @Override
    public String getDescription() {
        return "Sets where the nozzle picks a package's parts, relative to the footprint origin, "
                + "in mm in the footprint's frame, as the package's settings panel would. "
                + "Feeders keep locating the part origin; picks move by this offset, rotated "
                + "with the part. Zero picks at the origin. Nothing moves; test a pick in "
                + "OpenPnP afterwards.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string("packageId", "The package id from list_packages")
                .number("x", "X in mm, footprint frame")
                .number("y", "Y in mm, footprint frame")
                .required("packageId")
                .required("x")
                .required("y")
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
        // A pick or a placement reads the offset on the machine thread.
        if (configuration.getMachine().isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        Location before = pkg.getPickOffset();
        Location after = new Location(LengthUnit.Millimeters,
                McpJson.requiredNumber(arguments, "x"), McpJson.requiredNumber(arguments, "y"),
                0, 0);
        pkg.setPickOffset(after);

        JsonObject result = new JsonObject();
        result.add("before", McpJson.location(before));
        result.add("after", McpJson.location(after));
        return McpToolResult.json(result);
    }
}
