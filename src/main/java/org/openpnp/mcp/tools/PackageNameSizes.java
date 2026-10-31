package org.openpnp.mcp.tools;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Body sizes read from a package name, for the naming conventions that state them. Sizes are in
 * mm, width along the footprint's X axis.
 */
final class PackageNameSizes {
    static final class Size {
        final double width;
        final double height;
        /** Which convention matched, for the operator. */
        final String rule;

        Size(double width, double height, String rule) {
            this.width = width;
            this.height = height;
            this.rule = rule;
        }
    }

    private static final double TENTHS = 10;
    private static final double HUNDREDTHS = 100;

    /** KiCad chip names give the metric code, length then width in 0.1 mm: C_0603_1608Metric. */
    private static final Pattern METRIC_CHIP = Pattern.compile("(?<![0-9])(\\d{2})(\\d{2})Metric");
    /** KiCad names that state the body: SOIC-8_3.9x4.9mm, TSSOP-16_4.4x5mm. */
    private static final Pattern BODY_MM = Pattern.compile(
            "_(\\d+(?:\\.\\d+)?)x(\\d+(?:\\.\\d+)?)mm", Pattern.CASE_INSENSITIVE);
    /**
     * IPC-7351 no-lead names give the body after the pitch, in 0.01 mm: QFN50P400X400X80-21N.
     * Leaded names (SOP, SOT) give the lead span there instead, so they are not read.
     */
    private static final Pattern IPC_NO_LEAD = Pattern.compile(
            "^(?:QFN|DFN|SON|LGA|BGA)\\d+P(\\d+)X(\\d+)X\\d+", Pattern.CASE_INSENSITIVE);
    /** Imperial chip codes as bare names: R0603, C0805, 0402. */
    private static final Pattern IMPERIAL_CHIP = Pattern.compile("^[A-Z]{0,3}(\\d{4})$",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, double[]> IMPERIAL_SIZES = Map.of(
            "0201", new double[] {0.6, 0.3},
            "0402", new double[] {1.0, 0.5},
            "0603", new double[] {1.6, 0.8},
            "0805", new double[] {2.0, 1.25},
            "1206", new double[] {3.2, 1.6},
            "1210", new double[] {3.2, 2.5},
            "1812", new double[] {4.5, 3.2},
            "2010", new double[] {5.0, 2.5},
            "2512", new double[] {6.3, 3.2});

    private PackageNameSizes() {
    }

    /** @return the size the name states, or null. */
    static Size of(String name) {
        Matcher matcher = METRIC_CHIP.matcher(name);
        if (matcher.find()) {
            return new Size(Integer.parseInt(matcher.group(1)) / TENTHS,
                    Integer.parseInt(matcher.group(2)) / TENTHS, "KiCad metric chip code");
        }
        matcher = BODY_MM.matcher(name);
        if (matcher.find()) {
            return new Size(Double.parseDouble(matcher.group(1)),
                    Double.parseDouble(matcher.group(2)), "KiCad body size in the name");
        }
        matcher = IPC_NO_LEAD.matcher(name);
        if (matcher.find()) {
            return new Size(Integer.parseInt(matcher.group(1)) / HUNDREDTHS,
                    Integer.parseInt(matcher.group(2)) / HUNDREDTHS, "IPC-7351 body size");
        }
        matcher = IMPERIAL_CHIP.matcher(name);
        if (matcher.find()) {
            double[] size = IMPERIAL_SIZES.get(matcher.group(1));
            if (size != null) {
                return new Size(size[0], size[1], "imperial chip code");
            }
        }
        return null;
    }
}
