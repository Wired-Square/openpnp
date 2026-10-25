package org.openpnp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class McpToolResult {
    private final JsonObject structuredContent;
    private final String text;
    private final boolean error;

    private McpToolResult(JsonObject structuredContent, String text, boolean error) {
        this.structuredContent = structuredContent;
        this.text = text;
        this.error = error;
    }

    public static McpToolResult json(JsonObject structuredContent) {
        return new McpToolResult(structuredContent, structuredContent.toString(), false);
    }

    public static McpToolResult error(String message) {
        return new McpToolResult(null, message, true);
    }

    public boolean isError() {
        return error;
    }

    public JsonObject getStructuredContent() {
        return structuredContent;
    }

    public JsonObject toJson() {
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(textContent);

        JsonObject result = new JsonObject();
        result.add("content", content);
        if (structuredContent != null) {
            result.add("structuredContent", structuredContent);
        }
        result.addProperty("isError", error);
        return result;
    }
}
