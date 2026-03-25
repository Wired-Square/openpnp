package org.openpnp.gui.support;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;

import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Global key handler that adds precision-aware arrow key increment/decrement
 * to all numeric JTextFields. Installed once at startup via {@link #install()}.
 *
 * <p>The step size is derived from the field's current decimal precision:
 * <ul>
 *   <li>Up/Down arrows: step by the least significant digit
 *       (e.g. 0.001 for "2.341", 1 for "2")</li>
 *   <li>Shift+Up/Down: step by one order of magnitude larger
 *       (e.g. 0.01 for "2.341", 10 for "2")</li>
 *   <li>Ctrl+Up/Down: step by two orders of magnitude larger
 *       (e.g. 0.1 for "2.341", 100 for "2")</li>
 * </ul>
 *
 * Non-numeric text fields are ignored — the handler only acts when the
 * field content parses as a number.
 */
public class NumericTextFieldKeyHandler implements AWTEventListener {

    private static final int FINE_TIER = 0;
    private static final int MEDIUM_TIER = 1;
    private static final int COARSE_TIER = 2;

    public static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(
                new NumericTextFieldKeyHandler(), AWTEvent.KEY_EVENT_MASK);
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (!(event instanceof KeyEvent)) {
            return;
        }
        KeyEvent ke = (KeyEvent) event;
        if (ke.getID() != KeyEvent.KEY_PRESSED) {
            return;
        }
        if (!(ke.getComponent() instanceof JTextField)) {
            return;
        }
        if (!((JTextField) ke.getComponent()).isEditable()) {
            return;
        }

        boolean isShift = (ke.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0;
        boolean isCtrl = (ke.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0;

        int direction;
        int tier;
        switch (ke.getKeyCode()) {
            case KeyEvent.VK_UP:
                direction = 1;
                tier = isCtrl ? COARSE_TIER : isShift ? MEDIUM_TIER : FINE_TIER;
                break;
            case KeyEvent.VK_DOWN:
                direction = -1;
                tier = isCtrl ? COARSE_TIER : isShift ? MEDIUM_TIER : FINE_TIER;
                break;
            default:
                return;
        }

        JTextField textField = (JTextField) ke.getComponent();
        String text = textField.getText().trim();
        if (text.isEmpty() || !looksNumeric(text)) {
            return;
        }

        try {
            BigDecimal value = new BigDecimal(text);
            int scale = value.scale();
            // step = 10^(tier - scale): tier 0 = least significant digit,
            // tier 1 = one order larger, tier 2 = two orders larger
            BigDecimal step = BigDecimal.ONE.scaleByPowerOfTen(tier - scale);
            BigDecimal result = value.add(step.multiply(BigDecimal.valueOf(direction)));
            if (result.scale() < scale) {
                result = result.setScale(scale, RoundingMode.UNNECESSARY);
            }
            textField.setText(result.toPlainString());

            // If the text field is a table cell editor, commit the value
            // and re-enter editing so the change takes effect immediately
            // (e.g. pipeline reprocesses) without losing focus.
            JTable table = (JTable) SwingUtilities.getAncestorOfClass(JTable.class, textField);
            if (table != null && table.isEditing()) {
                int row = table.getEditingRow();
                int col = table.getEditingColumn();
                table.getCellEditor().stopCellEditing();
                SwingUtilities.invokeLater(() -> {
                    table.editCellAt(row, col);
                    table.getEditorComponent().requestFocusInWindow();
                });
            }

            ke.consume();
        }
        catch (NumberFormatException e) {
            // Not a numeric field — ignore
        }
    }

    private static boolean looksNumeric(String text) {
        boolean hasDigit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
            }
            else if (c != '-' && c != '+' && c != '.' && c != 'e' && c != 'E') {
                return false;
            }
        }
        return hasDigit;
    }
}
