package org.openpnp.gui.support;

import org.openpnp.model.AbstractVisionSettings;
import org.openpnp.model.Configuration;

public class VisionSettingsComboBoxModel<T extends AbstractVisionSettings> extends NamedComboBoxModel<AbstractVisionSettings> {
    private static final long serialVersionUID = 1L;

    public VisionSettingsComboBoxModel(Class<T> visionClass) {
        super("visionSettings", (Class<AbstractVisionSettings>) visionClass);
    }

    @Override
    protected void addAllElements() {
        Configuration.get().getVisionSettings().stream()
        .filter(namedClass::isInstance)
        .sorted(comparator)
        .forEach(this::addElement);
        addElement(null);
    }
}
