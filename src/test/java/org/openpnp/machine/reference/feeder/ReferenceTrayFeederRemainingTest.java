package org.openpnp.machine.reference.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Feeder;

import com.google.common.io.Files;

public class ReferenceTrayFeederRemainingTest {
    private static final int COLUMNS = 2;
    private static final int ROWS = 3;
    private static final int FED = 4;

    private ReferenceTrayFeeder tray;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp")); //$NON-NLS-1$
        Configuration.get().load();
        tray = new ReferenceTrayFeeder();
        tray.setTrayCountX(COLUMNS);
        tray.setTrayCountY(ROWS);
    }

    @Test
    public void remainingIsCapacityLessFeedCount() {
        assertEquals(OptionalInt.of(COLUMNS * ROWS), tray.getRemainingParts());

        tray.setFeedCount(FED);

        assertEquals(OptionalInt.of(COLUMNS * ROWS - FED), tray.getRemainingParts());
    }

    @Test
    public void remainingIsNeverNegative() {
        tray.setFeedCount(COLUMNS * ROWS + 1);

        assertEquals(OptionalInt.of(0), tray.getRemainingParts());
    }

    @Test
    public void feedCountAndTraySizeChangesFireRemainingParts() {
        List<Object> values = new ArrayList<>();
        tray.addPropertyChangeListener("remainingParts", e -> values.add(e.getNewValue())); //$NON-NLS-1$

        tray.setFeedCount(1);
        tray.setTrayCountX(COLUMNS + 1);

        assertEquals(List.of(OptionalInt.of(COLUMNS * ROWS - 1),
                OptionalInt.of((COLUMNS + 1) * ROWS - 1)), values);
    }

    @Test
    public void feedersThatCantCountReportEmpty() {
        Feeder strip = new ReferenceStripFeeder();

        assertEquals(OptionalInt.empty(), strip.getRemainingParts());
    }
}
