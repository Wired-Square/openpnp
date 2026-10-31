package org.openpnp.mcp.tools;

import java.util.Locale;

/**
 * Compares a part body found at a pick with where the feeder says it is, in machine
 * coordinates and mm.
 */
final class PickCheck {
    enum Flag {
        OK, MARGINAL, OFF
    }

    static final double DEFAULT_OK_FRACTION = 0.15;
    static final double DEFAULT_MARGINAL_FRACTION = 0.30;
    static final double ANGLE_MARGINAL_DEGREES = 5;
    static final double ANGLE_OFF_DEGREES = 15;
    /** A body this close to square looks the same turned a quarter turn. */
    static final double SQUARE_ASPECT = 1.15;
    private static final double HALF_TURN = 180;
    private static final double QUARTER_TURN = 90;

    final double dx;
    final double dy;
    final double distance;
    /** Signed, counter-clockwise positive, folded by the body's symmetry. */
    final double angleError;
    final Flag flag;
    final String reason;

    private PickCheck(double dx, double dy, double angleError, Flag flag, String reason) {
        this.dx = dx;
        this.dy = dy;
        this.distance = Math.hypot(dx, dy);
        this.angleError = angleError;
        this.flag = flag;
        this.reason = reason;
    }

    /**
     * @param expectedX where the body centre should be
     * @param expectedRotation the footprint zero's rotation at the pick
     * @param measuredLongAxis the found body's long side direction, degrees, any half turn
     * @param bodyWidth along the footprint's X
     * @param bodyHeight along the footprint's Y
     * @param maxPickTolerance the nozzle tip's limit in mm, or 0 for none
     */
    static PickCheck evaluate(double expectedX, double expectedY, double expectedRotation,
            double measuredX, double measuredY, double measuredLongAxis, double bodyWidth,
            double bodyHeight, double okFraction, double marginalFraction,
            double maxPickTolerance) {
        double dx = measuredX - expectedX;
        double dy = measuredY - expectedY;
        double distance = Math.hypot(dx, dy);
        double expectedLongAxis = bodyWidth >= bodyHeight
                ? expectedRotation
                : expectedRotation + QUARTER_TURN;
        boolean square = Math.max(bodyWidth, bodyHeight)
                < SQUARE_ASPECT * Math.min(bodyWidth, bodyHeight);
        double angleError = fold(measuredLongAxis - expectedLongAxis,
                square ? QUARTER_TURN : HALF_TURN);

        double shorter = Math.min(bodyWidth, bodyHeight);
        Flag flag = Flag.OK;
        String reason = null;
        if (maxPickTolerance > 0 && distance > maxPickTolerance) {
            flag = Flag.OFF;
            reason = String.format(Locale.ROOT,
                    "%.2f mm off, beyond the nozzle tip's Max Pick Tolerance of %.2f mm",
                    distance, maxPickTolerance);
        }
        else if (distance > marginalFraction * shorter) {
            flag = Flag.OFF;
            reason = String.format(Locale.ROOT,
                    "%.2f mm off, over %.0f%% of the body's %.2f mm side", distance,
                    marginalFraction * 100, shorter);
        }
        else if (distance > okFraction * shorter) {
            flag = Flag.MARGINAL;
            reason = String.format(Locale.ROOT,
                    "%.2f mm off, over %.0f%% of the body's %.2f mm side", distance,
                    okFraction * 100, shorter);
        }
        if (Math.abs(angleError) > ANGLE_OFF_DEGREES) {
            flag = Flag.OFF;
            reason = join(reason, String.format(Locale.ROOT,
                    "turned %.1f°", angleError));
        }
        else if (Math.abs(angleError) > ANGLE_MARGINAL_DEGREES) {
            if (flag == Flag.OK) {
                flag = Flag.MARGINAL;
            }
            reason = join(reason, String.format(Locale.ROOT,
                    "turned %.1f°", angleError));
        }
        return new PickCheck(dx, dy, angleError, flag, reason);
    }

    /** Into (-period/2, period/2]. */
    static double fold(double angle, double period) {
        double folded = angle % period;
        if (folded > period / 2) {
            folded -= period;
        }
        else if (folded <= -period / 2) {
            folded += period;
        }
        return folded;
    }

    private static String join(String first, String second) {
        return first == null ? second : first + "; " + second;
    }
}
