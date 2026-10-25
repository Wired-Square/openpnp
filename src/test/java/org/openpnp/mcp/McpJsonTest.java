package org.openpnp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

import com.google.gson.JsonObject;

public class McpJsonTest {
    private static final double EPSILON = 1e-9;

    @Test
    public void locationsAreRoundedMillimetres() {
        JsonObject json = McpJson.location(
                new Location(LengthUnit.Inches, 1, -1e-14, 0, 90.000000001));

        assertEquals(25.4, json.get("x").getAsDouble(), EPSILON);
        assertEquals("0.0", json.get("y").getAsString());
        assertEquals(90, json.get("rotation").getAsDouble(), EPSILON);
    }

    @Test
    public void aNonFiniteLocationIsRefusedRatherThanReportedAsZero() {
        assertThrows(IllegalStateException.class, () -> McpJson.location(
                new Location(LengthUnit.Millimeters, Double.NaN, 0, 0, 0)));
    }
}
