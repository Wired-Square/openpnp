package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.model.Configuration;

import com.google.common.io.Files;

public class FeederNotePanelTest {
    private ReferenceTrayFeeder first;
    private ReferenceTrayFeeder second;
    private FeederNotePanel panel;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        first = new ReferenceTrayFeeder();
        second = new ReferenceTrayFeeder();
        first.setNote("First note");
        SwingUtilities.invokeAndWait(() -> panel = new FeederNotePanel());
    }

    private JTextArea textArea() {
        return (JTextArea) ((JScrollPane) panel.getComponent(0)).getViewport().getView();
    }

    @Test
    public void showsTheSelectedFeedersNoteAndSavesEditsWhenTheSelectionChanges()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.setFeeder(first);
            assertEquals("First note", textArea().getText());

            textArea().setText("Edited");
            panel.setFeeder(second);

            assertEquals("Edited", first.getNote());
            assertEquals("", textArea().getText());
        });
    }

    @Test
    public void followsChangesMadeElsewhere() throws Exception {
        SwingUtilities.invokeAndWait(() -> panel.setFeeder(first));

        first.setNote("Set through MCP");

        // Queued after the listener's own invokeLater, so it sees the refreshed text.
        SwingUtilities.invokeAndWait(
                () -> assertEquals("Set through MCP", textArea().getText()));
    }

    @Test
    public void isDisabledWithoutAFeeder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.setFeeder(null);
            assertFalse(textArea().isEnabled());
            assertNull(second.getNote());
        });
    }
}
