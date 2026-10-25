package org.openpnp.mcp;

import com.google.gson.JsonObject;

/**
 * A tool the MCP server offers to an assistant. Calls run on the EDT, so a tool sees the same
 * consistent model as the UI and may change it.
 */
public interface McpTool {
    /**
     * What a tool may do. Tools above READ only run once the operator has enabled their tier.
     */
    enum Tier {
        READ,
        /** Records advice on the model for the operator to review, without changing behaviour. */
        SUGGEST,
        /** Changes configuration. */
        WRITE
    }

    String getName();

    String getDescription();

    /** A JSON Schema object describing the arguments. */
    JsonObject getInputSchema();

    Tier getTier();

    /**
     * @throws IllegalArgumentException when the arguments are invalid; the message is returned to
     *         the assistant.
     */
    McpToolResult call(JsonObject arguments) throws Exception;
}
