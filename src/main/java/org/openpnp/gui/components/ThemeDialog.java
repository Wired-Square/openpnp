package org.openpnp.gui.components;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Window;
import java.awt.event.WindowEvent;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;

import org.openpnp.Translations;

import org.openpnp.model.Configuration;

public class ThemeDialog {
    private static final ThemeDialog instance = new ThemeDialog();
    private static final ThemeSettingsPanel themePanel = new ThemeSettingsPanel();

    private JDialog dialog;
    private ThemeInfo oldTheme = null;
    private ThemeSettingsPanel.FontSize oldFontSize = null;
    private Boolean oldAlternateRows = true;

    protected ThemeDialog() {
    }

    public static ThemeDialog getInstance() {
        return instance;
    }

    public ThemeInfo getOldTheme() {
        return oldTheme;
    }

    public void setOldTheme(ThemeInfo oldTheme) {
        this.oldTheme = oldTheme;
    }

    public ThemeSettingsPanel.FontSize getOldFontSize() {
        return oldFontSize;
    }

    public void setOldFontSize(ThemeSettingsPanel.FontSize oldFontSize) {
        this.oldFontSize = oldFontSize;
    }

    public static void showThemeDialog(final Component parent) {
        showThemeDialog(parent, Dialog.ModalityType.MODELESS);
    }

    public static void showThemeDialog(final Component parent, final Dialog.ModalityType modalityType) {
        instance.showDialog(parent, modalityType);
    }

    protected JDialog createDialog(final Window parent) {
        JDialog dialog = new JDialog(parent);
        //dialog.setIconImage(IconLoader.createFrameIcon(getIcon(), dialog));
        dialog.setTitle(Translations.getString("Theme.Title")); //$NON-NLS-1$

        JPanel contentPane = new JPanel(new BorderLayout());
        themePanel.updateThemesList(null);
        contentPane.add(themePanel, BorderLayout.CENTER);
        contentPane.add(createButtonPanel(), BorderLayout.SOUTH);
        dialog.setContentPane(contentPane);
        contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setMinimumSize(new Dimension(400, 500));
        dialog.pack();
        dialog.setLocationByPlatform(true);
        dialog.setLocationRelativeTo(parent);
        return dialog;
    }

    public void showDialog(final Component parent, final Dialog.ModalityType modal) {
        if (dialog != null && dialog.isVisible()) {
            dialog.requestFocus();
            return;
        }
        themePanel.updateThemesList(oldTheme);
        Window window = getWindow(parent);
        dialog = createDialog(window);
        dialog.setModalityType(modal);
        dialog.setVisible(true);
    }

    protected Component createButtonPanel() {
        JButton ok = new JButton(Translations.getString("Theme.Save")); //$NON-NLS-1$
        ok.setDefaultCapable(true);
        ok.addActionListener(e -> {
            ThemeInfo theme = themePanel.setTheme();
            oldTheme = theme;
            oldFontSize = themePanel.getFontSize();
            oldAlternateRows  = themePanel.isAlternateRows();
            Configuration.get().setThemeInfo(theme);
            Configuration.get().setFontSize(oldFontSize);
            Configuration.get().setAlternateRows(oldAlternateRows);
            dialog.pack();
            dialog.doLayout();
            dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
        });

        JButton cancel = new JButton(Translations.getString("Theme.Cancel")); //$NON-NLS-1$
        cancel.addActionListener(e -> {
            if (oldTheme != null) {
                themePanel.setTheme(oldTheme, oldFontSize, oldAlternateRows);
            }
            dialog.pack();
            dialog.doLayout();
            dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
        });
        JButton apply = new JButton(Translations.getString("Theme.Apply")); //$NON-NLS-1$
        apply.addActionListener(e -> {
            themePanel.setTheme();
            // setTheme() defers the actual theme change via invokeLater,
            // so pack must also be deferred to run after the theme is applied
            EventQueue.invokeLater(() -> {
                dialog.pack();
                dialog.doLayout();
            });
        });

        Box box = Box.createHorizontalBox();
        box.add(Box.createHorizontalGlue());
        box.add(ok);
        box.add(cancel);
        box.add(apply);
        return box;
    }

    public static Window getWindow(final Component component) {
        if (component == null) {
            return null;
        }
        return component instanceof Window ? (Window) component : SwingUtilities.getWindowAncestor(component);
    }
}
