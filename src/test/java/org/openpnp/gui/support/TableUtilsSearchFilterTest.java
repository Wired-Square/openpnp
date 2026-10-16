package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

import org.junit.jupiter.api.Test;

public class TableUtilsSearchFilterTest {
    /** Column 1 throws, like a placements column that needs a job context. */
    static class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return 2;
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public Object getValueAt(int row, int column) {
            if (column == 1) {
                throw new IllegalStateException("hidden column read");
            }
            return row == 0 ? "R0402" : "C0603";
        }
    }

    private static int filteredRows(JTable table, String search) {
        TableRowSorter<Model> sorter = new TableRowSorter<>((Model) table.getModel());
        RowFilter<Model, Integer> filter = TableUtils.createVisibleColumnsSearchFilter(table, search);
        sorter.setRowFilter(filter);
        return sorter.getViewRowCount();
    }

    @Test
    public void searchesOnlyVisibleColumns() {
        JTable table = new JTable(new Model());
        table.removeColumn(table.getColumnModel().getColumn(1));

        assertEquals(1, filteredRows(table, "r04"));
    }

    @Test
    public void noVisibleColumnsMatchesNothingWithoutReadingHiddenCells() {
        JTable table = new JTable(new Model());
        table.removeColumn(table.getColumnModel().getColumn(1));
        table.removeColumn(table.getColumnModel().getColumn(0));

        assertEquals(0, filteredRows(table, "r04"));
    }
}
