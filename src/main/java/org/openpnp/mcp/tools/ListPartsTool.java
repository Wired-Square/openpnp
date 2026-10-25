package org.openpnp.mcp.tools;

import java.util.Locale;
import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class ListPartsTool implements McpTool {
    private final Supplier<Configuration> configuration;

    public ListPartsTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "list_parts";
    }

    @Override
    public String getDescription() {
        return "Lists the parts library: id, name, package, height and how many placements and "
                + "feeders use each part.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string("idContains", "Only parts whose id contains this text, ignoring case")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    @Override
    public McpToolResult call(JsonObject arguments) {
        String idContains = McpJson.optionalString(arguments, "idContains");
        String filter = idContains == null ? null : idContains.toLowerCase(Locale.ROOT);

        JsonArray parts = new JsonArray();
        for (Part part : configuration.get().getParts()) {
            if (filter != null && !part.getId().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            JsonObject json = new JsonObject();
            json.addProperty("id", part.getId());
            json.addProperty("name", part.getName());
            if (part.getPackage() != null) {
                json.addProperty("packageId", part.getPackage().getId());
            }
            if (!part.isPartHeightUnknown()) {
                json.addProperty("heightMm", McpJson.millimetres(part.getHeight()));
            }
            json.addProperty("placementCount", part.getPlacementCount());
            json.addProperty("assignedFeeders", part.getAssignedFeeders());
            parts.add(json);
        }
        JsonObject result = new JsonObject();
        result.add("parts", parts);
        return McpToolResult.json(result);
    }
}
