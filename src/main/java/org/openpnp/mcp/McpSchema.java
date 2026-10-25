package org.openpnp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Builds the JSON Schema of a tool's arguments object. */
public final class McpSchema {
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    public McpSchema string(String name, String description) {
        return property(name, "string", description);
    }

    public McpSchema bool(String name, String description) {
        return property(name, "boolean", description);
    }

    public McpSchema number(String name, String description) {
        return property(name, "number", description);
    }

    public McpSchema required(String name) {
        if (!properties.has(name)) {
            throw new IllegalArgumentException("No property " + name);
        }
        required.add(name);
        return this;
    }

    public JsonObject build() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties.deepCopy());
        if (!required.isEmpty()) {
            schema.add("required", required.deepCopy());
        }
        return schema;
    }

    private McpSchema property(String name, String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        properties.add(name, property);
        return this;
    }
}
