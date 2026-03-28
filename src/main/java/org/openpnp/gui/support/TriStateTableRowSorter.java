package org.openpnp.gui.support;

import java.util.ArrayList;
import java.util.List;

import javax.swing.SortOrder;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

/**
 * A TableRowSorter that cycles through three sort states per column:
 * ASCENDING → DESCENDING → UNSORTED, instead of the default two-state
 * ASCENDING ↔ DESCENDING cycle.  Multi-column sorting is preserved:
 * only the clicked column's key is removed when cycling to UNSORTED.
 */
public class TriStateTableRowSorter<M extends TableModel> extends TableRowSorter<M> {

    public TriStateTableRowSorter(M model) {
        super(model);
    }

    @Override
    public void toggleSortOrder(int column) {
        if (column < 0 || column >= getModelWrapper().getColumnCount()
                || !isSortable(column)) {
            return;
        }
        List<? extends SortKey> current = getSortKeys();
        if (!current.isEmpty()) {
            SortKey first = current.get(0);
            if (first.getColumn() == column
                    && first.getSortOrder() == SortOrder.DESCENDING) {
                // Third click: cycle to UNSORTED by removing this column's key
                List<SortKey> remaining = new ArrayList<>();
                for (SortKey key : current) {
                    if (key.getColumn() != column) {
                        remaining.add(key);
                    }
                }
                setSortKeys(remaining.isEmpty() ? null : remaining);
                return;
            }
        }
        super.toggleSortOrder(column);
    }
}
