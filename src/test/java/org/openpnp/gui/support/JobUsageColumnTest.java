package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;

import org.junit.jupiter.api.Test;
import org.openpnp.Translations;
import org.openpnp.model.JobUsageService.Issue;
import org.openpnp.model.JobUsageService.Usage;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.UIScale;

public class JobUsageColumnTest {
    private static final int NAME_COLUMN = 0;
    private static final int USAGE_COLUMN = 1;
    private static final int TABLE_WIDTH = 400;
    private static final int TABLE_HEIGHT = 200;
    private static final float ZOOM_STEP = 1.5f;

    private static final Usage IN_USE = Usage.inUse(Set.of());
    private static final Usage ISSUE =
            Usage.inUse(EnumSet.of(Issue.PART_HEIGHT_UNKNOWN, Issue.FEEDER_DISABLED));

    private static final class UsageTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;

        private final transient List<Usage> usages;

        UsageTableModel(Usage... usages) {
            this.usages = new ArrayList<>(Arrays.asList(usages));
        }

        @Override
        public int getRowCount() {
            return usages.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return column == NAME_COLUMN ? "row " + row : usages.get(row); //$NON-NLS-1$
        }
    }

    private static JTable installedTable(UsageTableModel model) {
        JTable table = new JTable(model);
        TableRowSorter<UsageTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        JobUsageColumn.install(table, sorter, USAGE_COLUMN);
        return table;
    }

    @Test
    public void eachStateHasItsOwnIconAndToolTip() {
        assertSame(JobUsageColumn.IN_USE, JobUsageColumn.icon(IN_USE));
        assertSame(JobUsageColumn.ISSUE, JobUsageColumn.icon(ISSUE));
        assertSame(JobUsageColumn.NOT_IN_USE, JobUsageColumn.icon(Usage.NOT_IN_USE));
        for (Icon icon : new Icon[] {JobUsageColumn.IN_USE, JobUsageColumn.ISSUE,
                JobUsageColumn.NOT_IN_USE}) {
            assertTrue(((FlatSVGIcon) icon).hasFound());
        }

        assertEquals(Translations.getString("JobUsageColumn.InUse.toolTip"), //$NON-NLS-1$
                JobUsageColumn.toolTip(IN_USE));
        assertEquals(Translations.getString("JobUsageColumn.NotInUse.toolTip"), //$NON-NLS-1$
                JobUsageColumn.toolTip(Usage.NOT_IN_USE));
    }

    @Test
    public void theIssueToolTipNamesEveryIssue() {
        String toolTip = JobUsageColumn.toolTip(ISSUE);

        assertTrue(toolTip.contains(
                Translations.getString("JobUsageColumn.Issue.PartHeightUnknown"))); //$NON-NLS-1$
        assertTrue(toolTip.contains(
                Translations.getString("JobUsageColumn.Issue.FeederDisabled"))); //$NON-NLS-1$
        assertFalse(toolTip.contains("!")); //$NON-NLS-1$
    }

    @Test
    public void sortingAscendingPutsRowsNeedingAttentionFirst() {
        List<Usage> usages = new ArrayList<>(List.of(Usage.NOT_IN_USE, IN_USE, ISSUE));

        usages.sort(JobUsageColumn.BY_ATTENTION);

        assertEquals(List.of(ISSUE, IN_USE, Usage.NOT_IN_USE), usages);
    }

    @Test
    public void installShowsTheColumnFirstNarrowAndFixed() {
        JTable table = installedTable(new UsageTableModel(IN_USE));

        TableColumn column = table.getColumnModel().getColumn(0);
        assertEquals(USAGE_COLUMN, column.getModelIndex());
        assertFalse(column.getResizable());
        assertEquals(column.getMinWidth(), column.getMaxWidth());
        assertSame(JobUsageColumn.BY_ATTENTION,
                ((TableRowSorter<?>) table.getRowSorter()).getComparator(USAGE_COLUMN));
    }

    @Test
    public void theRendererShowsTheIconAndToolTipWithoutText() {
        JTable table = installedTable(new UsageTableModel(ISSUE));

        JLabel cell = (JLabel) table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);

        assertSame(JobUsageColumn.ISSUE, cell.getIcon());
        assertEquals("", cell.getText()); //$NON-NLS-1$
        assertEquals(JobUsageColumn.toolTip(ISSUE), cell.getToolTipText());
    }

    @Test
    public void onlyTheUsageColumnHeaderHasTheToolTipAndTheTablesOwnHeaderIsKept() {
        UsageTableModel model = new UsageTableModel(IN_USE);
        // The Feeders and Parts tables bring their own header, which install must keep.
        JTable table = new JTable(model);
        JTableHeader ownHeader = table.getTableHeader();
        TableRowSorter<UsageTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        JobUsageColumn.install(table, sorter, USAGE_COLUMN);
        table.setSize(TABLE_WIDTH, TABLE_HEIGHT);
        JTableHeader header = table.getTableHeader();
        header.setSize(TABLE_WIDTH, header.getPreferredSize().height);
        int usageX = header.getHeaderRect(0).x + 1;
        int nameX = header.getHeaderRect(1).x + 1;

        MouseEvent overUsage = mouseAt(header, usageX);
        header.dispatchEvent(overUsage);
        String usageToolTip = header.getToolTipText(overUsage);
        MouseEvent overName = mouseAt(header, nameX);
        header.dispatchEvent(overName);
        String nameToolTip = header.getToolTipText(overName);

        assertSame(ownHeader, header);
        assertEquals(Translations.getString("JobUsageColumn.Header.toolTip"), usageToolTip); //$NON-NLS-1$
        assertNull(nameToolTip);
    }

    @Test
    public void theHeaderRendererIsUntouched() {
        JTable table = new JTable(new UsageTableModel(IN_USE));
        TableRowSorter<UsageTableModel> sorter = new TableRowSorter<>(
                (UsageTableModel) table.getModel());
        table.setRowSorter(sorter);
        MultisortTableHeaderCellRenderer renderer = new MultisortTableHeaderCellRenderer();
        table.getTableHeader().setDefaultRenderer(renderer);

        JobUsageColumn.install(table, sorter, USAGE_COLUMN);

        assertSame(renderer, table.getTableHeader().getDefaultRenderer());
    }

    @Test
    public void theColumnWidthFollowsTheUserZoom() {
        JTable table = installedTable(new UsageTableModel(IN_USE));
        TableColumn column = table.getColumnModel().getColumn(0);
        int width = column.getMaxWidth();
        float zoom = UIScale.getZoomFactor();
        try {
            UIScale.setZoomFactor(zoom * ZOOM_STEP);

            assertTrue(column.getMaxWidth() > width);
            assertEquals(column.getMinWidth(), column.getMaxWidth());
        }
        finally {
            UIScale.setZoomFactor(zoom);
        }
    }

    private static MouseEvent mouseAt(JTableHeader header, int x) {
        Point point = new Point(x, 1);
        return new MouseEvent(header, MouseEvent.MOUSE_MOVED, 0, 0, point.x, point.y, 0, false);
    }
}
