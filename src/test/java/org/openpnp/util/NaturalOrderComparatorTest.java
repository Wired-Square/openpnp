package org.openpnp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

public class NaturalOrderComparatorTest {
    private static final NaturalOrderComparator COMPARATOR = NaturalOrderComparator.INSTANCE;

    @Test
    public void numbersSortByValue() {
        assertSorted("Slot 2: Photon", "Slot 10: Photon", "Slot 100: Photon");
    }

    @Test
    public void slotsWithoutAnAddressSortAfterNumberedSlots() {
        assertSorted("Slot 9: Photon", "Slot 254: Photon", "Slot None: Photon");
    }

    @Test
    public void textComparesCaseInsensitively() {
        assertSorted("apple", "Banana", "cherry");
    }

    @Test
    public void numbersLongerThanALongCompareByValue() {
        assertSorted("id 99999999999999999999", "id 100000000000000000000");
    }

    @Test
    public void leadingZerosDoNotChangeTheValue() {
        assertSorted("Slot 2", "Slot 005", "Slot 10");
    }

    @Test
    public void equalUnderNaturalRulesFallsBackToStringOrder() {
        assertTrue(COMPARATOR.compare("a01", "a1") < 0);
        assertTrue(COMPARATOR.compare("a1", "a01") > 0);
        assertTrue(COMPARATOR.compare("ABC", "abc") < 0);
        assertEquals(0, COMPARATOR.compare("Slot 5", "Slot 5"));
    }

    @Test
    public void prefixSortsFirst() {
        assertSorted("Slot", "Slot 1", "Slot 1 A");
    }

    private static void assertSorted(String... expected) {
        List<String> shuffled = new ArrayList<>(List.of(expected));
        Collections.reverse(shuffled);
        shuffled.sort(COMPARATOR);
        assertEquals(List.of(expected), shuffled);
    }
}
