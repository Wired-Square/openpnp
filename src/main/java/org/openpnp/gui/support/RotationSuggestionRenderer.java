package org.openpnp.gui.support;

import java.awt.Component;

import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

import org.openpnp.model.RotationSuggestion;

/** Shows a feeder's pending rotation suggestion, with its rationale as the tooltip. */
public class RotationSuggestionRenderer extends DefaultTableCellRenderer {
    private static final long serialVersionUID = 1L;

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
            boolean hasFocus, int row, int column) {
        super.getTableCellRendererComponent(table, null, isSelected, hasFocus, row, column);
        setToolTipText(null);
        if (value instanceof RotationSuggestion) {
            RotationSuggestion suggestion = (RotationSuggestion) value;
            setText(String.format("%s° (%s)", //$NON-NLS-1$
                    new RotationCellValue(suggestion.getRotation()), suggestion.getConfidence()));
            setToolTipText(suggestion.getRationale());
            if (!isSelected) {
                setForeground(UIColors.statusWarning());
            }
        }
        return this;
    }
}
