package org.openpnp.mcp;

import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Conversions shared by the tools. Lengths are always reported in millimetres. */
public final class McpJson {
    /** Keeps 0.1 µm and 0.0001°, and hides floating point noise such as 1e-14. */
    private static final double ROUNDING_SCALE = 1e4;

    private McpJson() {
    }

    /**
     * @throws IllegalStateException when a coordinate is not finite, so an unconfigured feeder is
     *         never reported at the machine origin.
     */
    public static JsonObject location(Location location) {
        Location mm = location.convertToUnits(LengthUnit.Millimeters);
        if (!Double.isFinite(mm.getX()) || !Double.isFinite(mm.getY())
                || !Double.isFinite(mm.getZ()) || !Double.isFinite(mm.getRotation())) {
            throw new IllegalStateException("Location is not finite: " + mm);
        }
        JsonObject json = new JsonObject();
        json.addProperty("x", round(mm.getX()));
        json.addProperty("y", round(mm.getY()));
        json.addProperty("z", round(mm.getZ()));
        json.addProperty("rotation", round(mm.getRotation()));
        return json;
    }

    public static double millimetres(Length length) {
        return round(length.convertToUnits(LengthUnit.Millimeters).getValue());
    }

    public static double millimetres(double value, LengthUnit units) {
        return millimetres(new Length(value, units));
    }

    public static double round(double value) {
        double rounded = Math.round(value * ROUNDING_SCALE) / ROUNDING_SCALE;
        // Avoid reporting -0.0.
        return rounded == 0 ? 0 : rounded;
    }

    public static String optionalString(JsonObject arguments, String name) {
        JsonElement element = arguments.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return element.getAsString();
    }

    public static boolean optionalBoolean(JsonObject arguments, String name, boolean fallback) {
        JsonElement element = arguments.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return element.getAsBoolean();
    }
}
