package org.openpnp.gui;

import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.text.ParseException;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;

import org.openpnp.Translations;
import org.openpnp.gui.support.UIColors;
import org.openpnp.mcp.McpService;
import org.openpnp.mcp.McpSettings;
import org.pmw.tinylog.Logger;

import com.formdev.flatlaf.util.UIScale;
import com.jgoodies.forms.builder.ButtonBarBuilder;
import com.jgoodies.forms.factories.Borders;
import com.jgoodies.forms.factories.DefaultComponentFactory;
import com.jgoodies.forms.layout.FormLayout;

public class McpServerDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private static final int DESCRIPTION_WIDTH = 460;
    private static final int COMMAND_COLUMNS = 40;
    private static final String TOKEN_MASK = "••••••••"; //$NON-NLS-1$

    private final transient McpService service;
    private final JCheckBox enabledChk;
    private final JSpinner portSpinner;
    private final JCheckBox allowWriteChk;
    private final JCheckBox applyDirectlyChk;
    private final JCheckBox allowMachineChk;
    private final JLabel statusLbl;
    private final JTextField commandTf;

    public McpServerDialog(Frame owner, McpService service) {
        super(owner, Translations.getString("McpServerDialog.title"), true); //$NON-NLS-1$
        this.service = service;
        McpSettings settings = service.getSettings();
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        DefaultComponentFactory factory = DefaultComponentFactory.getInstance();
        JPanel form = new JPanel(new FormLayout(
                "right:pref, $lcgap, pref:grow, $rgap, pref", //$NON-NLS-1$
                "pref, $pgap, pref, $lgap, pref, $lgap, pref, $lgap, pref, $pgap, " //$NON-NLS-1$
                        + "pref, $lgap, pref, $lgap, pref, $lgap, pref, $lgap, pref, $pgap, pref, " //$NON-NLS-1$
                        + "$lgap, pref, " //$NON-NLS-1$
                        + "$ugap, " //$NON-NLS-1$
                        + "pref")); //$NON-NLS-1$
        form.setBorder(Borders.DIALOG);

        // An HTML width keeps the text wrapped to a fixed, DPI-scaled width so pack() sizes it.
        JLabel descriptionLbl = new JLabel(String.format("<html><div style='width:%dpx'>%s</div>", //$NON-NLS-1$
                UIScale.scale(DESCRIPTION_WIDTH),
                Translations.getString("McpServerDialog.Description"))); //$NON-NLS-1$
        form.add(descriptionLbl, "1, 1, 5, 1"); //$NON-NLS-1$

        form.add(factory.createSeparator(
                Translations.getString("McpServerDialog.Section.Server")), "1, 3, 5, 1"); //$NON-NLS-1$ //$NON-NLS-2$
        enabledChk = new JCheckBox(Translations.getString("McpServerDialog.Enabled")); //$NON-NLS-1$
        enabledChk.setSelected(settings.isEnabled());
        form.add(enabledChk, "3, 5, 3, 1"); //$NON-NLS-1$
        form.add(new JLabel(Translations.getString("McpServerDialog.Port")), "1, 7"); //$NON-NLS-1$ //$NON-NLS-2$
        portSpinner = new JSpinner(new SpinnerNumberModel(settings.getPort(),
                McpSettings.MIN_PORT, McpSettings.MAX_PORT, 1));
        portSpinner.setEditor(new JSpinner.NumberEditor(portSpinner, "#")); //$NON-NLS-1$
        form.add(portSpinner, "3, 7, left, default"); //$NON-NLS-1$
        form.add(new JLabel(Translations.getString("McpServerDialog.Status")), "1, 9"); //$NON-NLS-1$ //$NON-NLS-2$
        statusLbl = new JLabel();
        form.add(statusLbl, "3, 9, 3, 1"); //$NON-NLS-1$

        form.add(factory.createSeparator(
                Translations.getString("McpServerDialog.Section.Access")), "1, 11, 5, 1"); //$NON-NLS-1$ //$NON-NLS-2$
        allowWriteChk = new JCheckBox(
                Translations.getString("McpServerDialog.AllowWrite")); //$NON-NLS-1$
        allowWriteChk.setSelected(settings.isAllowWrite());
        form.add(allowWriteChk, "3, 13, 3, 1"); //$NON-NLS-1$
        applyDirectlyChk = new JCheckBox(
                Translations.getString("McpServerDialog.ApplySuggestionsDirectly")); //$NON-NLS-1$
        applyDirectlyChk.setSelected(settings.isApplySuggestionsDirectly());
        form.add(applyDirectlyChk, "3, 15, 3, 1"); //$NON-NLS-1$
        // Applying a suggestion changes configuration, so it needs write access too.
        applyDirectlyChk.setEnabled(allowWriteChk.isSelected());
        allowWriteChk.addItemListener(e -> applyDirectlyChk.setEnabled(allowWriteChk.isSelected()));
        allowMachineChk = new JCheckBox(
                Translations.getString("McpServerDialog.AllowMachine")); //$NON-NLS-1$
        allowMachineChk.setSelected(settings.isAllowMachine());
        form.add(allowMachineChk, "3, 17, 3, 1"); //$NON-NLS-1$
        form.add(new JLabel(Translations.getString("McpServerDialog.Token")), "1, 19"); //$NON-NLS-1$ //$NON-NLS-2$
        JLabel tokenLbl = new JLabel(TOKEN_MASK);
        form.add(tokenLbl, "3, 19"); //$NON-NLS-1$
        JButton regenerateButton = new JButton(
                Translations.getString("McpServerDialog.RegenerateToken")); //$NON-NLS-1$
        regenerateButton.addActionListener(e -> {
            if (commitPort()) {
                settings.regenerateToken();
                apply();
            }
        });
        form.add(regenerateButton, "5, 19"); //$NON-NLS-1$

        form.add(factory.createSeparator(
                Translations.getString("McpServerDialog.Section.ClaudeCode")), "1, 21, 5, 1"); //$NON-NLS-1$ //$NON-NLS-2$
        form.add(new JLabel(Translations.getString("McpServerDialog.Command")), "1, 23"); //$NON-NLS-1$ //$NON-NLS-2$
        commandTf = new JTextField(COMMAND_COLUMNS);
        commandTf.setEditable(false);
        commandTf.setFont(UIManager.getFont("monospaced.font")); //$NON-NLS-1$
        form.add(commandTf, "3, 23"); //$NON-NLS-1$
        JButton copyButton = new JButton(
                Translations.getString("McpServerDialog.CopyCommand")); //$NON-NLS-1$
        copyButton.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(service.getClaudeCodeCommand()), null));
        form.add(copyButton, "5, 23"); //$NON-NLS-1$

        JButton applyButton = new JButton(Translations.getString("McpServerDialog.Apply")); //$NON-NLS-1$
        applyButton.addActionListener(e -> apply());
        JButton closeButton = new JButton(Translations.getString("McpServerDialog.Close")); //$NON-NLS-1$
        closeButton.addActionListener(e -> dispose());
        form.add(new ButtonBarBuilder().addGlue().addButton(applyButton, closeButton).build(),
                "1, 25, 5, 1"); //$NON-NLS-1$
        setContentPane(form);
        getRootPane().setDefaultButton(applyButton);

        refresh(null);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Reports an invalid port, so nothing is applied, rather than silently keeping the old one. */
    private boolean commitPort() {
        try {
            portSpinner.commitEdit();
            return true;
        }
        catch (ParseException e) {
            refresh(String.format(Translations.getString("McpServerDialog.InvalidPort"), //$NON-NLS-1$
                    McpSettings.MIN_PORT, McpSettings.MAX_PORT));
            return false;
        }
    }

    private void apply() {
        if (!commitPort()) {
            return;
        }
        McpSettings settings = service.getSettings();
        settings.setEnabled(enabledChk.isSelected());
        settings.setPort((Integer) portSpinner.getValue());
        settings.setAllowWrite(allowWriteChk.isSelected());
        settings.setApplySuggestionsDirectly(applyDirectlyChk.isSelected());
        settings.setAllowMachine(allowMachineChk.isSelected());
        try {
            service.applySettings();
            refresh(null);
        }
        catch (IOException e) {
            Logger.warn(e, "Could not start the MCP server"); //$NON-NLS-1$
            refresh(e.getMessage());
        }
    }

    private void refresh(String failure) {
        if (failure != null) {
            statusLbl.setText(String.format(
                    Translations.getString("McpServerDialog.Status.Failed"), failure)); //$NON-NLS-1$
            statusLbl.setForeground(UIColors.statusError());
        }
        else if (service.isRunning()) {
            statusLbl.setText(String.format(
                    Translations.getString("McpServerDialog.Status.Running"), //$NON-NLS-1$
                    service.getUrl()));
            statusLbl.setForeground(UIColors.statusReady());
        }
        else {
            statusLbl.setText(Translations.getString("McpServerDialog.Status.Stopped")); //$NON-NLS-1$
            statusLbl.setForeground(UIColors.statusDisabled());
        }
        // The copied command carries the real token; the screen never shows it.
        commandTf.setText(service.getClaudeCodeCommand()
                .replace(service.getSettings().getToken(), TOKEN_MASK));
        commandTf.setCaretPosition(0);
    }
}
