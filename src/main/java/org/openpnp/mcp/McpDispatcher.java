package org.openpnp.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.openpnp.mcp.McpTool.Tier;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Answers MCP JSON-RPC messages. Transport-independent so the protocol can be tested without
 * HTTP.
 */
public final class McpDispatcher {
    /** Newest first; an unsupported client version is answered with the first. */
    static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of("2025-06-18", "2025-03-26", "2024-11-05");

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    private static final String SERVER_NAME = "openpnp";
    private static final String INSTRUCTIONS = "Tools for an OpenPnP pick and place machine. "
            + "All lengths are millimetres and all rotations degrees, counter-clockwise positive, "
            + "in machine coordinates unless a field says otherwise.";

    /** Runs a tool on the thread its tier requires. */
    @FunctionalInterface
    public interface ToolInvoker {
        McpToolResult invoke(McpTool tool, JsonObject arguments) throws Exception;
    }

    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final Predicate<Tier> tierEnabled;
    private final ToolInvoker invoker;
    private final String serverVersion;

    public McpDispatcher(List<McpTool> tools, Predicate<Tier> tierEnabled, ToolInvoker invoker,
            String serverVersion) {
        for (McpTool tool : tools) {
            if (this.tools.put(tool.getName(), tool) != null) {
                throw new IllegalArgumentException("Duplicate MCP tool " + tool.getName());
            }
        }
        this.tierEnabled = tierEnabled;
        this.invoker = invoker;
        this.serverVersion = serverVersion;
    }

    /**
     * @return the response to send, or empty for a notification, which gets none.
     */
    public Optional<JsonObject> handle(String message) {
        JsonElement element;
        try {
            element = JsonParser.parseString(message);
        }
        catch (JsonParseException e) {
            return Optional.of(error(null, PARSE_ERROR, "Parse error"));
        }
        if (!element.isJsonObject()) {
            // JSON-RPC batches were removed from MCP in 2025-06-18.
            return Optional.of(error(null, INVALID_REQUEST, "Expected a single JSON-RPC object"));
        }
        JsonObject request = element.getAsJsonObject();
        JsonElement id = request.get("id");
        try {
            return dispatch(request, id);
        }
        catch (RuntimeException e) {
            Logger.warn(e, "MCP request failed");
            return Optional.of(error(id, INTERNAL_ERROR, "Internal error: " + e));
        }
    }

    private Optional<JsonObject> dispatch(JsonObject request, JsonElement id) {
        JsonElement method = request.get("method");
        if (method == null || !isString(method)) {
            if (request.has("result") || request.has("error")) {
                // A response to a server request; this server makes none.
                return Optional.empty();
            }
            return Optional.of(error(id, INVALID_REQUEST, "Missing method"));
        }
        if (id == null || id.isJsonNull()) {
            return Optional.empty();
        }
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params")
                : new JsonObject();

        switch (method.getAsString()) {
            case "initialize":
                return Optional.of(result(id, initialize(params)));
            case "ping":
                return Optional.of(result(id, new JsonObject()));
            case "tools/list":
                return Optional.of(result(id, listTools()));
            case "tools/call":
                return Optional.of(callTool(id, params));
            default:
                return Optional.of(error(id, METHOD_NOT_FOUND,
                        "Method not found: " + method.getAsString()));
        }
    }

    private JsonObject initialize(JsonObject params) {
        JsonElement protocolVersion = params.get("protocolVersion");
        String requested = isString(protocolVersion) ? protocolVersion.getAsString() : null;
        String version = requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)
                ? requested
                : SUPPORTED_PROTOCOL_VERSIONS.get(0);

        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", serverVersion);

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", version);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    private JsonObject listTools() {
        JsonArray list = new JsonArray();
        for (McpTool tool : tools.values()) {
            JsonObject annotations = new JsonObject();
            annotations.addProperty("readOnlyHint", tool.getTier() == Tier.READ);

            JsonObject entry = new JsonObject();
            entry.addProperty("name", tool.getName());
            entry.addProperty("description", tool.getDescription());
            entry.add("inputSchema", tool.getInputSchema());
            entry.add("annotations", annotations);
            list.add(entry);
        }
        JsonObject result = new JsonObject();
        result.add("tools", list);
        return result;
    }

    private JsonObject callTool(JsonElement id, JsonObject params) {
        JsonElement name = params.get("name");
        if (!isString(name)) {
            return error(id, INVALID_PARAMS, "Missing tool name");
        }
        McpTool tool = tools.get(name.getAsString());
        if (tool == null) {
            return error(id, INVALID_PARAMS, "Unknown tool: " + name.getAsString());
        }
        JsonElement arguments = params.get("arguments");
        if (arguments != null && !arguments.isJsonNull() && !arguments.isJsonObject()) {
            return error(id, INVALID_PARAMS, "Tool arguments must be an object");
        }
        if (!tierEnabled.test(tool.getTier())) {
            return result(id, McpToolResult.error(String.format(
                    "%s tools are disabled. Ask the operator to enable them in Machine → MCP Server.",
                    tool.getTier())).toJson());
        }

        try {
            JsonObject args = arguments != null && arguments.isJsonObject()
                    ? arguments.getAsJsonObject()
                    : new JsonObject();
            return result(id, invoker.invoke(tool, args).toJson());
        }
        catch (IllegalArgumentException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            return result(id, McpToolResult.error(message).toJson());
        }
        catch (Exception e) {
            Logger.warn(e, "MCP tool {} failed", tool.getName());
            return result(id, McpToolResult.error(tool.getName() + " failed: " + e).toJson());
        }
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString();
    }

    private static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject response = envelope(id);
        response.add("result", result);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = envelope(id);
        response.add("error", error);
        return response;
    }

    private static JsonObject envelope(JsonElement id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        return response;
    }
}
