package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.BevelBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.SoftBevelBorder;

import org.openpnp.Main;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.util.DiagnosticsBundle;
import org.openpnp.util.UiUtils;
import org.pmw.tinylog.Logger;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

public class SubmitDiagnosticsDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private static final int VISION_IMAGE_LIMIT = 10;
    private static final String DIAGNOSTICS_DIRECTORY = "diagnostics";
    private static final String NEW_ISSUE_URL = "https://github.com/Wired-Square/openpnp/issues/new";
    private static final DateTimeFormatter ZIP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final JPanel contentPanel = new JPanel();
    private JTextArea descriptionTa;
    private JCheckBox includeMachineXmlChk;
    private JCheckBox includePartsXmlChk;
    private JCheckBox includePackagesXmlChk;
    private JCheckBox includeLogChk;
    private JCheckBox includeScreenShotChk;
    private JCheckBox includeVisionChk;
    private JCheckBox includeSystemInfoChk;
    private JCheckBox includeJobChk;
    private JLabel lblSubmitAHelp;
    private JProgressBar progressBar;
    private JButton okButton;
    private JButton cancelButton;
    private transient SwingWorker<File, Integer> worker;

    private transient BufferedImage screenShot;

    /**
     * Create the dialog.
     */
    public SubmitDiagnosticsDialog() {
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        getContentPane().setLayout(new BorderLayout());
        contentPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        getContentPane().add(contentPanel, BorderLayout.CENTER);
        FormLayout fl_contentPanel = new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("default:grow"),
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("default:grow"),},
            new RowSpec[] {
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.UNRELATED_GAP_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.UNRELATED_GAP_ROWSPEC,
                RowSpec.decode("default:grow"),
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.UNRELATED_GAP_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.PREF_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.UNRELATED_GAP_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.UNRELATED_GAP_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,});
        fl_contentPanel.setColumnGroups(new int[][]{new int[]{4, 2}});
        contentPanel.setLayout(fl_contentPanel);
        {
            lblSubmitAHelp = new JLabel("Save Diagnostics");
            lblSubmitAHelp.putClientProperty("FlatLaf.styleClass", "h1");
            contentPanel.add(lblSubmitAHelp, "2, 2, 3, 1");
        }
        {
            JTextPane txtpnToSubmitA = new JTextPane();
            txtpnToSubmitA.setBackground(UIManager.getColor("Label.background"));
            txtpnToSubmitA.setEditable(false);
            txtpnToSubmitA.setText(
                    "Describe the problem you are experiencing below, select the checkboxes to include content that will help resolve your issue, then click Save.\n\nOpenPnP saves a zip file in the diagnostics folder of your configuration directory. Nothing is uploaded. To report the problem, open an issue at github.com/Wired-Square/openpnp and attach the zip.\n\nReview the zip before you share it: configuration files and the log can contain private or proprietary information.");
            contentPanel.add(txtpnToSubmitA, "2, 6, 3, 1, fill, fill");
        }
        {
            txtpnWarningIfYou = new JTextPane();
            txtpnWarningIfYou.setText("Warning: If you include a screenshot or Vision Debug Images these images may include output from your machine's cameras. If these images contain content you don't want to share you should uncheck these options.");
            txtpnWarningIfYou.setForeground(Color.RED);
            txtpnWarningIfYou.setBackground(UIManager.getColor("Label.background"));
            txtpnWarningIfYou.setEditable(false);
            contentPanel.add(txtpnWarningIfYou, "2, 8, 3, 1, fill, fill");
        }
        {
            JLabel lblComments = new JLabel("Please Describe The Issue");
            lblComments.putClientProperty("FlatLaf.styleClass", "h4");
            contentPanel.add(lblComments, "2, 12");
        }
        {
            descriptionTa = new JTextArea();
            descriptionTa.setBorder(new SoftBevelBorder(BevelBorder.LOWERED, null, null, null, null));
            descriptionTa.setColumns(60);
            descriptionTa.setRows(10);
            contentPanel.add(descriptionTa, "2, 14, 3, 1, fill, fill");
        }
        {
            JLabel lblInclude = new JLabel("Include");
            lblInclude.putClientProperty("FlatLaf.styleClass", "h4");
            contentPanel.add(lblInclude, "2, 18");
        }
        {
            includeMachineXmlChk = new JCheckBox("machine.xml");
            includeMachineXmlChk.setSelected(true);
            contentPanel.add(includeMachineXmlChk, "2, 20");
        }
        {
            includePartsXmlChk = new JCheckBox("parts.xml");
            includePartsXmlChk.setSelected(true);
            contentPanel.add(includePartsXmlChk, "4, 20");
        }
        {
            includePackagesXmlChk = new JCheckBox("packages.xml");
            includePackagesXmlChk.setSelected(true);
            contentPanel.add(includePackagesXmlChk, "2, 22");
        }
        {
            includeLogChk = new JCheckBox("Latest Log File");
            includeLogChk.setSelected(true);
            contentPanel.add(includeLogChk, "4, 22");
        }
        {
            includeSystemInfoChk = new JCheckBox("Anonymous System Information");
            includeSystemInfoChk.setSelected(true);
            contentPanel.add(includeSystemInfoChk, "2, 24");
        }
        {
            includeJobChk = new JCheckBox("Current Job Data (Job Will Be Saved First)");
            includeJobChk.setSelected(true);
            contentPanel.add(includeJobChk, "4, 24");
        }
        {
            includeScreenShotChk = new JCheckBox("OpenPnP Window Screen Shot");
            includeScreenShotChk.setSelected(true);
            contentPanel.add(includeScreenShotChk, "2, 26");
        }
        {
            includeVisionChk = new JCheckBox("Vision Debug Images (" + VISION_IMAGE_LIMIT + " Newest)");
            includeVisionChk.setSelected(true);
            contentPanel.add(includeVisionChk, "4, 26");
        }
        {
            progressBar = new JProgressBar();
            progressBar.setStringPainted(true);
            contentPanel.add(progressBar, "2, 30, 3, 1");
        }
        {
            JPanel buttonPane = new JPanel();
            buttonPane.setLayout(new FlowLayout(FlowLayout.RIGHT));
            getContentPane().add(buttonPane, BorderLayout.SOUTH);
            {
                cancelButton = new JButton(cancelAction);
                buttonPane.add(cancelButton);
            }
            {
                okButton = new JButton(sendAction);
                buttonPane.add(okButton);
                getRootPane().setDefaultButton(okButton);
            }
        }

        // We have to grab a screenshot before the dialog is shown or the screenshot
        // will include the dialog. So we grab it and if we don't need it it just gets
        // ignored.
        try {
            screenShot = new Robot().createScreenCapture(MainFrame.get().getBounds());
        }
        catch (Exception e) {

        }
    }

    public transient Action sendAction = new AbstractAction("Save") {
        private static final long serialVersionUID = 1L;

        @Override
        public void actionPerformed(ActionEvent e) {
            okButton.setEnabled(false);
            DiagnosticsBundle bundle;
            File zip;
            // Saving can ask whether to save modified boards and panels, so it runs on the EDT,
            // as does saving the job; only the zip is written in the background.
            try {
                Configuration.get().save();
                bundle = createBundle();
                File directory = new File(Configuration.get().getConfigurationDirectory(),
                        DIAGNOSTICS_DIRECTORY);
                Files.createDirectories(directory.toPath());
                zip = new File(directory, "OpenPnP-Diagnostics-"
                        + LocalDateTime.now().format(ZIP_TIMESTAMP) + ".zip");
            }
            catch (Exception e1) {
                MessageBoxes.errorBox(MainFrame.get(), "Save Diagnostics Failed", e1);
                okButton.setEnabled(true);
                return;
            }
            progressBar.setMaximum(bundle.size());
            progressBar.setValue(0);
            worker = new SwingWorker<File, Integer>() {
                @Override
                protected File doInBackground() throws Exception {
                    bundle.write(zip, written -> publish(written));
                    return zip;
                }

                @Override
                protected void process(List<Integer> written) {
                    progressBar.setValue(written.get(written.size() - 1));
                }

                @Override
                protected void done() {
                    worker = null;
                    if (isCancelled()) {
                        // The write may have finished before it saw the cancel.
                        try {
                            Files.deleteIfExists(zip.toPath());
                        }
                        catch (IOException e1) {
                            Logger.warn(e1, "Unable to delete cancelled diagnostics " + zip);
                        }
                        return;
                    }
                    try {
                        File saved = get();
                        Logger.info("Saved diagnostics to " + saved);
                        dispose();
                        offerNextSteps(saved);
                    }
                    catch (InterruptedException | ExecutionException e1) {
                        Throwable cause = e1.getCause() != null ? e1.getCause() : e1;
                        Logger.error(cause, "Save diagnostics failed");
                        MessageBoxes.errorBox(MainFrame.get(), "Save Diagnostics Failed", cause);
                        okButton.setEnabled(true);
                    }
                }
            };
            worker.execute();
        }
    };

    private DiagnosticsBundle createBundle() throws Exception {
        DiagnosticsBundle bundle = new DiagnosticsBundle();
        File configDir = Configuration.get().getConfigurationDirectory();
        File logDir = new File(configDir, "log");
        bundle.addText("description.txt", descriptionTa.getText());
        if (includeMachineXmlChk.isSelected()) {
            bundle.addFile("machine.xml", new File(configDir, "machine.xml"));
        }
        if (includePartsXmlChk.isSelected()) {
            bundle.addFile("parts.xml", new File(configDir, "parts.xml"));
        }
        if (includePackagesXmlChk.isSelected()) {
            bundle.addFile("packages.xml", new File(configDir, "packages.xml"));
        }
        if (includeLogChk.isSelected()) {
            bundle.addFile("OpenPnP.log", new File(logDir, "OpenPnP.log"));
        }
        if (includeSystemInfoChk.isSelected()) {
            bundle.addText("system-info.txt", getSystemInfo());
        }
        if (includeJobChk.isSelected()) {
            Job job = MainFrame.get().getJobTab().getJob();
            String jobName = job.getFile() == null ? "job.job.xml" : job.getFile().getName();
            bundle.addBytes("job/" + jobName, Configuration.get().serializeJob(job));
            HashSet<Board> boards = new HashSet<>();
            for (BoardLocation bl : job.getBoardLocations()) {
                boards.add(bl.getBoard());
            }
            for (Board board : boards) {
                if (board.getFile() != null) {
                    bundle.addFile("job/" + board.getFile().getName(), board.getFile());
                }
            }
        }
        if (includeScreenShotChk.isSelected() && screenShot != null) {
            bundle.addPng("screenshot.png", screenShot);
        }
        if (includeVisionChk.isSelected()) {
            File[] visionFiles = new File(logDir, "vision")
                    .listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
            if (visionFiles != null) {
                Arrays.sort(visionFiles,
                        Comparator.comparingLong(File::lastModified).reversed());
                for (int i = 0; i < Math.min(visionFiles.length, VISION_IMAGE_LIMIT); i++) {
                    bundle.addFile("vision/" + visionFiles[i].getName(), visionFiles[i]);
                }
            }
        }
        return bundle;
    }

    private static void offerNextSteps(File zip) {
        String[] options = {"Open New Issue", "Show Folder", "Close"};
        int choice = JOptionPane.showOptionDialog(MainFrame.get(),
                "Saved diagnostics to\n" + zip.getAbsolutePath()
                        + "\n\nTo report the problem, open a new issue and attach this file.",
                "Diagnostics Saved", JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            UiUtils.browseUri(NEW_ISSUE_URL);
        }
        else if (choice == 1) {
            try {
                Desktop.getDesktop().open(zip.getParentFile());
            }
            catch (Exception e) {
                MessageBoxes.errorBox(MainFrame.get(), "Unable to open the folder", e);
            }
        }
    }

    public transient Action cancelAction = new AbstractAction("Cancel") {
        private static final long serialVersionUID = 1L;

        @Override
        public void actionPerformed(ActionEvent arg0) {
            if (worker != null) {
                // Interrupts the write, which deletes the partial zip.
                worker.cancel(true);
            }
            dispose();
        }
    };
    private JTextPane txtpnWarningIfYou;

    static String getSystemInfo() throws Exception {
        StringBuffer sb = new StringBuffer();
        String[] keys = new String[] {"os.name", "os.arch", "java.runtime.name", "java.vm.vendor",
                "java.vm.name", "user.country", "java.runtime.version", "os.version",
                "java.vm.info", "java.version",};
        for (String key : keys) {
            sb.append(String.format("%s: %s\n", key, System.getProperty(key)));
        }
        sb.append(String.format("Memory Total: %.2f\n",
                Runtime.getRuntime().totalMemory() / 1024.0 / 1024.0));
        sb.append(String.format("Memory Free: %.2f\n",
                Runtime.getRuntime().freeMemory() / 1024.0 / 1024.0));
        sb.append(String.format("Memory Max: %.2f\n",
                Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0));
        sb.append(String.format("OpenPnp Version: %s", Main.getVersion()));
        return sb.toString();
    }
}
