package org.openpnp.mcp.tools;

import org.openpnp.mcp.McpJson;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

import com.google.gson.JsonObject;

final class FeederLookup {
    static final String FEEDER_ID = "feederId";

    private FeederLookup() {
    }

    static Feeder require(Machine machine, JsonObject arguments) {
        String id = McpJson.optionalString(arguments, FEEDER_ID);
        if (id == null) {
            throw new IllegalArgumentException(FEEDER_ID + " is required");
        }
        Feeder feeder = machine.getFeeder(id);
        if (feeder == null) {
            throw new IllegalArgumentException("No feeder " + id);
        }
        return feeder;
    }
}
