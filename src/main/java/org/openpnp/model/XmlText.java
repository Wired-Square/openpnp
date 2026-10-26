package org.openpnp.model;

/**
 * Checks free text before it is saved in the configuration. Simple XML escapes only markup
 * characters and writes everything else as is, so a character XML 1.0 doesn't allow would leave a
 * file that can't be loaded again.
 */
public final class XmlText {
    private static final int TAB = 0x9;
    private static final int LINE_FEED = 0xA;
    private static final int CARRIAGE_RETURN = 0xD;
    private static final int FIRST_PRINTABLE = 0x20;
    private static final int LAST_BEFORE_SURROGATES = 0xD7FF;
    private static final int FIRST_AFTER_SURROGATES = 0xE000;
    private static final int LAST_BMP_CHARACTER = 0xFFFD;
    private static final int FIRST_SUPPLEMENTARY = 0x10000;

    private XmlText() {
    }

    /**
     * @throws IllegalArgumentException naming the first character XML 1.0 doesn't allow.
     */
    public static void requireValid(String text, String what) {
        text.codePoints().filter(c -> !isAllowed(c)).findFirst().ifPresent(c -> {
            throw new IllegalArgumentException(
                    String.format("%s contains U+%04X, which can't be saved", what, c));
        });
    }

    private static boolean isAllowed(int c) {
        return c == TAB || c == LINE_FEED || c == CARRIAGE_RETURN
                || (c >= FIRST_PRINTABLE && c <= LAST_BEFORE_SURROGATES)
                || (c >= FIRST_AFTER_SURROGATES && c <= LAST_BMP_CHARACTER)
                || c >= FIRST_SUPPLEMENTARY;
    }
}
