package org.openpnp.machine.photon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.RotationSuggestion;
import org.openpnp.model.RotationSuggestion.Confidence;
import org.openpnp.spi.Machine;
import org.openpnp.util.Utils2D;

import com.google.common.io.Files;

public class PhotonFeederRotationTest {
    private static final int ADDRESS = 30;
    private static final double EPSILON = 1e-9;

    private PhotonFeeder feeder;
    private PhotonFeederSlots.Slot slot;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        Machine machine = Configuration.get().getMachine();
        feeder = new PhotonFeeder();
        machine.addFeeder(feeder);
        slot = new PhotonProperties(machine).getFeederSlots().getSlot(ADDRESS);
        slot.setLocation(new Location(LengthUnit.Millimeters, 100, 400, 4, 90));
        feeder.setSlotAddress(ADDRESS);
        feeder.setOffset(new Location(LengthUnit.Millimeters, 1, 2, -0.4, 0));
    }

    @Test
    public void applyingAPickRotationChangesOnlyTheOffsetRotation() throws Exception {
        feeder.applyPickRotation(0);

        assertEquals(-90, feeder.getOffset().getRotation(), EPSILON);
        assertEquals(1, feeder.getOffset().getX(), EPSILON);
        assertEquals(-0.4, feeder.getOffset().getZ(), EPSILON);
        assertEquals(90, slot.getLocation().getRotation(), EPSILON);
        assertEquals(0, feeder.getPickLocation().getRotation(), EPSILON);
    }

    @Test
    public void applyingASuggestionClearsIt() throws Exception {
        feeder.setRotationSuggestion(new RotationSuggestion(180, Confidence.High, "datasheet"));

        feeder.applyRotationSuggestion();

        assertNull(feeder.getRotationSuggestion());
        assertEquals(180, feeder.getPickLocation().getRotation(), EPSILON);
    }

    @Test
    public void rotatingKeepsTheLearnedPickOffsetCorrection() throws Exception {
        feeder.getPickOffsetCorrection().setEnabled(true);
        feeder.getPickOffsetCorrection()
                .addOffset(new Location(LengthUnit.Millimeters, 0.3, -0.2, 0, 0));
        Location before = feeder.getPickLocation();

        slot.setRotationKeepingPick(0);
        feeder.applyPickRotation(45);

        Location after = feeder.getPickLocation();
        assertEquals(before.getX(), after.getX(), EPSILON);
        assertEquals(before.getY(), after.getY(), EPSILON);
        assertEquals(0.3, feeder.getPickOffsetCorrection().getOffset().getX(), EPSILON);
        assertEquals(45, after.getRotation(), EPSILON);
    }

    @Test
    public void aFeederWithoutAnOffsetCannotApplyARotation() {
        feeder.setOffset(null);

        assertFalse(feeder.canApplyPickRotation());
        assertThrows(IllegalStateException.class, () -> feeder.applyPickRotation(0));
        assertNull(feeder.getOffset());
    }

    @Test
    public void aFeederWithoutASlotCannotApplyARotation() {
        feeder.setSlotAddress(null);

        assertThrows(IllegalStateException.class, () -> feeder.applyPickRotation(0));
    }

    @Test
    public void rotatingTheSlotKeepsThePick() throws Exception {
        Location before = feeder.getPickLocation();

        slot.setRotationKeepingPick(-90);

        Location after = feeder.getPickLocation();
        assertEquals(-90, slot.getLocation().getRotation(), EPSILON);
        assertEquals(before.getX(), after.getX(), EPSILON);
        assertEquals(before.getY(), after.getY(), EPSILON);
        assertEquals(before.getZ(), after.getZ(), EPSILON);
        assertEquals(0, Utils2D.normalizeAngle180(after.getRotation() - before.getRotation()),
                EPSILON);
        assertTrue(feeder.canApplyPickRotation());
    }
}
