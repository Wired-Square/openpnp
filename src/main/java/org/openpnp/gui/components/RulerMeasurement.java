package org.openpnp.gui.components;

import java.util.Locale;

import org.openpnp.model.Location;

/**
 * A straight-line measurement between two points in machine coordinates, as drawn with the
 * camera view's ruler.
 */
public final class RulerMeasurement {
    private static final double SNAP_STEP_DEGREES = 45;

    private final Location start;
    private final Location end;

    public RulerMeasurement(Location start, Location end) {
        this.start = start;
        this.end = end.convertToUnits(start.getUnits());
    }

    /**
     * @return a measurement from start whose end keeps its distance but turns to the nearest
     *         multiple of 45°.
     */
    public static RulerMeasurement snapped(Location start, Location end) {
        RulerMeasurement free = new RulerMeasurement(start, end);
        double degrees = free.getAngle();
        double angle = Math.toRadians(Math.signum(degrees)
                * Math.round(Math.abs(degrees) / SNAP_STEP_DEGREES) * SNAP_STEP_DEGREES);
        double length = free.getLength();
        return new RulerMeasurement(start, start.add(new Location(start.getUnits(),
                length * Math.cos(angle), length * Math.sin(angle), 0, 0)));
    }

    public Location getStart() {
        return start;
    }

    public Location getEnd() {
        return end;
    }

    public double getDeltaX() {
        return end.getX() - start.getX();
    }

    public double getDeltaY() {
        return end.getY() - start.getY();
    }

    public double getLength() {
        return Math.hypot(getDeltaX(), getDeltaY());
    }

    /**
     * @return the direction from start to end in degrees, counter-clockwise from +X.
     */
    public double getAngle() {
        return Math.toDegrees(Math.atan2(getDeltaY(), getDeltaX()));
    }

    public String getLabel() {
        return String.format(Locale.US, "%.3f %s  ΔX %.3f  ΔY %.3f  %.1f°", getLength(), //$NON-NLS-1$
                start.getUnits().getShortName(), getDeltaX(), getDeltaY(), getAngle());
    }
}
