package org.openpnp.gui.support;

import java.awt.BorderLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.beans.PropertyChangeListener;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.openpnp.Translations;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.spi.Feeder;

/**
 * Shows and edits the selected feeder's note above its configuration. Edits are saved when the
 * text area loses focus.
 */
public class FeederNotePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int ROWS = 3;

    private final JTextArea noteTa = new JTextArea(ROWS, 0);
    private final transient PropertyChangeListener noteListener =
            event -> SwingUtilities.invokeLater(this::showNote);
    private transient Feeder feeder;

    public FeederNotePanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder(
                Translations.getString("FeederNotePanel.Title"))); //$NON-NLS-1$
        noteTa.setLineWrap(true);
        noteTa.setWrapStyleWord(true);
        noteTa.setToolTipText(Translations.getString("FeederNotePanel.ToolTip")); //$NON-NLS-1$
        noteTa.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                saveNote();
            }
        });
        add(new JScrollPane(noteTa), BorderLayout.CENTER);
        setFeeder(null);
    }

    /**
     * @param feeder the feeder whose note to show, or null for none.
     */
    public void setFeeder(Feeder feeder) {
        saveNote();
        if (this.feeder instanceof AbstractModelObject) {
            ((AbstractModelObject) this.feeder).removePropertyChangeListener("note", noteListener); //$NON-NLS-1$
        }
        this.feeder = feeder;
        if (feeder instanceof AbstractModelObject) {
            ((AbstractModelObject) feeder).addPropertyChangeListener("note", noteListener); //$NON-NLS-1$
        }
        noteTa.setEnabled(feeder != null);
        showNote();
    }

    private void showNote() {
        String note = feeder == null ? null : feeder.getNote();
        noteTa.setText(note == null ? "" : note); //$NON-NLS-1$
        noteTa.setCaretPosition(0);
    }

    private void saveNote() {
        if (feeder == null) {
            return;
        }
        String current = feeder.getNote() == null ? "" : feeder.getNote(); //$NON-NLS-1$
        if (Objects.equals(current, noteTa.getText())) {
            return;
        }
        try {
            feeder.setNote(noteTa.getText());
        }
        catch (IllegalArgumentException e) {
            MessageBoxes.errorBox(this, feeder.getName(), e);
            showNote();
        }
    }
}
