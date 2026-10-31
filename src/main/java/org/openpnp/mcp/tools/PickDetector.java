package org.openpnp.mcp.tools;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.openpnp.util.OpenCvUtils;

/**
 * Finds a part body of a known size near the centre of a top camera frame: the outline nearest
 * the centre whose minimum area rectangle matches the body, as a dark part on a light ground or
 * a light part on a dark one. Pixel coordinates have Y pointing down.
 */
final class PickDetector {
    /** A side may differ from the expected size by this factor either way. */
    static final double SIZE_TOLERANCE = 1.35;
    /**
     * How far from the centre to search, in body lengths: far enough that a part one tape pitch
     * (often two to four body lengths) from its expected place is still measured, not missed.
     */
    static final double SEARCH_BODY_LENGTHS = 4;
    /** The search window never shrinks below this, so tiny parts still have context. */
    static final double MIN_SEARCH_HALF_PX = 40;
    private static final int BLUR_KERNEL_PX = 5;
    private static final double MAX_GREY = 255;
    private static final double HALF_TURN = 180;
    private static final int OUTLINE_THICKNESS_PX = 2;
    private static final int CENTRE_MARK_PX = 6;
    private static final Scalar EXPECTED_COLOUR = new Scalar(255, 200, 0);
    private static final Scalar FOUND_COLOUR = new Scalar(0, 220, 0);

    static {
        nu.pattern.OpenCV.loadLocally();
    }

    static final class Detection {
        /** Body centre in the full frame, in pixels. */
        final double x;
        final double y;
        final double longPx;
        final double shortPx;
        /** The long side's direction in the image, degrees from +X toward +Y (down), 0 to 180. */
        final double longAxisImageDegrees;
        /** True for a light part on a dark ground. */
        final boolean light;
        final RotatedRect rect;

        Detection(RotatedRect rect, double longPx, double shortPx, double longAxisImageDegrees,
                boolean light) {
            this.rect = rect;
            this.x = rect.center.x;
            this.y = rect.center.y;
            this.longPx = longPx;
            this.shortPx = shortPx;
            this.longAxisImageDegrees = longAxisImageDegrees;
            this.light = light;
        }
    }

    private PickDetector() {
    }

    /**
     * @return the matching outline nearest the frame centre, or null if none matches.
     */
    static Detection detect(BufferedImage image, double longPx, double shortPx) {
        Mat frame = OpenCvUtils.toMat(image);
        Mat grey = new Mat();
        try {
            if (frame.channels() == 1) {
                frame.copyTo(grey);
            }
            else {
                Imgproc.cvtColor(frame, grey, Imgproc.COLOR_BGR2GRAY);
            }
            Rect window = searchWindow(grey.cols(), grey.rows(), longPx);
            Mat region = new Mat(grey, window);
            Imgproc.GaussianBlur(region, region, new Size(BLUR_KERNEL_PX, BLUR_KERNEL_PX), 0);
            Point centre = new Point(grey.cols() / 2.0 - window.x, grey.rows() / 2.0 - window.y);

            Detection best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (boolean light : new boolean[] {false, true}) {
                for (Detection candidate : candidates(region, longPx, shortPx, light)) {
                    double distance = Math.hypot(candidate.x - centre.x, candidate.y - centre.y);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
            region.release();
            if (best == null) {
                return null;
            }
            RotatedRect inFrame = new RotatedRect(
                    new Point(best.x + window.x, best.y + window.y), best.rect.size,
                    best.rect.angle);
            return new Detection(inFrame, best.longPx, best.shortPx, best.longAxisImageDegrees,
                    best.light);
        }
        finally {
            frame.release();
            grey.release();
        }
    }

    static Rect searchWindow(int width, int height, double longPx) {
        double half = searchHalfWidth(longPx);
        int left = (int) Math.max(0, Math.floor(width / 2.0 - half));
        int top = (int) Math.max(0, Math.floor(height / 2.0 - half));
        int right = (int) Math.min(width, Math.ceil(width / 2.0 + half));
        int bottom = (int) Math.min(height, Math.ceil(height / 2.0 + half));
        return new Rect(left, top, right - left, bottom - top);
    }

    static double searchHalfWidth(double longPx) {
        return Math.max(MIN_SEARCH_HALF_PX, SEARCH_BODY_LENGTHS * longPx);
    }

    private static List<Detection> candidates(Mat region, double longPx, double shortPx,
            boolean light) {
        Mat binary = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        // A dark part is the foreground once the threshold is inverted.
        int type = (light ? Imgproc.THRESH_BINARY : Imgproc.THRESH_BINARY_INV)
                | Imgproc.THRESH_OTSU;
        Imgproc.threshold(region, binary, 0, MAX_GREY, type);
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE);
        binary.release();
        hierarchy.release();

        List<Detection> matches = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            Rect bounds = Imgproc.boundingRect(contour);
            boolean touchesEdge = bounds.x <= 0 || bounds.y <= 0
                    || bounds.x + bounds.width >= region.cols()
                    || bounds.y + bounds.height >= region.rows();
            if (!touchesEdge) {
                Detection detection = measure(contour, light);
                if (fits(detection.longPx, longPx) && fits(detection.shortPx, shortPx)) {
                    matches.add(detection);
                }
            }
            contour.release();
        }
        return matches;
    }

    private static Detection measure(MatOfPoint contour, boolean light) {
        MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
        RotatedRect rect = Imgproc.minAreaRect(points);
        points.release();
        Point[] corners = new Point[4];
        rect.points(corners);
        // Measured from the corners, as OpenCV's angle convention changed between versions.
        double side1 = Math.hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y);
        double side2 = Math.hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y);
        Point from = side1 >= side2 ? corners[0] : corners[1];
        Point to = side1 >= side2 ? corners[1] : corners[2];
        double angle = Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
        angle = ((angle % HALF_TURN) + HALF_TURN) % HALF_TURN;
        return new Detection(rect, Math.max(side1, side2), Math.min(side1, side2), angle, light);
    }

    private static boolean fits(double measured, double expected) {
        return measured >= expected / SIZE_TOLERANCE && measured <= expected * SIZE_TOLERANCE;
    }

    /** The frame with the expected body outline and, if found, the detected one drawn on it. */
    static BufferedImage annotate(BufferedImage image, RotatedRect expected, Detection found) {
        Mat frame = OpenCvUtils.toMat(image);
        try {
            if (frame.channels() == 1) {
                Imgproc.cvtColor(frame, frame, Imgproc.COLOR_GRAY2BGR);
            }
            draw(frame, expected, EXPECTED_COLOUR);
            if (found != null) {
                draw(frame, found.rect, FOUND_COLOUR);
            }
            return OpenCvUtils.toBufferedImage(frame);
        }
        finally {
            frame.release();
        }
    }

    private static void draw(Mat frame, RotatedRect rect, Scalar colour) {
        Point[] corners = new Point[4];
        rect.points(corners);
        for (int i = 0; i < corners.length; i++) {
            Imgproc.line(frame, corners[i], corners[(i + 1) % corners.length], colour,
                    OUTLINE_THICKNESS_PX);
        }
        Point c = rect.center;
        Imgproc.line(frame, new Point(c.x - CENTRE_MARK_PX, c.y),
                new Point(c.x + CENTRE_MARK_PX, c.y), colour, 1);
        Imgproc.line(frame, new Point(c.x, c.y - CENTRE_MARK_PX),
                new Point(c.x, c.y + CENTRE_MARK_PX), colour, 1);
    }
}
