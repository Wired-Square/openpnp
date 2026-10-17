package org.openpnp.gui.support;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;
import java.util.function.Supplier;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;

import org.openpnp.Translations;
import org.pmw.tinylog.Logger;

/**
 * Shows a hardware ID, such as a feeder serial number, as an icon: hovering reveals the ID and
 * clicking copies it to the clipboard. {@link #installTableColumn} gives a table column the same
 * behaviour.
 */
public final class HardwareIdLabel extends JLabel {
    private static final long serialVersionUID = 1L;

    private final transient Supplier<Clipboard> clipboard;
    private String hardwareId;

    public HardwareIdLabel() {
        this(HardwareIdLabel::systemClipboard);
    }

    HardwareIdLabel(Supplier<Clipboard> clipboard) {
        this.clipboard = clipboard;
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (hardwareId != null && SwingUtilities.isLeftMouseButton(e)
                        && copy(HardwareIdLabel.this.clipboard, hardwareId)) {
                    setToolTipText(copiedText());
                    ToolTipManager.sharedInstance().mouseMoved(e);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setToolTipText(revealText(hardwareId));
            }
        });
        setHardwareId(null);
    }

    public String getHardwareId() {
        return hardwareId;
    }

    public void setHardwareId(String hardwareId) {
        this.hardwareId = hardwareId;
        setIcon(hardwareId == null ? null : Icons.hardwareId);
        setToolTipText(revealText(hardwareId));
        setCursor(hardwareId == null ? null : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName(hardwareId);
    }

    /**
     * Renders the column's String values as the icon, reveals each on hover and copies it on
     * click. Null values show nothing.
     */
    public static void installTableColumn(JTable table, int modelColumn) {
        installTableColumn(table, modelColumn, HardwareIdLabel::systemClipboard);
    }

    static void installTableColumn(JTable table, int modelColumn, Supplier<Clipboard> clipboard) {
        TableColumnFeedback feedback = new TableColumnFeedback(table, modelColumn, clipboard);
        TableColumn column = table.getColumnModel().getColumn(table.convertColumnIndexToView(modelColumn));
        column.setCellRenderer(feedback.renderer);
        table.addMouseListener(feedback);
        table.addMouseMotionListener(feedback);
    }

    private static final class TableColumnFeedback extends MouseAdapter {
        private final JTable table;
        private final int modelColumn;
        private final Supplier<Clipboard> clipboard;
        // The ID copied by the last click, until the pointer leaves that cell.
        private String copiedId;

        private final DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, null, isSelected, hasFocus, row, column);
                String id = (String) value;
                setHorizontalAlignment(SwingConstants.CENTER);
                setIcon(id == null ? null : Icons.hardwareId);
                setToolTipText(id != null && id.equals(copiedId) ? copiedText() : revealText(id));
                return this;
            }
        };

        private TableColumnFeedback(JTable table, int modelColumn, Supplier<Clipboard> clipboard) {
            this.table = table;
            this.modelColumn = modelColumn;
            this.clipboard = clipboard;
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            String id = idAt(e);
            if (id != null && SwingUtilities.isLeftMouseButton(e) && copy(clipboard, id)) {
                copiedId = id;
                ToolTipManager.sharedInstance().mouseMoved(e);
            }
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            if (copiedId != null && !Objects.equals(copiedId, idAt(e))) {
                copiedId = null;
            }
        }

        @Override
        public void mouseExited(MouseEvent e) {
            copiedId = null;
        }

        private String idAt(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            int column = table.columnAtPoint(e.getPoint());
            if (row < 0 || column < 0 || table.convertColumnIndexToModel(column) != modelColumn) {
                return null;
            }
            return (String) table.getValueAt(row, column);
        }
    }

    private static String revealText(String id) {
        return id == null ? null
                : String.format(Translations.getString("HardwareIdLabel.ToolTip"), id); //$NON-NLS-1$
    }

    private static String copiedText() {
        return Translations.getString("HardwareIdLabel.Copied"); //$NON-NLS-1$
    }

    /** @return false if the clipboard was unavailable, for example held by another application */
    private static boolean copy(Supplier<Clipboard> clipboard, String id) {
        StringSelection selection = new StringSelection(id);
        try {
            clipboard.get().setContents(selection, selection);
            return true;
        }
        catch (IllegalStateException e) {
            Logger.warn(e, "Cannot copy the hardware ID to the clipboard"); //$NON-NLS-1$
            return false;
        }
    }

    private static Clipboard systemClipboard() {
        return Toolkit.getDefaultToolkit().getSystemClipboard();
    }
}
