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

    public McpSchema oneOf(String name, String description, String... values) {
        property(name, "string", description);
        JsonArray allowed = new JsonArray();
        for (String value : values) {
            allowed.add(value);
        }
        properties.getAsJsonObject(name).add("enum", allowed);
        return this;
    }

    public McpSchema bool(String name, String description) {
        return property(name, "boolean", description);
    }

    public McpSchema integer(String name, String description) {
        return property(name, "integer", description);
    }

    public McpSchema number(String name, String description) {
        return property(name, "number", description);
    }

    public McpSchema stringArray(String name, String description) {
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        property(name, "array", description);
        properties.getAsJsonObject(name).add("items", items);
        return this;
    }

    /** An array of objects, each described by a nested schema. */
    public McpSchema objectArray(String name, String description, McpSchema items) {
        property(name, "array", description);
        properties.getAsJsonObject(name).add("items", items.build());
        return this;
    }

    /** A property of any JSON type, described in words. */
    public McpSchema any(String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("description", description);
        properties.add(name, property);
        return this;
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
