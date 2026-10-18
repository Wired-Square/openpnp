package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;

import com.google.common.io.Files;

public class PartsComboBoxModelTest {
    // MainFrame builds parts combo boxes before it loads the configuration on the EDT.
    @Test
    public void followsPartsAddedWhileTheConfigurationLoads() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp")); //$NON-NLS-1$
        Configuration configuration = Configuration.get();
        PartsComboBoxModel model = new PartsComboBoxModel();
        AtomicReference<Exception> failure = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            try {
                configuration.load();
                configuration.addPart(new Part("ADDED-AFTER-LOAD")); //$NON-NLS-1$
            }
            catch (Exception e) {
                failure.set(e);
            }
        });

        assertEquals(null, failure.get());
        assertEquals(configuration.getParts().size(), model.getSize());
        boolean found = false;
        for (int i = 0; i < model.getSize(); i++) {
            found |= ((Part) model.getElementAt(i)).getId().equals("ADDED-AFTER-LOAD"); //$NON-NLS-1$
        }
        assertTrue(found);
    }
}
