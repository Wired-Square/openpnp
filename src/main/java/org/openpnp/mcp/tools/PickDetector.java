package org.openpnp.mcp.tools;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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
 * Finds the tape pocket and the part near the centre of a top camera frame, given the part's
 * body size. The pocket is a dark outline a little larger than the body. The part is an outline
 * matching the body, dark on light or light on dark, or failing that the hull of its bright
 * features (terminations or leads), which finds two-tone chips and dark bodies on dark tape.
 * Pixel coordinates have Y pointing down.
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
    /** A pocket holds the body: no side smaller than the body's. */
    static final double POCKET_MIN = 0.95;
    /** A pocket side is at most this many times the body's, leads and clearance included. */
    static final double POCKET_MAX = 2.0;
    /** At least one pocket side has this much clearance, so a bare body is not a pocket. */
    static final double POCKET_CLEARANCE = 1.1;
    /** Without a pocket, features count within this many body half-diagonals of the centre. */
    static final double FEATURE_RADIUS_HALF_DIAGONALS = 1.5;
    /** A feature hull spans at most this many body lengths (leads stand out from the body). */
    static final double FEATURE_HULL_MAX = 2.0;
    /** A feature hull spans at least this fraction of the body, each way. */
    static final double FEATURE_HULL_MIN = 0.6;
    /** Features smaller than this fraction of the body's shorter side are noise. */
    static final double FEATURE_MIN_SIDE = 0.06;
    /**
     * A feature hull within this factor of the body on both sides is the body's own outline
     * (a chip's terminations), so its long side is the body's.
     */
    static final double FEATURE_MATCH = 1.15;
    private static final int MIN_FEATURES = 2;
    /** A pocket holding a part at the pick point may be centred up to this far off. */
    static final double HOLDER_BODY_LENGTHS = 1;
    /**
     * A feature's mirror image across a hull axis must land within this fraction of the body's
     * shorter side of another feature: terminations and leads are symmetric, glints are not.
     */
    static final double MIRROR_TOLERANCE = 0.25;
    private static final int BLUR_KERNEL_PX = 5;
    private static final double MAX_GREY = 255;
    private static final double HALF_TURN = 180;
    private static final int OUTLINE_THICKNESS_PX = 2;
    private static final int CENTRE_MARK_PX = 6;
    private static final Scalar EXPECTED_COLOUR = new Scalar(255, 200, 0);
    private static final Scalar FOUND_COLOUR = new Scalar(0, 220, 0);
    private static final Scalar POCKET_COLOUR = new Scalar(255, 0, 255);
    private static final Scalar FAR_COLOUR = new Scalar(0, 140, 255);

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
        /**
         * True when found from bright features: the centre holds, but the hull of a leaded part
         * is longest across its leads, so the angle is only known to a quarter turn.
         */
        final boolean fromFeatures;
        final RotatedRect rect;

        Detection(RotatedRect rect, double longPx, double shortPx, double longAxisImageDegrees,
                boolean light, boolean fromFeatures) {
            this.rect = rect;
            this.x = rect.center.x;
            this.y = rect.center.y;
            this.longPx = longPx;
            this.shortPx = shortPx;
            this.longAxisImageDegrees = longAxisImageDegrees;
            this.light = light;
            this.fromFeatures = fromFeatures;
        }

        Detection offset(double dx, double dy) {
            return new Detection(new RotatedRect(new Point(x + dx, y + dy), rect.size, rect.angle),
                    longPx, shortPx, longAxisImageDegrees, light, fromFeatures);
        }
    }

    static final class Result {
        /** The tape pocket at the pick point, or null where none shows (black tape, loose parts). */
        final Detection pocket;
        /** The part at the pick point, or null. */
        final Detection part;
        /**
         * With nothing at the pick point, the nearest pocket- or part-sized outline further off,
         * often the next pocket; never judged. Null otherwise.
         */
        final Detection far;
        /**
         * The pocket outline is also the size of the body and nothing was found inside it, so it
         * may be a dark part filling the pocket rather than an empty pocket.
         */
        final boolean pocketMayBePart;

        Result(Detection pocket, Detection part, Detection far, boolean pocketMayBePart) {
            this.pocket = pocket;
            this.part = part;
            this.far = far;
            this.pocketMayBePart = pocketMayBePart;
        }
    }

    private PickDetector() {
    }

    /**
     * Finds the pocket and the part within nearPx of the frame centre; either may be null.
     * Outlines further off are only reported as {@link Result#far}.
     */
    static Result detect(BufferedImage image, double longPx, double shortPx, double nearPx) {
        Mat frame = OpenCvUtils.toMat(image);
        Mat grey = new Mat();
        Mat region = null;
        try {
            if (frame.channels() == 1) {
                frame.copyTo(grey);
            }
            else {
                Imgproc.cvtColor(frame, grey, Imgproc.COLOR_BGR2GRAY);
            }
            Rect window = searchWindow(grey.cols(), grey.rows(), longPx);
            region = new Mat(grey, window);
            Imgproc.GaussianBlur(region, region, new Size(BLUR_KERNEL_PX, BLUR_KERNEL_PX), 0);
            Point centre = new Point(grey.cols() / 2.0 - window.x, grey.rows() / 2.0 - window.y);
            List<Detection> dark = outlines(region, false);
            List<Detection> light = outlines(region, true);
            List<Detection> all = concat(dark, light);
            Predicate<Detection> isBody = d -> fits(d.longPx, longPx) && fits(d.shortPx, shortPx);

            Predicate<Detection> isPocket = d -> isPocket(d, longPx, shortPx);
            // A dark body can be pocket-sized too; the pocket is the larger outline around it.
            Detection globalPocket = largest(centre, dark, isPocket, nearPx);
            if (globalPocket == null) {
                // Over the whole window a shadow or the dark surround can shift the threshold
                // until the pocket merges with them; a box just big enough for a near pocket
                // has a threshold of its own.
                double half = POCKET_MAX * longPx / 2 + nearPx;
                Rect local = new Rect((int) Math.floor(centre.x - half),
                        (int) Math.floor(centre.y - half), (int) Math.ceil(2 * half),
                        (int) Math.ceil(2 * half));
                globalPocket = largest(centre, outlinesIn(region, local, false), isPocket,
                        nearPx);
            }
            Detection nearPocket = globalPocket;
            // A part in a pocket is nested in the pocket's outline, which external contours
            // leave out, so inside a pocket the outlines are thresholded on their own.
            List<Detection> brightInPocket = nearPocket != null
                    ? outlinesInside(region, nearPocket, true)
                    : null;
            Detection part = nearPocket != null
                    ? nearest(new Point(nearPocket.x, nearPocket.y),
                            concat(outlinesInside(region, nearPocket, false), brightInPocket),
                            isBody, nearPocket, Double.POSITIVE_INFINITY)
                    : nearest(centre, all, isBody, null, nearPx);
            Detection pocket = nearPocket;
            Detection farHull = null;
            if (part == null) {
                // Inside a pocket the features are thresholded on their own, as a bright
                // termination near the rim would otherwise merge with light tape.
                List<Detection> bright = pocket != null ? brightInPocket : light;
                part = features(bright, centre, longPx, shortPx, pocket);
                if (part != null && pocket == null && distance(part, centre) > nearPx) {
                    farHull = part;
                    part = null;
                }
            }
            if (pocket == null && part != null) {
                // A part near the pick point sits in its pocket even when the pocket's centre is
                // a little further off; that pocket is what the location should point at.
                pocket = smallestHolding(dark, isPocket, part, HOLDER_BODY_LENGTHS * longPx,
                        centre);
            }
            boolean pocketMayBePart = pocket != null && part == null && isBody.test(pocket);
            Detection far = farHull;
            if (pocket == null && part == null && far == null) {
                far = nearest(centre, all,
                        d -> isPocket(d, longPx, shortPx) || isBody.test(d), null,
                        Double.POSITIVE_INFINITY);
            }
            return new Result(shift(pocket, window), shift(part, window), shift(far, window),
                    pocketMayBePart);
        }
        finally {
            if (region != null) {
                region.release();
            }
            frame.release();
            grey.release();
        }
    }

    /** The largest match within the radius of the point. */
    private static Detection largest(Point from, List<Detection> outlines,
            Predicate<Detection> matches, double radius) {
        Detection best = null;
        for (Detection d : outlines) {
            if (matches.test(d) && distance(d, from) <= radius
                    && (best == null || d.longPx * d.shortPx > best.longPx * best.shortPx)) {
                best = d;
            }
        }
        return best;
    }

    /** The smallest match holding the detection, centred within the radius of the point. */
    private static Detection smallestHolding(List<Detection> outlines,
            Predicate<Detection> matches, Detection held, double radius, Point from) {
        Detection best = null;
        for (Detection d : outlines) {
            if (d != held && matches.test(d) && distance(d, from) <= radius
                    && inside(d, held.x, held.y)
                    && (best == null || d.longPx * d.shortPx < best.longPx * best.shortPx)) {
                best = d;
            }
        }
        return best;
    }

    private static Detection shift(Detection d, Rect window) {
        return d == null ? null : d.offset(window.x, window.y);
    }

    private static double distance(Detection d, Point from) {
        return Math.hypot(d.x - from.x, d.y - from.y);
    }

    /**
     * Whether an outline can line up with the body only one way: within the factor range with
     * long side to long side, but not long side to short side. Otherwise its angle is only
     * known to a quarter turn (a lead hull or a pocket with room for leads can be longest
     * across the body).
     */
    static boolean alignsOneWay(Detection d, double longPx, double shortPx, double min,
            double max) {
        boolean along = within(d.longPx / longPx, min, max)
                && within(d.shortPx / shortPx, min, max);
        boolean across = within(d.longPx / shortPx, min, max)
                && within(d.shortPx / longPx, min, max);
        return along && !across;
    }

    private static boolean within(double ratio, double min, double max) {
        return ratio >= min && ratio <= max;
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

    /** Outlines that don't touch the search window's edge, which are background. */
    private static List<Detection> outlines(Mat region, boolean light) {
        Mat binary = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        // A dark part is the foreground once the threshold is inverted.
        int type = (light ? Imgproc.THRESH_BINARY : Imgproc.THRESH_BINARY_INV)
                | Imgproc.THRESH_OTSU;
        try {
            Imgproc.threshold(region, binary, 0, MAX_GREY, type);
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);
        }
        finally {
            binary.release();
            hierarchy.release();
        }

        List<Detection> outlines = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            Rect bounds = Imgproc.boundingRect(contour);
            boolean touchesEdge = bounds.x <= 0 || bounds.y <= 0
                    || bounds.x + bounds.width >= region.cols()
                    || bounds.y + bounds.height >= region.rows();
            if (!touchesEdge) {
                outlines.add(measure(contour.toArray(), light, false));
            }
            contour.release();
        }
        return outlines;
    }

    /** Outlines within the pocket's bounds, in search window coordinates. */
    private static List<Detection> outlinesInside(Mat region, Detection pocket, boolean light) {
        return outlinesIn(region, pocket.rect.boundingRect(), light);
    }

    /**
     * Outlines within a box, thresholded on their own, in search window coordinates; outlines
     * touching the box's edge are left out.
     */
    private static List<Detection> outlinesIn(Mat region, Rect box, boolean light) {
        int left = Math.max(0, box.x);
        int top = Math.max(0, box.y);
        Rect bounds = new Rect(left, top,
                Math.min(region.cols(), box.x + box.width) - left,
                Math.min(region.rows(), box.y + box.height) - top);
        Mat inside = new Mat(region, bounds);
        Mat binary = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.threshold(inside, binary, 0, MAX_GREY,
                    (light ? Imgproc.THRESH_BINARY : Imgproc.THRESH_BINARY_INV)
                            | Imgproc.THRESH_OTSU);
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE, new Point(bounds.x, bounds.y));
        }
        finally {
            inside.release();
            binary.release();
            hierarchy.release();
        }
        // Light tape showing at the edge of the pocket's box is not part of the part.
        List<Detection> outlines = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            Rect blob = Imgproc.boundingRect(contour);
            boolean touchesEdge = blob.x <= bounds.x || blob.y <= bounds.y
                    || blob.x + blob.width >= bounds.x + bounds.width
                    || blob.y + blob.height >= bounds.y + bounds.height;
            if (!touchesEdge) {
                outlines.add(measure(contour.toArray(), light, false));
            }
            contour.release();
        }
        return outlines;
    }

    static boolean isPocket(Detection d, double longPx, double shortPx) {
        return d.longPx >= POCKET_MIN * longPx && d.shortPx >= POCKET_MIN * shortPx
                && d.longPx <= POCKET_MAX * longPx && d.shortPx <= POCKET_MAX * shortPx
                && (d.longPx >= POCKET_CLEARANCE * longPx
                        || d.shortPx >= POCKET_CLEARANCE * shortPx);
    }

    /** The match nearest the point within the radius, inside the pocket when there is one. */
    private static Detection nearest(Point from, List<Detection> outlines,
            Predicate<Detection> matches, Detection pocket, double radius) {
        Detection best = null;
        double bestDistance = radius;
        for (Detection d : outlines) {
            if (!matches.test(d) || (pocket != null && !inside(pocket, d.x, d.y))) {
                continue;
            }
            double distance = distance(d, from);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = d;
            }
        }
        return best;
    }

    /**
     * The hull of the bright features in the pocket, or near the centre without one: a chip's
     * terminations or a leaded part's leads, when its body doesn't stand out.
     */
    private static Detection features(List<Detection> light, Point centre, double longPx,
            double shortPx, Detection pocket) {
        double radius = FEATURE_RADIUS_HALF_DIAGONALS * Math.hypot(longPx, shortPx) / 2;
        double minSide = FEATURE_MIN_SIDE * shortPx;
        List<Point> points = new ArrayList<>();
        List<Point> centres = new ArrayList<>();
        for (Detection d : light) {
            boolean near = pocket != null
                    ? inside(pocket, d.x, d.y)
                    : Math.hypot(d.x - centre.x, d.y - centre.y) <= radius;
            if (near && d.shortPx >= minSide && d.longPx < longPx) {
                Point[] corners = new Point[4];
                d.rect.points(corners);
                points.addAll(List.of(corners));
                centres.add(new Point(d.x, d.y));
            }
        }
        if (centres.size() < MIN_FEATURES) {
            return null;
        }
        Detection hull = measure(points.toArray(new Point[0]), true, true);
        // A lone row of leads is thin; a real hull spans the body both ways.
        if (hull.longPx > FEATURE_HULL_MAX * longPx || hull.longPx < FEATURE_HULL_MIN * longPx
                || hull.shortPx < FEATURE_HULL_MIN * shortPx) {
            return null;
        }
        double tolerance = MIRROR_TOLERANCE * shortPx;
        double axis = Math.toRadians(hull.longAxisImageDegrees);
        if (!mirrored(centres, hull, axis, tolerance)
                && !mirrored(centres, hull, axis + Math.PI / 2, tolerance)) {
            return null;
        }
        return hull;
    }

    /**
     * Whether the features mirror each other across the hull axis: each feature off the axis
     * has a different feature at its mirror image, and at least two are off it. Features on the
     * axis mirror themselves, so a single row of leads along it doesn't count.
     */
    private static boolean mirrored(List<Point> centres, Detection hull, double axis,
            double tolerance) {
        double ux = Math.cos(axis);
        double uy = Math.sin(axis);
        int offAxis = 0;
        for (Point p : centres) {
            double dx = p.x - hull.x;
            double dy = p.y - hull.y;
            double along = dx * ux + dy * uy;
            double across = -dx * uy + dy * ux;
            if (Math.abs(across) <= tolerance) {
                continue;
            }
            offAxis++;
            // Reflect across the axis: keep the along component, negate the across one.
            double mx = hull.x + along * ux + across * uy;
            double my = hull.y + along * uy - across * ux;
            boolean partnered = false;
            for (Point q : centres) {
                if (q != p && Math.hypot(q.x - mx, q.y - my) <= tolerance) {
                    partnered = true;
                    break;
                }
            }
            if (!partnered) {
                return false;
            }
        }
        return offAxis >= MIN_FEATURES;
    }

    private static boolean inside(Detection outline, double x, double y) {
        Point[] corners = new Point[4];
        outline.rect.points(corners);
        MatOfPoint2f polygon = new MatOfPoint2f(corners);
        try {
            return Imgproc.pointPolygonTest(polygon, new Point(x, y), false) >= 0;
        }
        finally {
            polygon.release();
        }
    }

    private static List<Detection> concat(List<Detection> first, List<Detection> second) {
        List<Detection> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static Detection measure(Point[] contour, boolean light, boolean fromFeatures) {
        MatOfPoint2f points = new MatOfPoint2f(contour);
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
        return new Detection(rect, Math.max(side1, side2), Math.min(side1, side2), angle, light,
                fromFeatures);
    }

    private static boolean fits(double measured, double expected) {
        return measured >= expected / SIZE_TOLERANCE && measured <= expected * SIZE_TOLERANCE;
    }

    /** The frame with the expected body outline and whatever was found drawn on it. */
    static BufferedImage annotate(BufferedImage image, RotatedRect expected, Result found) {
        Mat frame = OpenCvUtils.toMat(image);
        try {
            if (frame.channels() == 1) {
                Imgproc.cvtColor(frame, frame, Imgproc.COLOR_GRAY2BGR);
            }
            draw(frame, expected, EXPECTED_COLOUR);
            if (found != null && found.far != null) {
                draw(frame, found.far.rect, FAR_COLOUR);
            }
            if (found != null && found.pocket != null) {
                draw(frame, found.pocket.rect, POCKET_COLOUR);
            }
            if (found != null && found.part != null) {
                draw(frame, found.part.rect, FOUND_COLOUR);
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
