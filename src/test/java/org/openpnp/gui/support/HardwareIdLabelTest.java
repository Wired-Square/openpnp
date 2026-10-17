package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseEvent;

import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.Test;
import org.openpnp.Translations;

public class HardwareIdLabelTest {
    private static final String ID = "00112233445566778899AABB";
    private static final String OTHER_ID = "FFEEDDCCBBAA998877665544";
    private static final int ID_COLUMN = 1;
    private static final int TABLE_WIDTH = 400;
    private static final int TABLE_HEIGHT = 200;

    private final Clipboard clipboard = new Clipboard("test"); //$NON-NLS-1$
    private final String copied = Translations.getString("HardwareIdLabel.Copied"); //$NON-NLS-1$

    @Test
    public void labelHidesTheIdBehindAnIconAndRevealsItOnHover() {
        HardwareIdLabel label = new HardwareIdLabel(() -> clipboard);
        label.setHardwareId(ID);

        assertEquals("", label.getText());
        assertNotNull(label.getIcon());
        assertTrue(label.getToolTipText().contains(ID));
    }

    @Test
    public void clickingTheLabelCopiesTheId() throws Exception {
        HardwareIdLabel label = new HardwareIdLabel(() -> clipboard);
        label.setHardwareId(ID);

        label.dispatchEvent(mouse(label, MouseEvent.MOUSE_CLICKED, new Point(1, 1)));

        assertEquals(ID, clipboard.getData(DataFlavor.stringFlavor));
        assertEquals(copied, label.getToolTipText());

        label.dispatchEvent(mouse(label, MouseEvent.MOUSE_EXITED, new Point(1, 1)));
        assertTrue(label.getToolTipText().contains(ID));
    }

    @Test
    public void rightClickDoesNotCopy() {
        HardwareIdLabel label = new HardwareIdLabel(() -> clipboard);
        label.setHardwareId(ID);

        label.dispatchEvent(mouse(label, MouseEvent.MOUSE_CLICKED, new Point(1, 1), MouseEvent.BUTTON3));

        assertNull(clipboard.getContents(null));
        assertTrue(label.getToolTipText().contains(ID));
    }

    @Test
    public void unavailableClipboardIsNotReportedAsCopied() {
        Clipboard busy = new Clipboard("busy") { //$NON-NLS-1$
            @Override
            public synchronized void setContents(Transferable contents, ClipboardOwner owner) {
                throw new IllegalStateException("cannot open system clipboard"); //$NON-NLS-1$
            }
        };
        HardwareIdLabel label = new HardwareIdLabel(() -> busy);
        label.setHardwareId(ID);

        label.dispatchEvent(mouse(label, MouseEvent.MOUSE_CLICKED, new Point(1, 1)));

        assertTrue(label.getToolTipText().contains(ID));
    }

    @Test
    public void labelWithoutAnIdShowsNothingAndCopiesNothing() {
        HardwareIdLabel label = new HardwareIdLabel(() -> clipboard);

        label.dispatchEvent(mouse(label, MouseEvent.MOUSE_CLICKED, new Point(1, 1)));

        assertNull(label.getIcon());
        assertNull(label.getToolTipText());
        assertNull(clipboard.getContents(null));
    }

    @Test
    public void tableColumnRevealsAndCopiesEachRowsId() throws Exception {
        JTable table = tableWithIds(ID, OTHER_ID, null);
        Point first = centreOf(table, 0);
        Point second = centreOf(table, 1);

        assertTrue(table.getToolTipText(mouse(table, MouseEvent.MOUSE_MOVED, first)).contains(ID));

        table.dispatchEvent(mouse(table, MouseEvent.MOUSE_CLICKED, second));
        assertEquals(OTHER_ID, clipboard.getData(DataFlavor.stringFlavor));
        assertEquals(copied, table.getToolTipText(mouse(table, MouseEvent.MOUSE_MOVED, second)));

        table.dispatchEvent(mouse(table, MouseEvent.MOUSE_MOVED, first));
        table.dispatchEvent(mouse(table, MouseEvent.MOUSE_MOVED, second));
        assertTrue(table.getToolTipText(mouse(table, MouseEvent.MOUSE_MOVED, second)).contains(OTHER_ID));
    }

    @Test
    public void copiedFeedbackStaysOnTheClickedRowWhenIdsRepeat() {
        JTable table = tableWithIds(ID, ID);
        Point first = centreOf(table, 0);
        Point second = centreOf(table, 1);

        table.dispatchEvent(mouse(table, MouseEvent.MOUSE_CLICKED, first));
        assertEquals(copied, table.getToolTipText(mouse(table, MouseEvent.MOUSE_MOVED, first)));

        table.dispatchEvent(mouse(table, MouseEvent.MOUSE_MOVED, second));
        assertTrue(table.getToolTipText(mouse(table, MouseEvent.MOUSE_MOVED, second)).contains(ID));
    }

    @Test
    public void tableRowWithoutAnIdCopiesNothing() {
        JTable table = tableWithIds((String) null);

        table.dispatchEvent(mouse(table, MouseEvent.MOUSE_CLICKED, centreOf(table, 0)));

        assertNull(clipboard.getContents(null));
    }

    private JTable tableWithIds(String... ids) {
        DefaultTableModel model = new DefaultTableModel(new Object[] {"Name", "ID"}, 0); //$NON-NLS-1$ //$NON-NLS-2$
        for (String id : ids) {
            model.addRow(new Object[] {"feeder", id}); //$NON-NLS-1$
        }
        JTable table = new JTable(model);
        table.setSize(TABLE_WIDTH, TABLE_HEIGHT);
        table.doLayout();
        HardwareIdLabel.installTableColumn(table, ID_COLUMN, () -> clipboard);
        return table;
    }

    private static Point centreOf(JTable table, int row) {
        Rectangle cell = table.getCellRect(row, table.convertColumnIndexToView(ID_COLUMN), false);
        return new Point((int) cell.getCenterX(), (int) cell.getCenterY());
    }

    private static MouseEvent mouse(Component source, int id, Point at) {
        return mouse(source, id, at, MouseEvent.BUTTON1);
    }

    private static MouseEvent mouse(Component source, int id, Point at, int button) {
        return new MouseEvent(source, id, System.currentTimeMillis(), 0, at.x, at.y, 1, false, button);
    }
}
