package org.openpnp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.openpnp.util.OpenCvUtils;

public class PickVisionTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final double LIGHT = 200;
    private static final double DARK = 40;
    private static final double PIXEL = 1;
    private static final double DEGREE = 1;
    private static final double EPSILON = 1e-9;

    static {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Mat ground(double grey) {
        return new Mat(HEIGHT, WIDTH, CvType.CV_8UC3, new Scalar(grey, grey, grey));
    }

    private static void body(Mat frame, double x, double y, double width, double height,
            double angle, double grey) {
        Point[] corners = new Point[4];
        new RotatedRect(new Point(x, y), new Size(width, height), angle).points(corners);
        Imgproc.fillPoly(frame, List.of(new MatOfPoint(corners)), new Scalar(grey, grey, grey));
    }

    private static BufferedImage image(Mat frame) {
        return OpenCvUtils.toBufferedImage(frame);
    }

    @Test
    public void aDarkBodyOffCentreIsFoundWithItsAngle() {
        Mat frame = ground(LIGHT);
        body(frame, WIDTH / 2.0 + 10, HEIGHT / 2.0 - 5, 60, 30, 20, DARK);

        PickDetector.Detection found = PickDetector.detect(image(frame), 60, 30);

        assertNotNull(found);
        assertEquals(WIDTH / 2.0 + 10, found.x, PIXEL);
        assertEquals(HEIGHT / 2.0 - 5, found.y, PIXEL);
        assertEquals(60, found.longPx, 2 * PIXEL);
        assertEquals(30, found.shortPx, 2 * PIXEL);
        assertEquals(20, found.longAxisImageDegrees, DEGREE);
        assertFalse(found.light);
    }

    @Test
    public void aLightBodyOnADarkGroundIsFound() {
        Mat frame = ground(DARK);
        body(frame, WIDTH / 2.0, HEIGHT / 2.0, 30, 60, 0, LIGHT);

        PickDetector.Detection found = PickDetector.detect(image(frame), 60, 30);

        assertNotNull(found);
        assertTrue(found.light);
        assertEquals(90, found.longAxisImageDegrees, DEGREE);
    }

    @Test
    public void outlinesOfTheWrongSizeOrAtTheEdgeAreIgnored() {
        Mat frame = ground(LIGHT);
        // A pocket edge across the search window, and a small blob right at the centre.
        body(frame, WIDTH / 2.0, HEIGHT / 2.0 - 70, WIDTH, 20, 0, DARK);
        body(frame, WIDTH / 2.0, HEIGHT / 2.0, 12, 12, 0, DARK);
        body(frame, WIDTH / 2.0 - 40, HEIGHT / 2.0 + 30, 60, 30, 0, DARK);

        PickDetector.Detection found = PickDetector.detect(image(frame), 60, 30);

        assertNotNull(found);
        assertEquals(WIDTH / 2.0 - 40, found.x, PIXEL);
        assertEquals(HEIGHT / 2.0 + 30, found.y, PIXEL);
    }

    @Test
    public void aPartOnePocketAwayIsStillMeasured() {
        Mat frame = ground(LIGHT);
        // A 0603 at 20 px/mm, one 2 mm pitch from the centre.
        body(frame, WIDTH / 2.0 + 40, HEIGHT / 2.0, 32, 16, 0, DARK);

        PickDetector.Detection found = PickDetector.detect(image(frame), 32, 16);

        assertNotNull(found);
        assertEquals(WIDTH / 2.0 + 40, found.x, PIXEL);
    }

    @Test
    public void imageAnglesMapThroughEachAxisScale() {
        assertEquals(-30, CheckJobPicksTool.machineAngle(30, 0.02, 0.02), EPSILON);
        // A mirrored X axis turns the direction the other way, modulo the half turn.
        assertEquals(0, PickCheck.fold(CheckJobPicksTool.machineAngle(30, -0.02, 0.02) - 30,
                180), EPSILON);
    }

    @Test
    public void anEmptyPickFindsNothing() {
        assertNull(PickDetector.detect(image(ground(LIGHT)), 60, 30));
    }

    @Test
    public void theSearchWindowStaysInTheFrame() {
        org.opencv.core.Rect window = PickDetector.searchWindow(WIDTH, HEIGHT, 1000);

        assertEquals(0, window.x);
        assertEquals(WIDTH, window.width);
        assertEquals(HEIGHT, window.height);
        assertEquals(2 * PickDetector.MIN_SEARCH_HALF_PX,
                PickDetector.searchWindow(WIDTH, HEIGHT, 1).width, 1);
    }

    @Test
    public void annotatingKeepsTheFrameSize() {
        BufferedImage frame = image(ground(LIGHT));
        BufferedImage annotated = PickDetector.annotate(frame,
                new RotatedRect(new Point(WIDTH / 2.0, HEIGHT / 2.0), new Size(60, 30), 0), null);

        assertEquals(WIDTH, annotated.getWidth());
        assertEquals(HEIGHT, annotated.getHeight());
    }

    @Test
    public void anglesFoldByTheBodysSymmetry() {
        assertEquals(10, PickCheck.fold(190, 180), EPSILON);
        assertEquals(-10, PickCheck.fold(170, 180), EPSILON);
        assertEquals(90, PickCheck.fold(-90, 180), EPSILON);
        assertEquals(-5, PickCheck.fold(85, 90), EPSILON);
    }

    @Test
    public void offsetsAreFlaggedByFractionOfTheShorterSide() {
        // 1.6 × 0.8 body: ok to 0.12 mm, marginal to 0.24 mm.
        assertEquals(PickCheck.Flag.OK, check(0.1, 0, 0, 0).flag);
        assertEquals(PickCheck.Flag.MARGINAL, check(0.2, 0, 0, 0).flag);
        PickCheck off = check(0, -0.3, 0, 0);
        assertEquals(PickCheck.Flag.OFF, off.flag);
        assertEquals(-0.3, off.dy, EPSILON);
        assertTrue(off.reason.contains("30%"), off.reason);
    }

    @Test
    public void theNozzleTipToleranceCanMakeAPickOff() {
        PickCheck check = PickCheck.evaluate(0, 0, 0, 0.1, 0, 0, 1.6, 0.8, 0.15, 0.3, 0.05);

        assertEquals(PickCheck.Flag.OFF, check.flag);
        assertTrue(check.reason.contains("Max Pick Tolerance"), check.reason);
    }

    @Test
    public void theAngleIsComparedAlongTheLongSide() {
        // A tall body's long side is along the footprint's Y: 90° from its rotation.
        PickCheck tall = PickCheck.evaluate(0, 0, 0, 0, 0, 92, 0.8, 1.6, 0.15, 0.3, 0);
        assertEquals(2, tall.angleError, EPSILON);
        assertEquals(PickCheck.Flag.OK, tall.flag);
        // Upside down is the same outline.
        assertEquals(0, check(0, 0, 180, 0).angleError, EPSILON);
        assertEquals(PickCheck.Flag.MARGINAL, check(0, 0, 8, 0).flag);
        assertEquals(PickCheck.Flag.OFF, check(0, 0, -20, 0).flag);
        // A square body looks the same a quarter turn on.
        assertEquals(3, PickCheck.evaluate(0, 0, 0, 0, 0, 93, 4, 4, 0.15, 0.3, 0).angleError,
                EPSILON);
    }

    private static PickCheck check(double dx, double dy, double angle, double tolerance) {
        return PickCheck.evaluate(10, 20, 0, 10 + dx, 20 + dy, angle, 1.6, 0.8, 0.15, 0.3,
                tolerance);
    }
}
