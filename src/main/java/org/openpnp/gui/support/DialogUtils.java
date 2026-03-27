package org.openpnp.gui.support;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.Window;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * Utility for standardising dialog sizing and padding.
 */
public class DialogUtils {

    private static final int PADDING = 10;
    private static final double MIN_PARENT_WIDTH_RATIO = 0.35;
    private static final double MIN_PARENT_HEIGHT_RATIO = 0.5;
    private static final double MAX_PARENT_RATIO = 0.9;
    private static final int FALLBACK_MIN_WIDTH = 450;
    private static final int FALLBACK_MIN_HEIGHT = 500;

    /**
     * Sizes a dialog based on its content, clamped between sensible
     * minimum and maximum bounds relative to the parent window,
     * then centred on the parent.
     *
     * Call this after all components have been added to the dialog.
     */
    public static void sizeAndCenter(JDialog dialog) {
        dialog.pack();

        Window parent = dialog.getOwner();
        int minWidth = FALLBACK_MIN_WIDTH;
        int minHeight = FALLBACK_MIN_HEIGHT;
        int maxWidth = Integer.MAX_VALUE;
        int maxHeight = Integer.MAX_VALUE;

        if (parent != null) {
            minWidth = Math.max(minWidth, (int) (parent.getWidth() * MIN_PARENT_WIDTH_RATIO));
            minHeight = Math.max(minHeight, (int) (parent.getHeight() * MIN_PARENT_HEIGHT_RATIO));
            maxWidth = (int) (parent.getWidth() * MAX_PARENT_RATIO);
            maxHeight = (int) (parent.getHeight() * MAX_PARENT_RATIO);
        }

        Dimension packed = dialog.getSize();
        int width = Math.max(packed.width, minWidth);
        int height = Math.max(packed.height, minHeight);
        width = Math.min(width, maxWidth);
        height = Math.min(height, maxHeight);

        dialog.setSize(width, height);
        dialog.setLocationRelativeTo(parent);
    }

    /**
     * Ensures the dialog's content pane has consistent padding.
     * Safe to call on content panes that already have a border.
     */
    public static void addPadding(JDialog dialog) {
        Container contentPane = dialog.getContentPane();
        if (contentPane instanceof JPanel) {
            ((JPanel) contentPane).setBorder(new EmptyBorder(PADDING, PADDING, PADDING, PADDING));
        }
    }
}
