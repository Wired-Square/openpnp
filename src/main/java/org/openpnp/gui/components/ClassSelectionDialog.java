/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
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

package org.openpnp.gui.components;

import org.openpnp.Translations;
import org.openpnp.gui.support.DialogUtils;
import org.openpnp.vision.pipeline.Stage;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;


public class ClassSelectionDialog<T> extends JDialog {
    private static final long serialVersionUID = 1L;

    private Class<? extends T> selectedClass;
    private JTable table;
    private transient List<Class<? extends T>> availableClasses;

    public ClassSelectionDialog(Frame parent, String title, String description,
            List<Class<? extends T>> availableClasses) {
        super(parent, title, true);
        this.availableClasses = availableClasses;

        JPanel panel = new JPanel();
        panel.setBorder(new EmptyBorder(5, 5, 5, 5));
        getContentPane().add(panel, BorderLayout.CENTER);
        panel.setLayout(new BorderLayout(0, 0));

        JPanel panelActions = new JPanel();
        panel.add(panelActions, BorderLayout.SOUTH);
        panelActions.setLayout(new FlowLayout(FlowLayout.RIGHT, 5, 5));

        JButton btnCancel = new JButton(cancelAction);
        panelActions.add(btnCancel);

        JButton btnSelect = new JButton(selectAction);
        panelActions.add(btnSelect);

        JLabel lblDescription =
                new JLabel("Please select an implemention class from the list.");
        lblDescription.setBorder(new EmptyBorder(5, 5, 5, 5));
        panel.add(lblDescription, BorderLayout.NORTH);
        lblDescription.setHorizontalAlignment(SwingConstants.LEFT);

        lblDescription.setText(description);

        table = new JTable(new ClassTableModel()) {
            private static final long serialVersionUID = 1L;

            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row >= 0) {
                    row = convertRowIndexToModel(row);
                    String desc = getAnnotationDescription(availableClasses.get(row));
                    if (!desc.isEmpty()) {
                        return "<html><body style='width:300px'>" + desc + "</body></html>";
                    }
                }
                return null;
            }
        };
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(0).setMinWidth(120);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    selectAction.actionPerformed(null);
                }
            }
        });
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        // setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        DialogUtils.addPadding(this);
        DialogUtils.sizeAndCenter(this);

        table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) {
                    return;
                }
                selectAction.setEnabled(table.getSelectedRow() >= 0);
            }
        });

        JRootPane rootPane = getRootPane();
        KeyStroke stroke = KeyStroke.getKeyStroke("ESCAPE");
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        inputMap.put(stroke, "ESCAPE");
        rootPane.getActionMap().put("ESCAPE", cancelAction);

        selectAction.setEnabled(false);
    }

    private final transient Action selectAction = new AbstractAction(Translations.getString(
            "ClassSelectionDialog.Action.Accept")) { //$NON-NLS-1$
        private static final long serialVersionUID = 1L;

        @Override
        public void actionPerformed(ActionEvent arg0) {
            int row = table.getSelectedRow();
            if (row >= 0) {
                row = table.convertRowIndexToModel(row);
                selectedClass = availableClasses.get(row);
            }
            setVisible(false);
        }
    };

    private final transient Action cancelAction = new AbstractAction(Translations.getString(
            "ClassSelectionDialog.Action.Cancel")) { //$NON-NLS-1$
        private static final long serialVersionUID = 1L;

        @Override
        public void actionPerformed(ActionEvent arg0) {
            setVisible(false);
        }
    };

    public Class<? extends T> getSelectedClass() {
        return selectedClass;
    }

    private static String getAnnotationDescription(Class<?> clz) {
        Stage annotation = clz.getAnnotation(Stage.class);
        if (annotation != null) {
            String desc = annotation.description();
            if (desc != null && !desc.isEmpty()) {
                return desc;
            }
        }
        return "";
    }

    private class ClassTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;

        private final String[] columnNames = {"Name", "Description"};

        @Override
        public int getRowCount() {
            return availableClasses.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Class<? extends T> clz = availableClasses.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return clz.getSimpleName();
                case 1:
                    return getAnnotationDescription(clz);
            }
            return null;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }
}