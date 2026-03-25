package org.openpnp.vision.pipeline.ui;

import java.awt.Color;

import javax.swing.UIManager;

import org.openpnp.spi.Actuator;

import com.l2fprod.common.beans.editor.BooleanAsCheckBoxPropertyEditor;
import com.l2fprod.common.beans.editor.ComboBoxPropertyEditor;
import com.l2fprod.common.beans.editor.DoublePropertyEditor;
import com.l2fprod.common.beans.editor.StringPropertyEditor;
import com.l2fprod.common.propertysheet.PropertySheetTable;
import com.l2fprod.common.propertysheet.PropertySheetTableModel;
import com.l2fprod.common.swing.renderer.BooleanCellRenderer;
import com.l2fprod.common.swing.renderer.DefaultCellRenderer;

public class PipelinePropertySheetTable extends PropertySheetTable {
    private PipelinePanel pipelinePanel;

    public PipelinePropertySheetTable(PipelinePanel pipelinePanel) {
        this.pipelinePanel = pipelinePanel;
        // The l2fprod PropertySheetTable.initDefaultColors() applies
        // .darker().darker().darker() to the category foreground, which
        // makes text invisible in dark themes. Override with proper
        // LAF-aware colours.
        Color panelBg = UIManager.getColor("Panel.background");
        Color tableFg = UIManager.getColor("Table.foreground");
        Color tableBg = UIManager.getColor("Table.background");
        Color selBg = UIManager.getColor("Table.selectionBackground");
        Color selFg = UIManager.getColor("Table.selectionForeground");
        setCategoryBackground(panelBg);
        setCategoryForeground(tableFg);
        setSelectedCategoryBackground(selBg);
        setSelectedCategoryForeground(selFg);
        setPropertyBackground(tableBg);
        setPropertyForeground(tableFg);
        setSelectedPropertyBackground(selBg);
        setSelectedPropertyForeground(selFg);
        setGridColor(panelBg);
        getRendererRegistry().registerRenderer(Object.class, createThemedRenderer());
    }

    /**
     * Creates a DefaultCellRenderer that does not override the background
     * with the hardcoded SystemColor.window used by the l2fprod default.
     */
    public static DefaultCellRenderer createThemedRenderer() {
        DefaultCellRenderer renderer = new DefaultCellRenderer();
        renderer.setShowOddAndEvenRows(false);
        return renderer;
    }

    @Override
    public void setValueAt(Object aValue, int row, int column) {
        super.setValueAt(aValue, row, column);

        pipelinePanel.onStagePropertySheetValueChanged(aValue, row, column);
    }

    /**
     * Provide support for Actuator variant value types. 
     * 
     * @param table
     * @param propertyName
     * @param actuator
     */
    public void customizeActuatorProperty(String propertyName,
            Actuator actuator) {
        if (actuator != null) {
            PropertySheetTableModel model = (PropertySheetTableModel)getModel();
            com.l2fprod.common.propertysheet.Property actuatorValueProperty = null; 
            for (com.l2fprod.common.propertysheet.Property property : model.getProperties()) {
                if (property.getName().equals(propertyName)) {
                    actuatorValueProperty = property;
                    break;
                }
            }
            if (actuatorValueProperty != null) {
                switch (actuator.getValueType()) {
                    case Boolean:
                        getEditorRegistry().registerEditor(actuatorValueProperty, new BooleanAsCheckBoxPropertyEditor());
                        getRendererRegistry().registerRenderer(Object.class, new BooleanCellRenderer());
                        getRendererRegistry().registerRenderer(String.class, createThemedRenderer());
                        break;
                    case Double:
                        getEditorRegistry().registerEditor(actuatorValueProperty, new DoublePropertyEditor());
                        getRendererRegistry().registerRenderer(Object.class, createThemedRenderer());
                        break;
                    case String:
                        getEditorRegistry().registerEditor(actuatorValueProperty, new StringPropertyEditor());
                        getRendererRegistry().registerRenderer(Object.class, createThemedRenderer());
                        break;
                    case Profile:
                        getEditorRegistry().registerEditor(actuatorValueProperty, new ActuatorProfileEditor(actuator.getProfileValues()));
                        getRendererRegistry().registerRenderer(Object.class, createThemedRenderer());
                        break;
                }
            }
        }
    }

    public static class ActuatorProfileEditor extends ComboBoxPropertyEditor {
        public ActuatorProfileEditor(String [] values) {
            super();
            setAvailableValues(values);
        }
    }
}
