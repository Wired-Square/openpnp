package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.openpnp.Translations;
import org.openpnp.mcp.MotionConfirmer;

import com.formdev.flatlaf.util.UIScale;
import com.jgoodies.forms.builder.ButtonBarBuilder;
import com.jgoodies.forms.factories.Borders;

/**
 * Asks in a modal dialog that stays open until the operator chooses; Deny is the default button.
 */
public class McpMotionConfirmer implements MotionConfirmer {
    private static final int MESSAGE_WIDTH = 360;
    private static final int GAP = 12;

    private final Component parent;

    public McpMotionConfirmer(Component parent) {
        this.parent = parent;
    }

    @Override
    public boolean confirm(String description) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Motion must not be confirmed from the EDT");
        }
        AtomicBoolean allowed = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> allowed.set(ask(description)));
        return allowed.get();
    }

    private boolean ask(String description) {
        String message = String.format(
                "<html><body style='width:%dpx'><p>%s</p><p><b>%s</b></p><p>%s</p></body></html>", //$NON-NLS-1$
                UIScale.scale(MESSAGE_WIDTH),
                Translations.getString("McpMotionConfirmer.Intro"), //$NON-NLS-1$
                escapeHtml(description),
                Translations.getString("McpMotionConfirmer.Check")); //$NON-NLS-1$
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
                Translations.getString("McpMotionConfirmer.Title"), //$NON-NLS-1$
                Dialog.ModalityType.APPLICATION_MODAL);
        // Only Allow or Deny ends the question: closing the window or Escape does nothing.
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        AtomicBoolean allowed = new AtomicBoolean();
        JButton allow = new JButton(Translations.getString("McpMotionConfirmer.Allow")); //$NON-NLS-1$
        allow.addActionListener(e -> {
            allowed.set(true);
            dialog.dispose();
        });
        JButton deny = new JButton(Translations.getString("McpMotionConfirmer.Deny")); //$NON-NLS-1$
        deny.addActionListener(e -> dialog.dispose());

        JPanel content = new JPanel(new BorderLayout(0, UIScale.scale(GAP)));
        content.setBorder(Borders.DIALOG);
        content.add(new JLabel(message, UIManager.getIcon("OptionPane.warningIcon"), //$NON-NLS-1$
                SwingConstants.LEADING), BorderLayout.CENTER);
        content.add(new ButtonBarBuilder().addGlue().addButton(allow, deny).build(),
                BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(deny);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        deny.requestFocusInWindow();
        dialog.setVisible(true);
        return allowed.get();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }
}
