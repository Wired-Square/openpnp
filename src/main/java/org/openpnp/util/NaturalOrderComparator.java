package org.openpnp.util;

import java.util.Comparator;

/**
 * Orders strings the way people read them: runs of ASCII digits compare by numeric value, so
 * "Slot 2" sorts before "Slot 10", and other characters compare case-insensitively. Strings that
 * are equal under those rules fall back to {@link String#compareTo}, so the order is total and
 * consistent with equals.
 */
public final class NaturalOrderComparator implements Comparator<String> {
    public static final NaturalOrderComparator INSTANCE = new NaturalOrderComparator();

    private NaturalOrderComparator() {
    }

    @Override
    public int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (isDigit(ca) && isDigit(cb)) {
                int endA = endOfDigits(a, i);
                int endB = endOfDigits(b, j);
                int result = compareNumbers(a, skipZeros(a, i, endA), endA, b, skipZeros(b, j, endB), endB);
                if (result != 0) {
                    return result;
                }
                i = endA;
                j = endB;
            }
            else {
                int result = Character.compare(fold(ca), fold(cb));
                if (result != 0) {
                    return result;
                }
                i++;
                j++;
            }
        }
        if (i < a.length() || j < b.length()) {
            return i < a.length() ? 1 : -1;
        }
        return a.compareTo(b);
    }

    /** Compares two digit runs without leading zeros, of any length, by numeric value. */
    private static int compareNumbers(String a, int startA, int endA, String b, int startB, int endB) {
        int lengthA = endA - startA;
        int lengthB = endB - startB;
        if (lengthA != lengthB) {
            return Integer.compare(lengthA, lengthB);
        }
        for (int k = 0; k < lengthA; k++) {
            int result = Character.compare(a.charAt(startA + k), b.charAt(startB + k));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int endOfDigits(String s, int start) {
        int end = start;
        while (end < s.length() && isDigit(s.charAt(end))) {
            end++;
        }
        return end;
    }

    private static int skipZeros(String s, int start, int end) {
        int first = start;
        while (first < end - 1 && s.charAt(first) == '0') {
            first++;
        }
        return first;
    }

    private static char fold(char c) {
        return Character.toLowerCase(Character.toUpperCase(c));
    }
}
