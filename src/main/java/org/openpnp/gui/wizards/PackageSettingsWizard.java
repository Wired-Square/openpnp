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

package org.openpnp.gui.wizards;

import javax.swing.JPanel;
import javax.swing.border.TitledBorder;

import org.jdesktop.beansbinding.AutoBinding.UpdateStrategy;
import org.openpnp.Translations;
import org.openpnp.gui.components.ComponentDecorators;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.DoubleConverter;
import org.openpnp.gui.support.LengthConverter;
import org.openpnp.gui.support.MutableLocationProxy;

import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.RowSpec;
import com.jgoodies.forms.layout.FormSpecs;
import javax.swing.JLabel;
import javax.swing.JTextField;

public class PackageSettingsWizard extends AbstractConfigurationWizard {
    private static final long serialVersionUID = 1L;

    private final transient org.openpnp.model.Package pkg;
    private JPanel vacuumBlowOffPanel;
    private JLabel lblNewLabel;
    private JTextField vacuumLevel;
    private JLabel lblBlowOffLevel;
    private JTextField blowOffLevel;
    private JTextField pickOffsetX;
    private JTextField pickOffsetY;

    public PackageSettingsWizard(org.openpnp.model.Package pkg) {
        this.pkg = pkg;
        createUi();
    }
    
    private void createUi() {
        vacuumBlowOffPanel = new JPanel();
        contentPanel.add(vacuumBlowOffPanel);
        
        vacuumBlowOffPanel.setBorder(new TitledBorder(null, Translations.getString(
                "PackageSettingsWizard.Border.title"), //$NON-NLS-1$
                TitledBorder.LEADING, TitledBorder.TOP, null, null));
        add(vacuumBlowOffPanel);
        vacuumBlowOffPanel.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("default:grow"),},
            new RowSpec[] {
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,}));
        
        lblNewLabel = new JLabel(Translations.getString("PackageSettingsWizard.VacuumLevelLabel.text")); //$NON-NLS-1$
        vacuumBlowOffPanel.add(lblNewLabel, "2, 2, right, default");
        
        vacuumLevel = new JTextField();
        vacuumBlowOffPanel.add(vacuumLevel, "4, 2, left, default");
        vacuumLevel.setColumns(10);
        
        lblBlowOffLevel = new JLabel(Translations.getString("PackageSettingsWizard.BlowOffLevelLabel.text")); //$NON-NLS-1$
        vacuumBlowOffPanel.add(lblBlowOffLevel, "2, 4, right, default");
        
        blowOffLevel = new JTextField();
        vacuumBlowOffPanel.add(blowOffLevel, "4, 4, left, default");
        blowOffLevel.setColumns(10);

        JPanel pickPanel = new JPanel();
        pickPanel.setBorder(new TitledBorder(null, Translations.getString(
                "PackageSettingsWizard.PickOffsetBorder.title"), //$NON-NLS-1$
                TitledBorder.LEADING, TitledBorder.TOP, null, null));
        contentPanel.add(pickPanel);
        pickPanel.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("default:grow"),},
            new RowSpec[] {
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,}));

        JLabel lblPickOffset = new JLabel(Translations.getString(
                "PackageSettingsWizard.PickOffsetLabel.text")); //$NON-NLS-1$
        lblPickOffset.setToolTipText(Translations.getString(
                "PackageSettingsWizard.PickOffsetLabel.toolTipText")); //$NON-NLS-1$
        pickPanel.add(lblPickOffset, "2, 2, right, default");

        pickOffsetX = new JTextField();
        pickPanel.add(pickOffsetX, "4, 2, left, default");
        pickOffsetX.setColumns(10);

        pickOffsetY = new JTextField();
        pickPanel.add(pickOffsetY, "6, 2, left, default");
        pickOffsetY.setColumns(10);
    }

    @Override
    public void createBindings() {
        DoubleConverter doubleConverter = new DoubleConverter("%.1f");
        
        bind(UpdateStrategy.READ_WRITE, pkg, "pickVacuumLevel", vacuumLevel, "text", doubleConverter);
        bind(UpdateStrategy.READ_WRITE, pkg, "placeBlowOffLevel", blowOffLevel, "text", doubleConverter);
        
        LengthConverter lengthConverter = new LengthConverter();
        MutableLocationProxy pickOffsetProxy = new MutableLocationProxy();
        bind(UpdateStrategy.READ_WRITE, pkg, "pickOffset", pickOffsetProxy, "location");
        bind(UpdateStrategy.READ_WRITE, pickOffsetProxy, "lengthX", pickOffsetX, "text",
                lengthConverter);
        bind(UpdateStrategy.READ_WRITE, pickOffsetProxy, "lengthY", pickOffsetY, "text",
                lengthConverter);

        ComponentDecorators.decorateWithAutoSelect(vacuumLevel);
        ComponentDecorators.decorateWithAutoSelect(blowOffLevel);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(pickOffsetX);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(pickOffsetY);
    }
}
