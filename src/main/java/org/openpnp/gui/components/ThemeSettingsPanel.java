package org.openpnp.gui.components;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.AbstractListModel;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;

import org.openpnp.Translations;
import org.openpnp.gui.support.FlexibleColor;
import org.openpnp.model.Configuration;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatPropertiesLaf;
import com.formdev.flatlaf.IntelliJTheme;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.intellijthemes.FlatAllIJThemes;
import com.formdev.flatlaf.intellijthemes.FlatAllIJThemes.FlatIJLookAndFeelInfo;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.util.StringUtils;
import com.formdev.flatlaf.util.UIScale;

public class ThemeSettingsPanel extends JPanel {
    /**
     * Zoom levels for the font size slider, expressed as scale factors relative
     * to FlatLaf's auto-detected default. Kept as a serializable enum for
     * backward compatibility with saved preferences.
     */
    public enum FontSize {
        SMALLEST(0.625f, 0),
        BELOW_SMALLER(0.6875f, 5),
        SMALLER(0.75f, 10),
        BELOW_SMALL(0.8125f, 15),
        SMALL(0.875f, 20),
        BELOW_DEFAULT(0.9375f, 25),
        DEFAULT(1.0f, 30),
        BELOW_LARGE(1.0625f, 35),
        LARGE(1.125f, 40),
        BELOW_LARGER(1.1875f, 45),
        LARGER(1.25f, 50),
        BELOW_HUGE(1.375f, 55),
        HUGE(1.5f, 60),
        BELOW_LARGEST(1.625f, 65),
        LARGEST(1.75f, 70);

        private final float scale;
        private final int percent;
        private static final Map<Integer, FontSize> percentToSize = new HashMap<>();

        FontSize(float scale, int percent) {
            this.scale = scale;
            this.percent = percent;
        }

        public float getScale() {
            return scale;
        }

        public int getPercent() {
            return percent;
        }

        static {
            for (FontSize fSize : FontSize.values()) {
                percentToSize.put(fSize.getPercent(), fSize);
            }
        }

        public static FontSize fromPercent(int percent) {
            FontSize fSize = percentToSize.get(percent);
            if (fSize == null) {
                return DEFAULT;
            }
            return fSize;
        }
    }

    public static final String THEMES_PACKAGE = "/com/formdev/flatlaf/intellijthemes/themes/";

    private final List<ThemeInfo> themes = new ArrayList<>();

    private JList<ThemeInfo> themesList;
    private JSlider fontSizeSlider;
    private FontSize currentFontSize;

    private JCheckBox chckbxAlternatingRows;

    public ThemeSettingsPanel() {
        initComponents();
        updateThemesList(null);
    }

    private void initComponents() {
        JLabel themesLabel = new JLabel();
        themesList = new JList<>();
        JScrollPane themesScrollPane = new JScrollPane();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        themesLabel.setText(Translations.getString("Theme.Section.Theme")); //$NON-NLS-1$
        themesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(themesLabel);
        themesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        themesScrollPane.setViewportView(themesList);
        themesScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(themesScrollPane);
        themesList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                ThemeInfo theme = (ThemeInfo) value;
                String name = theme.name;
                int sep = name.indexOf('/');
                if (sep >= 0) {
                    name = name.substring(sep + 1).trim();
                }

                JComponent c;
                if (theme.themeFile == null && theme.lafClassName == null && theme.resourceName == null) {
                    name = " - " + name + " -";
                    c = (JComponent) super.getListCellRendererComponent(list, name, index, false, false);
                    c.setEnabled(false);
                    c.setFocusable(false);
                    c.setAlignmentX(CENTER_ALIGNMENT);
                } else {
                    c = (JComponent) super.getListCellRendererComponent(list, name, index, isSelected, cellHasFocus);
                }
                c.setToolTipText(buildToolTip((ThemeInfo) value));
                return c;
            }

            private String buildToolTip(ThemeInfo ti) {
                if (ti.themeFile != null) {
                    return ti.themeFile.getPath();
                }
                if (ti.resourceName == null) {
                    return ti.name;
                }

                return "Name: " + ti.name;
            }
        });
        JLabel fontLabel = new JLabel();
        fontLabel.setText(Translations.getString("Theme.Section.FontSize")); //$NON-NLS-1$
        fontLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JSeparator separator = new JSeparator(JSeparator.HORIZONTAL);
        separator.setAlignmentX(Component.LEFT_ALIGNMENT);
        separator.setAlignmentY(Component.CENTER_ALIGNMENT);
        separator.setMaximumSize(new Dimension(99999999, 5));
        separator.setMinimumSize(new Dimension(0, 5));
        add(separator);

        chckbxAlternatingRows = new JCheckBox(Translations.getString("ThemeSettingsPanel.chckbxAlternatingRows.text")); //$NON-NLS-1$
        add(chckbxAlternatingRows);
        chckbxAlternatingRows.setSelected(UIManager.getColor("Table.alternateRowColor") != null);

        add(fontLabel);
        createFontSlider();
    }

    private void createFontSlider() {
        if (fontSizeSlider != null) {
            remove(fontSizeSlider);
        }
        boolean isFlatLaf = UIManager.getLookAndFeel() instanceof FlatLaf;
        if (!isFlatLaf) {
            fontSizeSlider = new JSlider();
            fontSizeSlider.setEnabled(false);
            fontSizeSlider.setMinimum(-1);
            fontSizeSlider.setMaximum(0);
            fontSizeSlider.setValue(-1);
            fontSizeSlider.setPaintTicks(true);
            fontSizeSlider.setOrientation(JSlider.HORIZONTAL);
            fontSizeSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(fontSizeSlider);
            return;
        }
        int defaultPercent = FontSize.DEFAULT.getPercent();
        int currentPercent = (currentFontSize != null)
                ? currentFontSize.getPercent()
                : FontSize.DEFAULT.getPercent();

        fontSizeSlider = new JSlider() {
            @Override
            public String getToolTipText(final MouseEvent event) {
                return getValue() + "%";
            }
        };
        fontSizeSlider.setToolTipText(String.valueOf(fontSizeSlider.getValue()));
        fontSizeSlider.setOrientation(JSlider.HORIZONTAL);
        fontSizeSlider.setPaintTicks(true);
        fontSizeSlider.setMinimum(FontSize.SMALLEST.getPercent());
        fontSizeSlider.setMaximum(FontSize.LARGEST.getPercent());
        int tickSpacing = 5;
        Dictionary<Integer, JComponent> dict = fontSizeSlider.createStandardLabels(tickSpacing);

        JLabel mid = ((JLabel) dict.get(defaultPercent));

        Enumeration<Integer> e = dict.keys();
        while (e.hasMoreElements()) {
            dict.remove(e.nextElement());
        }

        dict.put(defaultPercent, mid);
        mid.setText(Translations.getString("Theme.FontSize.Default")); //$NON-NLS-1$
        mid.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        mid.setHorizontalTextPosition(JLabel.RIGHT);

        fontSizeSlider.setLabelTable(dict);
        fontSizeSlider.setMajorTickSpacing(tickSpacing);
        fontSizeSlider.setMinorTickSpacing(tickSpacing);
        fontSizeSlider.setPaintLabels(true);
        fontSizeSlider.setSnapToTicks(true);
        fontSizeSlider.setValue(currentPercent);
        fontSizeSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(fontSizeSlider);
    }

    public void updateThemesList(ThemeInfo oldSel) {
        if (oldSel == null) {
            oldSel = themesList.getSelectedValue();
        }

        themes.clear();

        themes.add(new ThemeInfo(Translations.getString("Theme.Section.System"), null, false, null, null)); //$NON-NLS-1$
        UIManager.LookAndFeelInfo[] lookAndFeels = UIManager.getInstalledLookAndFeels();
        for (UIManager.LookAndFeelInfo lookAndFeel : lookAndFeels) {
            String name = lookAndFeel.getName();
            if (lookAndFeel.getClassName().equals(UIManager.getSystemLookAndFeelClassName())){
                name += " " + Translations.getString("Theme.Default"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String className = lookAndFeel.getClassName();
            themes.add(new ThemeInfo(name, null, false, null, className));
        }

        themes.add(new ThemeInfo(Translations.getString("Theme.Section.Extra"), null, false, null, null)); //$NON-NLS-1$
        themes.add(new ThemeInfo("Light", null, false, null, FlatLightLaf.class.getName()));
        themes.add(new ThemeInfo("Dark", null, true, null, FlatDarkLaf.class.getName()));
        themes.add(new ThemeInfo("IntelliJ Light", null, false, null, FlatIntelliJLaf.class.getName()));
        themes.add(new ThemeInfo("Darcula", null, true, null, FlatDarculaLaf.class.getName()));
        themes.add(new ThemeInfo("macOS Light", null, false, null, FlatMacLightLaf.class.getName()));
        themes.add(new ThemeInfo("macOS Dark", null, true, null, FlatMacDarkLaf.class.getName()));
        
        themes.add(new ThemeInfo(Translations.getString("Theme.Section.IntelliJ"), null, false, null, null)); //$NON-NLS-1$
        for (FlatIJLookAndFeelInfo info : FlatAllIJThemes.INFOS) {
        	themes.add(new ThemeInfo(info.getName(), null, info.isDark(), null, info.getClassName()));
        }        
        
        File themesDirectory = new File(Configuration.get().getConfigurationDirectory(), "themes");
        if (!themesDirectory.exists() || !themesDirectory.isDirectory()) {
            themesDirectory.mkdirs();
        }
        File[] themeFiles = themesDirectory.listFiles((dir, name) -> name.endsWith(".theme.json") || name.endsWith(".properties"));
        if (themeFiles != null) {
            if (themeFiles.length > 0) {
                themes.add(new ThemeInfo(Translations.getString("Theme.Section.User"), null, false, null, null)); //$NON-NLS-1$
                for (File f : themeFiles) {
                    String fName = f.getName();
                    String name = fName.endsWith(".properties")
                            ? StringUtils.removeTrailing(fName, ".properties")
                            : StringUtils.removeTrailing(fName, ".theme.json");
                    themes.add(new ThemeInfo(name, null, false, f, null));
                }
            }
        }

        themesList.setModel(new AbstractListModel<ThemeInfo>() {
            @Override
            public int getSize() {
                return themes.size();
            }

            @Override
            public ThemeInfo getElementAt(int index) {
                return themes.get(index);
            }
        });

        if (oldSel != null) {
            for (int i = 0; i < themes.size(); i++) {
                ThemeInfo theme = themes.get(i);
                if (oldSel.name.equals(theme.name) &&
                        Objects.equals(oldSel.resourceName, theme.resourceName) &&
                        Objects.equals(oldSel.themeFile, theme.themeFile) &&
                        Objects.equals(oldSel.lafClassName, theme.lafClassName)) {
                    themesList.setSelectedIndex(i);
                    break;
                }
            }

            if (themesList.getSelectedIndex() < 0) {
                themesList.setSelectedIndex(0);
            }
        }

        int sel = themesList.getSelectedIndex();
        if (sel >= 0) {
            Rectangle bounds = themesList.getCellBounds(sel, sel);
            if (bounds != null) {
                themesList.scrollRectToVisible(bounds);
            }
        }
    }

    public void setTheme(ThemeInfo themeInfo, FontSize fontSize, Boolean alternateRow) {
        if (themeInfo == null) {
            return;
        }
        // Set the zoom factor BEFORE installing the L&F so that FlatLaf
        // picks it up during initialization and computes correct font sizes.
        if (fontSize != null) {
            currentFontSize = fontSize;
            UIScale.setZoomFactor(fontSize.getScale());
        }
        // change look and feel
        if (themeInfo.lafClassName != null) {
            FlatAnimatedLafChange.showSnapshot();
            if (!themeInfo.lafClassName.equals(UIManager.getLookAndFeel().getClass().getName())) {
                if (themeInfo.lafClassName.equals("com.sun.java.swing.plaf.gtk.GTKLookAndFeel")) {
                    UIManager.put("Slider.paintValue", Boolean.FALSE);
                }
                try {
                    UIManager.setLookAndFeel(themeInfo.lafClassName);
                } catch (Exception ignore) {
                }
            }
        } else if (themeInfo.themeFile != null) {
            FlatAnimatedLafChange.showSnapshot();
            try {
                if (themeInfo.themeFile.getName().endsWith(".properties")) {
                    FlatLaf.setup(new FlatPropertiesLaf(themeInfo.name, themeInfo.themeFile));
                } else {
                    FlatLaf.setup(IntelliJTheme.createLaf(new FileInputStream(themeInfo.themeFile)));
                }
            } catch (Exception ignore) {
            }
        } else if (themeInfo.resourceName != null) {
            FlatAnimatedLafChange.showSnapshot();
            IntelliJTheme.setup(ThemeSettingsPanel.class.getResourceAsStream(THEMES_PACKAGE + themeInfo.resourceName));
        }
        FlexibleColor defaultRowColor = new FlexibleColor(UIManager.getColor("Table.background").getRGB());
        if (alternateRow == null || alternateRow) {
            if (FlatLaf.isLafDark()) {
                UIManager.put("Table.alternateRowColor", defaultRowColor.brighter(30));
            } else {
                UIManager.put("Table.alternateRowColor", defaultRowColor.darker(30));
            }
        }
        else {
            UIManager.put("Table.alternateRowColor", null);
        }
        FlatLaf.updateUI();
        removeAll();
        initComponents();
        updateThemesList(themeInfo);
        FlatAnimatedLafChange.hideSnapshotWithAnimation();
    }

    public ThemeInfo setTheme() {
        ThemeInfo themeInfo = themesList.getSelectedValue();
        EventQueue.invokeLater(() -> setTheme(themeInfo, getFontSize(), isAlternateRows()));
        return themeInfo;
    }

    public FontSize getFontSize() {
        int value = fontSizeSlider.getValue();
        if (value < 0) {
            return null;
        }
        return FontSize.fromPercent(fontSizeSlider.getValue());
    }

    public Boolean isAlternateRows() {
        return chckbxAlternatingRows.isSelected();
    }
}
