package org.openpnp.machine.reference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Machine;

import com.google.common.io.Files;

public class ReferenceNozzleUnloadTest {
    /**
     * Records which nozzle the tip appears loaded on when the changer end location is resolved
     * (when slot vision calibration decides whether the slot should be occupied), and during the
     * changer moves (when runout compensation should use the unloaded stand-in).
     */
    static class RecordingNozzleTip extends ReferenceNozzleTip {
        ReferenceNozzle loadedOnAtEndLocationLookup;
        ReferenceNozzle loadedOnDuringChangerMoves;

        @Override
        public Location getChangerEndLocationCalibrated(boolean nozzleTipChange) throws Exception {
            loadedOnAtEndLocationLookup = getNozzleWhereLoaded();
            return new Location(LengthUnit.Millimeters);
        }

        @Override
        public String getChangerActuatorPostStepThree() {
            loadedOnDuringChangerMoves = getNozzleWhereLoaded();
            return super.getChangerActuatorPostStepThree();
        }
    }

    private Machine machine;
    private ReferenceNozzle nozzle;
    private RecordingNozzleTip nozzleTip;

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = new File(Files.createTempDir(), ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();

        machine = Configuration.get().getMachine();
        nozzle = (ReferenceNozzle) machine.getDefaultHead().getDefaultNozzle();

        nozzleTip = new RecordingNozzleTip();
        nozzleTip.setName("Recording");
        machine.addNozzleTip(nozzleTip);
        nozzle.addCompatibleNozzleTip(nozzleTip);

        machine.setEnabled(true);
        machine.home();
        nozzle.setNozzleTip(nozzleTip);
        nozzle.setChangerEnabled(true);
    }

    @Test
    public void slotVisionStillSeesTheTipLoadedDuringUnload() throws Exception {
        nozzle.unloadNozzleTip();

        assertSame(nozzle, nozzleTip.loadedOnAtEndLocationLookup);
    }

    @Test
    public void changerMovesUseTheUnloadedCalibration() throws Exception {
        nozzle.unloadNozzleTip();

        assertNull(nozzleTip.loadedOnDuringChangerMoves);
    }

    @Test
    public void unloadLeavesNoCalibrationOverride() throws Exception {
        nozzle.unloadNozzleTip();
        assertNull(nozzle.getNozzleTip());

        // A leftover override would keep reporting the stand-in instead of the mounted tip.
        nozzle.setNozzleTip(nozzleTip);

        assertSame(nozzleTip, nozzle.getCalibrationNozzleTip());
    }
}
