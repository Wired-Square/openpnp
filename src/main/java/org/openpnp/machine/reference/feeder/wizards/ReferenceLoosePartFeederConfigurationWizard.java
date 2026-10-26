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

package org.openpnp.machine.reference.feeder.wizards;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.ComponentDecorators;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.LengthConverter;

import org.openpnp.machine.reference.feeder.ReferenceLoosePartFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.util.UiUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;
import org.openpnp.vision.pipeline.ui.CvPipelineEditorDialog;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Color;

import java.awt.FlowLayout;

public class ReferenceLoosePartFeederConfigurationWizard
        extends AbstractReferenceFeederConfigurationWizard {
    private final ReferenceLoosePartFeeder feeder;
    private final JTextField searchWidthTf;
    private final JTextField searchHeightTf;
    private final JTextField partSizeTf;

    public ReferenceLoosePartFeederConfigurationWizard(ReferenceLoosePartFeeder feeder) {
        super(feeder);
        this.feeder = feeder;

        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null, "Vision", TitledBorder.LEADING, TitledBorder.TOP,
                null, null));
        contentPanel.add(panel);
        panel.setLayout(new FormLayout(
                new ColumnSpec[] {FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC,},
                new RowSpec[] {FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,}));

        JButton btnEditPipeline = new JButton("Edit Pipeline");
        btnEditPipeline.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                UiUtils.messageBoxOnException(() -> {
                    editPipeline();
                });
            }
        });
        panel.add(btnEditPipeline, "2, 2");

        JButton btnResetPipeline = new JButton("Reset Pipeline");
        btnResetPipeline.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                resetPipeline();
            }
        });
        panel.add(btnResetPipeline, "4, 2");
        
        JPanel searchPanel = new JPanel();
        searchPanel.setBorder(new TitledBorder(null, "Search Area", TitledBorder.LEADING,
                TitledBorder.TOP, null, null));
        contentPanel.add(searchPanel);
        searchPanel.setLayout(new FormLayout(
                new ColumnSpec[] {FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC,},
                new RowSpec[] {FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,}));
        searchPanel.add(new JLabel("Width (X)"), "2, 2, right, default");
        searchWidthTf = new JTextField();
        searchWidthTf.setColumns(8);
        searchPanel.add(searchWidthTf, "4, 2");
        searchPanel.add(new JLabel("Height (Y)"), "6, 2, right, default");
        searchHeightTf = new JTextField();
        searchHeightTf.setColumns(8);
        searchPanel.add(searchHeightTf, "8, 2");
        searchPanel.add(new JLabel("Largest part size"), "2, 4, right, default");
        partSizeTf = new JTextField();
        partSizeTf.setColumns(8);
        searchPanel.add(partSizeTf, "4, 4");
        JLabel searchHelp = new JLabel(Icons.info);
        searchHelp.setToolTipText("<html>The full area, centred on the location; 0 searches one "
                + "frame.<br>Frames overlap by the largest part size (0: half a frame).</html>");
        searchPanel.add(searchHelp, "6, 4, left, default");

        JLabel lblWarningThisFeeder = new JLabel("Warning: This feeder is incomplete and experimental. Use at your own risk.");
        lblWarningThisFeeder.putClientProperty("FlatLaf.styleClass", "large");
        lblWarningThisFeeder.setForeground(Color.RED);
        lblWarningThisFeeder.setHorizontalAlignment(SwingConstants.LEFT);
    }

    @Override
    public void createBindings() {
        super.createBindings();
        LengthConverter lengthConverter = new LengthConverter();
        addWrappedBinding(feeder, "searchWidth", searchWidthTf, "text", lengthConverter);
        addWrappedBinding(feeder, "searchHeight", searchHeightTf, "text", lengthConverter);
        addWrappedBinding(feeder, "partSize", partSizeTf, "text", lengthConverter);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(searchWidthTf);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(searchHeightTf);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(partSizeTf);
    }

    private void editPipeline() throws Exception {
        if (feeder.getPart() == null) {
            throw new Exception("Feeder "+feeder.getName()+" has no part.");
        }
        CvPipeline pipeline = feeder.getPipeline();
        pipeline.setProperty("camera", Configuration.get().getMachine().getDefaultHead().getDefaultCamera());
        pipeline.setProperty("feeder", feeder);
        CvPipelineEditor editor = new CvPipelineEditor(pipeline);
        JDialog dialog = new CvPipelineEditorDialog(MainFrame.get(), feeder.getPart().getId() + " Pipeline", editor);
        dialog.setVisible(true);
}

    private void resetPipeline() {
        feeder.resetPipeline();
    }
}
