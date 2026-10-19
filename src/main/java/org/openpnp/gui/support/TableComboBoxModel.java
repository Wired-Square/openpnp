package org.openpnp.gui.support;

import org.openpnp.model.Configuration;
import org.openpnp.model.Identifiable;

import javax.swing.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

public class TableComboBoxModel<T extends Identifiable> extends DefaultComboBoxModel<T> implements PropertyChangeListener {
    private static final long serialVersionUID = 1L;

    protected final transient IdentifiableComparator<T> comparator = new IdentifiableComparator<>();

    public TableComboBoxModel(String property) {
        addAllElements();
        Configuration.get().addPropertyChangeListener(property, this);
    }

    protected void addAllElements() {
        throw new UnsupportedOperationException("Calling this function from general TableComboBoxModel is not supported");
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        removeAllElements();
        addAllElements();
    }
}
