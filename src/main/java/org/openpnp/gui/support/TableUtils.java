/*
 * Copyright (C) 2022 Jason von Nieda <jason@vonnieda.org>, Tony Luken <tonyluken62+openpnp@gmail.com>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.gui.support;

import java.awt.Component;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.prefs.Preferences;

import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;

import com.formdev.flatlaf.util.UIScale;

import org.openpnp.gui.tablemodel.ColumnWidthSaveable;
import org.openpnp.gui.tablemodel.ColumnAlignable;

public class TableUtils {

    public static final int MIN_COLUMN_WIDTH = 10;
    private static final int RESIZE_DEBOUNCE_MS = 200;
    
    /**
     * Sets the horizontal alignment of each of the columns of the specified table..
     * @param tableModel - the table model for the table. Must implement ColumnAlignable.
     * @param table - the table whose columns' horizontal alignment is to be set
     */
    public static void setColumnAlignment(ColumnAlignable tableModel, JTable table) {
        int[] alignments = tableModel.getColumnAlignments();
        for (int iCol=0; iCol<table.getColumnCount(); iCol++) {
            table.getColumnModel().getColumn(iCol).setHeaderRenderer(new CustomAlignmentRenderer<>(
                    table.getTableHeader().getDefaultRenderer(), alignments[iCol]));

            table.getColumnModel().getColumn(iCol).setCellRenderer(new CustomAlignmentRenderer<>(
                    table.getCellRenderer(0, iCol), alignments[iCol]));

        }
    }

    /**
     * Auto-sizes a single column of a table based on header text and content.
     * Uses interactive-resize style layout so the column gets its exact
     * computed width and subsequent columns absorb the difference, rather than
     * JTable scaling all columns proportionally.
     */
    public static void autoSizeColumn(JTable table, int viewCol) {
        int width = computePreferredColumnWidth(table, viewCol, table.getRowCount());
        TableColumn col = table.getColumnModel().getColumn(viewCol);
        col.setPreferredWidth(width);

        // Simulate interactive resize: set resizingColumn so doLayout uses
        // accommodateDelta (adjusts subsequent columns) instead of
        // setWidthsFromPreferredWidths (proportional scaling of all columns).
        javax.swing.table.JTableHeader header = table.getTableHeader();
        if (header != null) {
            header.setResizingColumn(col);
        }
        col.setWidth(width);
        table.doLayout();
        if (header != null) {
            header.setResizingColumn(null);
        }

        // Sync all columns' preferred widths to their actual widths so that
        // any deferred proportional relayout is a no-op (scale factor = 1.0).
        TableColumnModel cm = table.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            TableColumn tc = cm.getColumn(i);
            if (tc.getPreferredWidth() != tc.getWidth()) {
                tc.setPreferredWidth(tc.getWidth());
            }
        }
    }

    /**
     * Installs listeners on each column of the table as well as the table itself that save and
     * restore the table's column widths. If the table's TableModel implements ColumnWidthSaveable,
     * the restored column widths will be a combination of fixed and proportionally allocated to the
     * table's total width based upon the values returned by the model's getColumnWidthTypes() 
     * method. Otherwise, all columns will be proportionally allocated to the table's total width.
     * @param table - the table whose columns' widths are to be saved/restored
     * @param prefs - the Preferences node where the widths are stored/retrieved
     * @param prefKey - the key prefix for the particular table
     */
    public static void installColumnWidthSavers(JTable table, Preferences prefs, String prefKey) {
        for (int iCol=0; iCol<table.getColumnCount(); iCol++) {
            table.getColumnModel().getColumn(iCol).addPropertyChangeListener(new PropertyChangeListener() {

                @Override
                public void propertyChange(PropertyChangeEvent evt) {
                    if (evt.getPropertyName() == "preferredWidth") {
                        String key = prefKey + ((TableColumn) evt.getSource()).getModelIndex();
                        prefs.putInt(key, (int) evt.getNewValue());
                    }
                }
            });
        }

        Timer resizeTimer = new Timer(RESIZE_DEBOUNCE_MS, e -> restoreColumnWidths(table, prefs, prefKey));
        resizeTimer.setRepeats(false);
        table.addComponentListener(new ComponentAdapter() {

            @Override
            public void componentResized(ComponentEvent e) {
                resizeTimer.restart();
            }

        });

        // When no saved prefs exist and the table is initially empty, re-restore
        // column widths once data arrives so content-based sizing can take effect.
        if (table.getRowCount() == 0 && !hasAnyPref(table, prefs, prefKey)) {
            table.getModel().addTableModelListener(new javax.swing.event.TableModelListener() {
                @Override
                public void tableChanged(javax.swing.event.TableModelEvent e) {
                    if (table.getRowCount() > 0) {
                        table.getModel().removeTableModelListener(this);
                        SwingUtilities.invokeLater(() -> restoreColumnWidths(table, prefs, prefKey));
                    }
                }
            });
        }
    }

    private static boolean hasAnyPref(JTable table, Preferences prefs, String prefKey) {
        for (int iCol = 0; iCol < table.getColumnCount(); iCol++) {
            int iModelCol = table.getColumnModel().getColumn(iCol).getModelIndex();
            if (prefs.getInt(prefKey + iModelCol, -1) >= MIN_COLUMN_WIDTH) {
                return true;
            }
        }
        return false;
    }
    
    private static void restoreColumnWidths(JTable table, Preferences prefs, String prefKey) {
        int nCols = table.getColumnCount();
        int nModelCols = table.getModel().getColumnCount();
        int totalWidth = table.getColumnModel().getTotalColumnWidth();

        // Detect DPI/scale changes by comparing a saved reference to the
        // current UIScale.  UIScale.scale() captures both system DPI and
        // FlatLaf user zoom, so this handles monitor changes and font size
        // slider changes.
        String scaleKey = prefKey + ".uiScale";
        int currentScaleRef = UIScale.scale(100);
        int savedScaleRef = prefs.getInt(scaleKey, currentScaleRef);
        float dpiRatio = (savedScaleRef > 0)
                ? (float) currentScaleRef / savedScaleRef : 1.0f;

        int[] widthTypes;
        try {
            widthTypes = ((ColumnWidthSaveable) table.getModel()).getColumnWidthTypes();
        }
        catch (ClassCastException ex) {
            //If that failed, treat all columns as being PROPORTIONAL
            widthTypes = new int[nModelCols];
            for (int i=0; i<nModelCols; i++) {
                widthTypes[i] = ColumnWidthSaveable.PROPORTIONAL;
            }
        }
        int[] prefWidths = new int[nCols];
        int totalFixed = 0;
        int totalProportional = 0;
        int numberProportional = 0;
        //Get the column width saved in the Preferences and compute the sum of the fixed
        //width columns and the sum of the proportional columns
        for (int iCol=0; iCol<nCols; iCol++) {
            int iModelCol = table.getColumnModel().getColumn(iCol).getModelIndex();
            prefWidths[iCol] = prefs.getInt(prefKey + iModelCol, -1);
            if (prefWidths[iCol] < MIN_COLUMN_WIDTH) {
                prefWidths[iCol] = computePreferredColumnWidth(table, iCol, 20);
            }
            else if (dpiRatio != 1.0f) {
                // Scale saved pixel widths to match the current DPI
                prefWidths[iCol] = Math.round(prefWidths[iCol] * dpiRatio);
            }
            if (widthTypes[iModelCol] == ColumnWidthSaveable.FIXED) {
                totalFixed += prefWidths[iCol];
            }
            if (widthTypes[iModelCol] == ColumnWidthSaveable.PROPORTIONAL) {
                totalProportional += prefWidths[iCol];
                numberProportional++;
            }
        }
        //If none of the columns were marked as being proportionally allocated, treat all
        //columns as being proportionally allocated
        if (totalProportional == 0) {
            totalProportional = totalFixed;
            totalFixed = 0;
        }
        //If the total width of the fixed columns is greater than the width of the table, adjust
        //the total width to allow for a positive width for each of the proportional columns  
        if (totalFixed > totalWidth) {
            totalWidth = totalFixed + 5*numberProportional;
        }
        //Compute the scale factor to be applied to each of the proportional columns
        double scale = (totalWidth - totalFixed) / (double) totalProportional;
        int[] newWidths = new int[nCols];
        int newTotalWidth = 0;
        //Compute the new width of each columns
        for (int iCol=0; iCol<nCols; iCol++) {
            int iModelCol = table.getColumnModel().getColumn(iCol).getModelIndex();
            if (widthTypes[iModelCol] == ColumnWidthSaveable.PROPORTIONAL || totalFixed == 0) {
                newWidths[iCol] = (int) (prefWidths[iCol] * scale);
            }
            else {
                newWidths[iCol] = prefWidths[iCol];
            }
            newTotalWidth += newWidths[iCol];
        }
        int i = 0;
        //Due to truncation, the total of the new columns widths may not sum to the total table
        //width so spread the excess evenly across as many columns as necessary to make-up for
        //the difference
        while (newTotalWidth < totalWidth) {
            int iModelCol = table.getColumnModel().getColumn(i).getModelIndex();
            if (widthTypes[iModelCol] == ColumnWidthSaveable.PROPORTIONAL || totalFixed == 0) {
                newWidths[i]++;
                newTotalWidth++;
            }
            i = (i+1) % nCols;
        }
        
        SwingUtilities.invokeLater(() -> {
            //Set the preferred width of the columns
            for (int iCol=0; iCol<nCols; iCol++) {
                table.getColumnModel().getColumn(iCol).setPreferredWidth(newWidths[iCol]);
            }
            // Save the current scale so future DPI changes are detected
            prefs.putInt(scaleKey, currentScaleRef);
        });
    }

    private static int computePreferredColumnWidth(JTable table, int viewCol, int maxRows) {
        int padding = 10;
        // Use the table's current default column width (typically 75px) as a floor
        int minWidth = table.getColumnModel().getColumn(viewCol).getPreferredWidth();

        TableColumn column = table.getColumnModel().getColumn(viewCol);
        TableCellRenderer headerRenderer = column.getHeaderRenderer();
        if (headerRenderer == null && table.getTableHeader() != null) {
            headerRenderer = table.getTableHeader().getDefaultRenderer();
        }
        int width = minWidth;
        if (headerRenderer != null) {
            Component comp = headerRenderer.getTableCellRendererComponent(
                    table, column.getHeaderValue(), false, false, -1, viewCol);
            width = Math.max(width, comp.getPreferredSize().width + padding);
        }

        int rows = Math.min(table.getRowCount(), maxRows);
        for (int row = 0; row < rows; row++) {
            TableCellRenderer renderer = table.getCellRenderer(row, viewCol);
            Component comp = table.prepareRenderer(renderer, row, viewCol);
            width = Math.max(width, comp.getPreferredSize().width + padding);
        }
        return Math.max(width, MIN_COLUMN_WIDTH);
    }

    /**
     * Creates a case-insensitive {@link RowFilter} that matches the given search text against only
     * the columns currently shown in the specified table.
     *
     * @param table the table whose visible columns should be searched
     * @param searchText the user's raw search text
     * @return a filter over the visible columns
     * @throws java.util.regex.PatternSyntaxException if the search text is not a valid regex
     */
    public static <M extends TableModel> RowFilter<M, Integer> createVisibleColumnsSearchFilter(
            JTable table, String searchText) {
        TableColumnModel columnModel = table.getColumnModel();
        if (columnModel.getColumnCount() == 0) {
            // regexFilter treats an empty index list as "every model column", which would read
            // the hidden cells this filter exists to avoid.
            return new RowFilter<M, Integer>() {
                @Override
                public boolean include(Entry<? extends M, ? extends Integer> entry) {
                    return false;
                }
            };
        }
        int[] modelColumnIndices = new int[columnModel.getColumnCount()];
        for (int i = 0; i < modelColumnIndices.length; i++) {
            modelColumnIndices[i] = columnModel.getColumn(i).getModelIndex();
        }
        return RowFilter.regexFilter("(?i)" + searchText.trim(), modelColumnIndices); //$NON-NLS-1$
    }
}
