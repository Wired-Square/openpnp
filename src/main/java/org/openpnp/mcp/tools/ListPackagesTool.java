package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class ListPackagesTool implements McpTool {
    private final Supplier<Configuration> configuration;

    public ListPackagesTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "list_packages";
    }

    @Override
    public String getDescription() {
        return "Lists packages with their footprint body size. Give an id to also get the pads "
                + "(centre, size and rotation relative to the footprint zero; mark flags pads "
                + "the operator marked, usually pin 1).";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string("id", "Only this package, including its pads")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    @Override
    public McpToolResult call(JsonObject arguments) {
        String id = McpJson.optionalString(arguments, "id");
        JsonArray packages = new JsonArray();
        if (id != null) {
            Package pkg = configuration.get().getPackage(id);
            if (pkg == null) {
                throw new IllegalArgumentException("No package " + id);
            }
            packages.add(describe(pkg, true));
        }
        else {
            for (Package pkg : configuration.get().getPackages()) {
                packages.add(describe(pkg, false));
            }
        }
        JsonObject result = new JsonObject();
        result.add("packages", packages);
        return McpToolResult.json(result);
    }

    private static JsonObject describe(Package pkg, boolean includePads) {
        JsonObject json = new JsonObject();
        json.addProperty("id", pkg.getId());
        json.addProperty("description", pkg.getDescription());
        json.addProperty("tapeSpecification", pkg.getTapeSpecification());
        Footprint footprint = pkg.getFootprint();
        if (footprint == null) {
            return json;
        }
        LengthUnit units = footprint.getUnits();
        JsonObject footprintJson = new JsonObject();
        footprintJson.addProperty("bodyWidth", McpJson.millimetres(footprint.getBodyWidth(), units));
        footprintJson.addProperty("bodyHeight",
                McpJson.millimetres(footprint.getBodyHeight(), units));
        footprintJson.addProperty("padCount", footprint.getPads().size());
        if (includePads) {
            JsonArray pads = new JsonArray();
            for (Footprint.Pad pad : footprint.getPads()) {
                JsonObject padJson = new JsonObject();
                padJson.addProperty("name", pad.getName());
                padJson.addProperty("x", McpJson.millimetres(pad.getX(), units));
                padJson.addProperty("y", McpJson.millimetres(pad.getY(), units));
                padJson.addProperty("width", McpJson.millimetres(pad.getWidth(), units));
                padJson.addProperty("height", McpJson.millimetres(pad.getHeight(), units));
                padJson.addProperty("rotation", McpJson.round(pad.getRotation()));
                padJson.addProperty("roundness", pad.getRoundness());
                if (pad.getMark()) {
                    padJson.addProperty("mark", true);
                }
                pads.add(padJson);
            }
            footprintJson.add("pads", pads);
        }
        json.add("footprint", footprintJson);
        return json;
    }
}
