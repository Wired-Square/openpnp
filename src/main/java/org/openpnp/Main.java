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

package org.openpnp;

import java.awt.EventQueue;
import java.io.File;
import java.util.Locale;

import javax.swing.ToolTipManager;
import javax.swing.UIManager;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.ThemeDialog;
import org.openpnp.gui.components.ThemeInfo;
import org.openpnp.gui.components.ThemeSettingsPanel;
import org.openpnp.logging.ConsoleWriter;
import org.openpnp.logging.SystemLogger;
import org.openpnp.model.Configuration;
import org.pmw.tinylog.Configurator;
import org.pmw.tinylog.Level;
import org.pmw.tinylog.Logger;
import org.pmw.tinylog.writers.RollingFileWriter;
import org.apache.commons.io.FileUtils;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

/**
 * Start with -Xdock:name=OpenPnP on Mac to make it prettier.
 * 
 * @author jason
 *
 */
public class Main {

    public static String getVersion() {
        return getVersionString()+"_"+getBuildString();
    }

    public static String getVersionString() {
        String version = null;
        try {
            version = FileUtils.readFileToString(new File("VERSION.txt"));
        }
        catch (Exception e) {
        }

        if (version==null) {
            version = "n/a";
        }

        return version.strip();
    }

    public static String getBuildString() {
        String version = Main.class.getPackage().getImplementationVersion();
        if (version == null) {
            version = "INTERNAL BUILD";
        }
        return version;
    }

    public static String getSourceUri() {
        String version = Main.class.getPackage().getImplementationVersion();
        if (version == null) {
            // Select the test branch, as this is likely a developer running OpenPnP.
            version = "test"; 
        }
        else {
            // Take the hash.
            version = version.substring(version.indexOf(".")+1);
        }
        return "https://github.com/openpnp/openpnp/blob/"+version+"/";
    }

    private static void configureLogging(File configurationDirectory) {
        File logDirectory = new File(configurationDirectory, "log");
        File logFile = new File(logDirectory, "OpenPnP.log");
        Configurator
            .currentConfig()
            .writer(new RollingFileWriter(logFile.getAbsolutePath(), 100))
            .addWriter(new ConsoleWriter(System.out, System.err))
            .activate();
        Configurator.currentConfig()
            .formatPattern("{date:yyyy-MM-dd HH:mm:ss.SSS} {class_name} {level}: {message}")
            .activate();

        // Redirect the stdout and stderr to the LogPanel
        SystemLogger out = new SystemLogger(System.out, Level.INFO);
        SystemLogger err = new SystemLogger(System.err, Level.ERROR);
        System.setOut(out);
        System.setErr(err);
    }
    
    private static void monkeyPatchBeansBinding() {
        // This hack fixes a bug in BeansBinding that will never be released due to to the library
        // being abandoned. The bug is that in BeansBinding.bind, it chooses to call an uncached
        // introspection method rather than a cached one. This causes each binding to take upwards
        // of 50ms on my machine. On a form with many bindings this can cause a huge load time
        // when loading wizards. This was most apparent on Feeders.
        // Note that the bug was fixed in Subversion in revision 629:
        // https://java.net/projects/beansbinding/sources/svn/revision/629
        // But it is unlikely this will ever be released to Maven.
        // This hack was found at http://blog.marcnuri.com/beansbinding-performance-issue-37/
        try {
            ClassPool cp = ClassPool.getDefault();
            CtClass cc = cp.get("org.jdesktop.beansbinding.ELProperty");
            CtMethod m = cc.getDeclaredMethod("getBeanInfo");
            m.setBody("{" +
            // "assert $1 != null;" +
                    "try {" + "return java.beans.Introspector.getBeanInfo($1.getClass());"
                    + "} catch (java.beans.IntrospectionException ie) {"
                    + "throw new org.jdesktop.beansbinding.PropertyResolutionException(\"Exception while introspecting \" + $1.getClass().getName(), ie);"
                    + "} }");
            Class c = cc.toClass();
            cc = cp.get("org.jdesktop.beansbinding.BeanProperty");
            m = cc.getDeclaredMethod("getBeanInfo");
            m.setBody("{" +
            // "assert $1 != null;" +
                    "try {" + "return java.beans.Introspector.getBeanInfo($1.getClass());"
                    + "} catch (java.beans.IntrospectionException ie) {"
                    + "throw new org.jdesktop.beansbinding.PropertyResolutionException(\"Exception while introspecting \" + $1.getClass().getName(), ie);"
                    + "} }");
            c = cc.toClass();
        }
        catch (NotFoundException ex) {
            ex.printStackTrace();
        }
        catch (CannotCompileException ex) {
            ex.printStackTrace();
        }
    }

    public static void main(String[] args) {
        monkeyPatchBeansBinding();

        for (String s : args) {
            if (s.equals("--version")) {
                System.out.println(getVersion());
                System.exit(0);
            }
        }

        // http://developer.apple.com/library/mac/#documentation/Java/Conceptual/Java14Development/07-NativePlatformIntegration/NativePlatformIntegration.html#//apple_ref/doc/uid/TP40001909-212952-TPXREF134
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        catch (Exception e) {
            throw new Error(e);
        }

        File configurationDirectory = new File(System.getProperty("user.home"));
        configurationDirectory = new File(configurationDirectory, ".openpnp2");

        if (System.getProperty("configDir") != null) {
            configurationDirectory = new File(System.getProperty("configDir"));
        }

        configurationDirectory.mkdirs();

        configureLogging(configurationDirectory);

        Configuration.initialize(configurationDirectory);
        final Configuration configuration = Configuration.get();
        Locale.setDefault(Configuration.get().getLocale());

        Boolean cachedDarkMode = configureWaylandScaling(configuration.getThemeInfo() == null);

        ThemeInfo theme = configuration.getThemeInfo();
        if (theme == null) {
            boolean dark = (cachedDarkMode != null) ? cachedDarkMode : isSystemDarkMode();
            if (dark) {
                theme = new ThemeInfo("Dark", null, true, null,
                        com.formdev.flatlaf.FlatDarkLaf.class.getName());
            } else {
                theme = new ThemeInfo("Light", null, false, null,
                        com.formdev.flatlaf.FlatLightLaf.class.getName());
            }
        }
        new ThemeSettingsPanel().setTheme(theme, configuration.getFontSize(), configuration.isAlternateRows());
        ThemeDialog.getInstance().setOldTheme(theme);
        ToolTipManager.sharedInstance().setDismissDelay(60000);

        EventQueue.invokeLater(new Runnable() {
            public void run() {
                try {
                    MainFrame frame = new MainFrame(configuration);
                    frame.setVisible(true);
                    Logger.info(String.format("Bienvenue, Bienvenido, Willkommen, Hello, Namaskar, Welkom, Bonjour to OpenPnP version %s.", Main.getVersion()));
                    configuration.getScripting().on("Startup", null);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * On GNOME Wayland, Java Swing runs under XWayland, which has known issues
     * with DPI scaling that cause FlatLaf to either double-scale or not scale
     * at all.  This method queries GNOME's actual display scale and the native
     * monitor resolution via D-Bus, then computes the correct
     * {@code flatlaf.uiScale} from the formula:
     *
     * <pre>uiScale = toolkitWidth &times; gnomeScale / nativeWidth</pre>
     *
     * <p>This formula handles both XWayland modes:
     * <ul>
     *   <li><b>Classic (integer scaling):</b> coordinate space = logical size,
     *       so toolkitWidth = nativeWidth/gnomeScale, giving uiScale = 1.</li>
     *   <li><b>xwayland-native-scaling:</b> coordinate space = buffer size
     *       = ceil(gnomeScale) &times; logical, giving uiScale = ceil(gnomeScale).</li>
     * </ul>
     *
     * <p>If Java already reports system scaling (e.g. a future JDK with native
     * Wayland support), or if the user/launcher has set {@code flatlaf.uiScale},
     * this method does nothing.
     */
    /**
     * Configures FlatLaf DPI scaling for GNOME Wayland sessions.  When
     * {@code needsDarkMode} is true and we are on Wayland, the dark mode
     * preference is queried in the same D-Bus connection to avoid opening
     * a second connection later.
     *
     * @param needsDarkMode whether the caller also needs dark mode detection
     * @return the dark mode result if it was queried as a side-effect
     *         ({@code Boolean.TRUE}/{@code Boolean.FALSE}), or {@code null}
     *         if dark mode was not queried (non-Wayland or early exit)
     */
    private static Boolean configureWaylandScaling(boolean needsDarkMode) {
        if (!"wayland".equals(System.getenv("XDG_SESSION_TYPE"))) {
            return null;
        }
        if (System.getProperty("flatlaf.uiScale") != null) {
            if (needsDarkMode && com.formdev.flatlaf.util.SystemInfo.isLinux) {
                return org.openpnp.util.GnomeDisplayConfig.isDesktopDarkMode();
            }
            return null;
        }

        // If Java already detects system scaling (future JDK with native Wayland),
        // FlatLaf's isSystemScaling() will handle it correctly.
        try {
            double sysScale = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration()
                    .getDefaultTransform().getScaleX();
            if (sysScale > 1.0) {
                if (needsDarkMode && com.formdev.flatlaf.util.SystemInfo.isLinux) {
                    return org.openpnp.util.GnomeDisplayConfig.isDesktopDarkMode();
                }
                return null;
            }
        } catch (Exception e) {
            return null;
        }

        // Query GNOME's display configuration and (optionally) dark mode
        // preference in a single D-Bus session connection.
        double[] config = needsDarkMode
                ? org.openpnp.util.GnomeDisplayConfig.queryScaleWidthAndDarkMode()
                : org.openpnp.util.GnomeDisplayConfig.queryScaleAndNativeWidth();
        double gnomeScale = config[0];
        double nativeWidth = config[1];

        if (gnomeScale > 1.0 && nativeWidth > 0) {
            int toolkitWidth = java.awt.Toolkit.getDefaultToolkit()
                    .getScreenSize().width;
            // Classic XWayland: toolkitWidth ≈ nativeWidth/gnomeScale → uiScale ≈ 1
            // Native scaling:   toolkitWidth ≈ ceil(gnomeScale) × nativeWidth/gnomeScale
            //                   → uiScale ≈ ceil(gnomeScale)
            int uiScale = (int) Math.round(
                    toolkitWidth * gnomeScale / nativeWidth);
            if (uiScale > 1) {
                System.setProperty("flatlaf.uiScale", String.valueOf(uiScale));
            }
        }

        return (config.length > 2) ? (config[2] == 1.0) : null;
    }

    /**
     * Detects whether the OS is configured for dark mode.
     * Falls back to false (light) if detection fails.
     */
    private static boolean isSystemDarkMode() {
        try {
            if (com.formdev.flatlaf.util.SystemInfo.isMacOS) {
                Process p = new ProcessBuilder("/usr/bin/defaults",
                        "read", "-g", "AppleInterfaceStyle")
                        .redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor();
                return "Dark".equalsIgnoreCase(output);
            }
            if (com.formdev.flatlaf.util.SystemInfo.isWindows) {
                String systemRoot = System.getenv("SystemRoot");
                if (systemRoot == null) {
                    systemRoot = "C:\\Windows";
                }
                Process p = new ProcessBuilder(systemRoot + "\\System32\\reg.exe",
                        "query",
                        "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme")
                        .redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor();
                // Value is 0x0 for dark, 0x1 for light
                return output.contains("0x0");
            }
            if (com.formdev.flatlaf.util.SystemInfo.isLinux) {
                // XDG Desktop Portal Settings works across GNOME, KDE,
                // and other freedesktop-compliant desktops via D-Bus.
                return org.openpnp.util.GnomeDisplayConfig.isDesktopDarkMode();
            }
        } catch (Exception e) {
            // Detection failed — fall through to default
        }
        return false;
    }
}
