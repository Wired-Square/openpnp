package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.Location;

import com.google.common.io.Files;

public class LocationButtonsPanelTest {
    private final JTextField x = new JTextField("1");
    private final JTextField y = new JTextField("2");
    private final JTextField z = new JTextField("3");
    private final JTextField c = new JTextField("-90");
    private LocationButtonsPanel panel;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        SwingUtilities.invokeAndWait(() -> panel = new LocationButtonsPanel(x, y, z, c));
    }

    @Test
    public void aCameraCaptureKeepsTheRotation() throws Exception {
        Location camera = new Location(Configuration.get().getSystemUnits(), 10, 20, 30, 0);

        SwingUtilities.invokeAndWait(() -> panel.copyCameraCaptureIntoTextFields(camera, false));

        assertEquals(10, Double.parseDouble(x.getText()), 1e-9);
        assertEquals(20, Double.parseDouble(y.getText()), 1e-9);
        assertEquals("3", z.getText());
        assertEquals("-90", c.getText());
    }

    @Test
    public void aProbedCameraCaptureSetsZ() throws Exception {
        Location camera = new Location(Configuration.get().getSystemUnits(), 10, 20, 30, 0);

        SwingUtilities.invokeAndWait(() -> panel.copyCameraCaptureIntoTextFields(camera, true));

        assertEquals(30, Double.parseDouble(z.getText()), 1e-9);
        assertEquals("-90", c.getText());
    }
}
