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
        WRITE,
        /** Moves the machine; every move also needs the operator's confirmation in OpenPnP. */
        MACHINE
    }

    String getName();

    String getDescription();

    /** A JSON Schema object describing the arguments. */
    JsonObject getInputSchema();

    Tier getTier();

    /**
     * Whether the call runs on the EDT. A tool that waits for the operator or for the machine
     * thread returns false and runs on the server thread, handing UI work to the EDT itself.
     */
    default boolean runsOnEdt() {
        return true;
    }

    /**
     * @throws IllegalArgumentException when the arguments are invalid; the message is returned to
     *         the assistant.
     */
    McpToolResult call(JsonObject arguments) throws Exception;
}
