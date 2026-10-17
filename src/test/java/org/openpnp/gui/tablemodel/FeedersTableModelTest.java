package org.openpnp.gui.tablemodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTubeFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

import com.google.common.io.Files;

public class FeedersTableModelTest {
    private static final String HARDWARE_ID = "00112233445566778899AABB";

    private Machine machine;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        machine = Configuration.get().getMachine();
    }

    @Test
    public void hardwareIdColumnShowsIdsOnlyForFeedersThatHaveThem() throws Exception {
        PhotonFeeder photon = new PhotonFeeder();
        photon.setHardwareId(HARDWARE_ID);
        Feeder tube = new ReferenceTubeFeeder();
        machine.addFeeder(photon);
        machine.addFeeder(tube);

        FeedersTableModel model = new FeedersTableModel(Configuration.get());
        model.refresh();

        assertEquals(HARDWARE_ID, model.getValueAt(model.indexOf(photon), FeedersTableModel.HARDWARE_ID_COLUMN));
        assertNull(model.getValueAt(model.indexOf(tube), FeedersTableModel.HARDWARE_ID_COLUMN));
    }
}
