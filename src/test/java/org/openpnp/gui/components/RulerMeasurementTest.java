package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Point;

import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

public class RulerMeasurementTest {
    private static final double TOLERANCE = 1e-9;
    private static final double UNITS_PER_PIXEL = 0.02;
    private static final int IMAGE_X = 40;
    private static final int IMAGE_Y = 30;
    private static final int SCALED_WIDTH = 640;
    private static final int SCALED_HEIGHT = 480;

    private static Location mm(double x, double y) {
        return new Location(LengthUnit.Millimeters, x, y, 0, 0);
    }

    @Test
    public void measuresLengthDeltasAndCounterClockwiseAngle() {
        RulerMeasurement ruler = new RulerMeasurement(mm(10, 20), mm(13, 16));

        assertEquals(3, ruler.getDeltaX(), TOLERANCE);
        assertEquals(-4, ruler.getDeltaY(), TOLERANCE);
        assertEquals(5, ruler.getLength(), TOLERANCE);
        assertEquals(Math.toDegrees(Math.atan2(-4, 3)), ruler.getAngle(), TOLERANCE);
    }

    @Test
    public void measuresAcrossUnits() {
        RulerMeasurement ruler = new RulerMeasurement(mm(0, 0),
                new Location(LengthUnit.Inches, 1, 0, 0, 0));

        assertEquals(25.4, ruler.getLength(), TOLERANCE);
    }

    @Test
    public void snappingKeepsLengthAndTurnsToTheNearest45Degrees() {
        RulerMeasurement snapped = RulerMeasurement.snapped(mm(0, 0), mm(10, 1));

        assertEquals(0, snapped.getAngle(), TOLERANCE);
        assertEquals(Math.hypot(10, 1), snapped.getLength(), TOLERANCE);

        RulerMeasurement diagonal = RulerMeasurement.snapped(mm(0, 0), mm(-5, -6));

        assertEquals(-135, diagonal.getAngle(), TOLERANCE);
    }

    @Test
    public void viewOffsetsPointUpAndRightFromTheImageCentre() {
        CameraView.ViewGeometry geometry = new CameraView.ViewGeometry(IMAGE_X, IMAGE_Y,
                SCALED_WIDTH, SCALED_HEIGHT, UNITS_PER_PIXEL, UNITS_PER_PIXEL,
                LengthUnit.Millimeters);
        int pixels = 50;

        Location offsets = geometry.offsetsFromXy(IMAGE_X + SCALED_WIDTH / 2 + pixels,
                IMAGE_Y + SCALED_HEIGHT / 2 + 1 - pixels);

        assertEquals(pixels * UNITS_PER_PIXEL, offsets.getX(), TOLERANCE);
        assertEquals(pixels * UNITS_PER_PIXEL, offsets.getY(), TOLERANCE);
    }

    @Test
    public void viewPointsRoundTripThroughOffsets() {
        CameraView.ViewGeometry geometry = new CameraView.ViewGeometry(IMAGE_X, IMAGE_Y,
                SCALED_WIDTH, SCALED_HEIGHT, UNITS_PER_PIXEL, UNITS_PER_PIXEL,
                LengthUnit.Millimeters);
        Point point = new Point(123, 456);

        assertEquals(point, geometry.xyFromOffsets(geometry.offsetsFromXy(point.x, point.y)));
    }
}
