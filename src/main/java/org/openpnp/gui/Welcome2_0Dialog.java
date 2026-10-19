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

package org.openpnp.gui;

import org.openpnp.gui.support.DialogUtils;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.net.URI;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

import org.apache.commons.io.FileUtils;
import org.openpnp.Main;

import org.openpnp.Translations;
import org.openpnp.gui.components.MarkupTextPane;

public class Welcome2_0Dialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final JPanel contentPanel = new JPanel();
    private MarkupTextPane markupTextPane;

    public Welcome2_0Dialog(Frame frame) {
        super(frame, true);
        String title = String.format(Translations.getString("WelcomeDialog.title"), Main.getVersionString()); //$NON-NLS-1$
        setTitle(title);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        getContentPane().setLayout(new BorderLayout());
        contentPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        getContentPane().add(contentPanel, BorderLayout.CENTER);
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        JLabel lblOpenpnp = new JLabel(
                Translations.getString(title)); //$NON-NLS-1$
        lblOpenpnp.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblOpenpnp.putClientProperty("FlatLaf.styleClass", "appTitle");
        contentPanel.add(lblOpenpnp);
        JLabel lblCopyright = new JLabel("Copyright © 2011 - 2025 Jason von Nieda and contributors");
        lblCopyright.putClientProperty("FlatLaf.styleClass", "small");
        lblCopyright.setAlignmentX(Component.CENTER_ALIGNMENT);
        contentPanel.add(lblCopyright);
        JLabel lblVersion = new JLabel("Version: " + Main.getVersion());
        lblVersion.putClientProperty("FlatLaf.styleClass", "small");
        lblVersion.setAlignmentX(Component.CENTER_ALIGNMENT);
        contentPanel.add(lblVersion);

        markupTextPane = new MarkupTextPane();
        contentPanel.add(markupTextPane);

        JPanel buttonPane = new JPanel();
        buttonPane.setLayout(new FlowLayout(FlowLayout.RIGHT));
        getContentPane().add(buttonPane, BorderLayout.SOUTH);
        JButton okButton = new JButton("OK");
        okButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                setVisible(false);
            }
        });
        okButton.setActionCommand("OK");
        buttonPane.add(okButton);
        getRootPane().setDefaultButton(okButton);

        try {
            String s = FileUtils.readFileToString(new File("OPENPNP_2_0.md"));
            markupTextPane.setText(s);
            // HACK: can't truly pinpoint hyperlinks in the text, so point it to the original.
            markupTextPane.setUri(new URI(Main.getSourceUri()+"OPENPNP_2_0.md"));
        }
        catch (Exception e) {
            markupTextPane.setPlainText(e.toString());
        }

        DialogUtils.addPadding(this);
        DialogUtils.sizeAndCenter(this);
    }
}
