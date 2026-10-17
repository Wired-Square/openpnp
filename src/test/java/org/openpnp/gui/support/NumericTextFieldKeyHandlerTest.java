package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;

import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

public class NumericTextFieldKeyHandlerTest {
    private static final double SPINNER_START = 1.0;
    private static final double SPINNER_MIN = 0.0;
    private static final double SPINNER_MAX = 1.5;
    private static final double SPINNER_STEP = 0.25;

    private final NumericTextFieldKeyHandler handler = new NumericTextFieldKeyHandler();

    @Test
    public void spinnerArrowsUseItsStepAndBounds() {
        JSpinner spinner = new JSpinner(
                new SpinnerNumberModel(SPINNER_START, SPINNER_MIN, SPINNER_MAX, SPINNER_STEP));
        JFormattedTextField field = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();

        press(field, KeyEvent.VK_UP);
        assertEquals(SPINNER_START + SPINNER_STEP, spinner.getValue());

        press(field, KeyEvent.VK_UP);
        press(field, KeyEvent.VK_UP);
        assertEquals(SPINNER_MAX, spinner.getValue());

        press(field, KeyEvent.VK_DOWN);
        assertEquals(SPINNER_MAX - SPINNER_STEP, spinner.getValue());
        assertEquals(SPINNER_MAX - SPINNER_STEP, field.getValue());
    }

    @Test
    public void plainTextFieldStepsByLeastSignificantDigit() {
        JTextField field = new JTextField("2.341");

        KeyEvent up = press(field, KeyEvent.VK_UP);

        assertTrue(up.isConsumed());
        assertEquals("2.342", field.getText());
    }

    /**
     * Delivers a key press the way the event queue does: global listeners first, then the
     * component's and its ancestors' key bindings unless a listener consumed it.
     */
    private KeyEvent press(JComponent source, int keyCode) {
        KeyEvent event = new KeyEvent(source, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                keyCode, KeyEvent.CHAR_UNDEFINED);
        handler.eventDispatched(event);
        if (!event.isConsumed()) {
            SwingUtilities.processKeyBindings(event);
        }
        return event;
    }
}
