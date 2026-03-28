package org.openpnp.gui.support;

import java.awt.Color;

import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;

/**
 * Theme-aware semantic colours. Cached per theme; the cache is invalidated
 * automatically when the L&amp;F dark/light state changes.
 */
public final class UIColors {

    private UIColors() {}

    // -- Theme cache --------------------------------------------------------

    private static boolean cachedDark;
    private static boolean cacheInitialised;

    // Log
    private static Color logTrace, logDebug, logInfo, logWarning, logError, logErrorBg;
    // Placement type
    private static Color typeFiducial;
    // Status
    private static Color statusReady, statusWarning, statusError, statusDisabled;
    // Affine
    private static Color affineGloballySet, affineLocallySet;
    private static Color affineGloballySetAlt, affineLocallySetAlt;
    // Validation
    private static Color validationError;

    private static void ensureCache() {
        boolean dark = FlatLaf.isLafDark();
        if (cacheInitialised && dark == cachedDark) {
            return;
        }
        cachedDark = dark;
        cacheInitialised = true;

        logTrace      = dark ? new Color(100, 200, 100) : new Color(64, 128, 64);
        logDebug      = dark ? new Color(160, 160, 160) : new Color(128, 128, 128);
        logInfo       = dark ? new Color(80, 140, 255)  : new Color(0, 91, 217);
        logWarning    = dark ? new Color(255, 100, 100) : new Color(255, 0, 0);
        logError      = dark ? new Color(255, 100, 100) : new Color(255, 0, 0);
        logErrorBg    = dark ? new Color(80, 40, 40)    : new Color(255, 255, 220);

        typeFiducial  = dark ? new Color(70, 90, 140)   : new Color(157, 188, 255);

        statusReady    = dark ? new Color(50, 120, 60)  : new Color(157, 255, 168);
        statusWarning  = dark ? new Color(120, 120, 40) : new Color(252, 255, 157);
        statusError    = dark ? new Color(140, 50, 50)  : new Color(255, 157, 157);
        statusDisabled = dark ? new Color(90, 90, 90)   : new Color(180, 180, 180);

        affineGloballySet    = dark ? new Color(50, 120, 60)  : new Color(157, 255, 168);
        affineLocallySet     = dark ? new Color(55, 75, 130)  : new Color(157, 188, 255);
        affineGloballySetAlt = dark ? new Color(45, 108, 54)  : new Color(141, 230, 151);
        affineLocallySetAlt  = dark ? new Color(50, 68, 117)  : new Color(141, 169, 230);

        validationError = dark ? new Color(100, 50, 50) : new Color(255, 221, 221);
    }

    // --- Log level colours ---

    public static Color logTrace() {
        ensureCache();
        return logTrace;
    }

    public static Color logDebug() {
        ensureCache();
        return logDebug;
    }

    public static Color logInfo() {
        ensureCache();
        return logInfo;
    }

    public static Color logWarning() {
        ensureCache();
        return logWarning;
    }

    public static Color logError() {
        ensureCache();
        return logError;
    }

    public static Color logErrorBg() {
        ensureCache();
        return logErrorBg;
    }

    // --- Placement type colours ---

    public static Color typeFiducial() {
        ensureCache();
        return typeFiducial;
    }

    // --- Status colours ---

    public static Color statusReady() {
        ensureCache();
        return statusReady;
    }

    public static Color statusWarning() {
        ensureCache();
        return statusWarning;
    }

    public static Color statusError() {
        ensureCache();
        return statusError;
    }

    public static Color statusDisabled() {
        ensureCache();
        return statusDisabled;
    }

    // --- Affine transform status colours ---

    public static Color affineGloballySet() {
        ensureCache();
        return affineGloballySet;
    }

    public static Color affineLocallySet() {
        ensureCache();
        return affineLocallySet;
    }

    public static Color affineGloballySetAlt() {
        ensureCache();
        return affineGloballySetAlt;
    }

    public static Color affineLocallySetAlt() {
        ensureCache();
        return affineLocallySetAlt;
    }

    // --- Validation error background ---

    public static Color validationError() {
        ensureCache();
        return validationError;
    }

    // --- DRO colours (always coloured backgrounds, no dark variant needed) ---

    private static final Color DRO_NORMAL = new Color(0xBDFFBE);
    private static final Color DRO_SAVED = new Color(0x90cce0);

    public static Color droNormal() {
        return DRO_NORMAL;
    }

    public static Color droSaved() {
        return DRO_SAVED;
    }

    /** Standard foreground for the DRO display (dark text on bright signal backgrounds). */
    public static Color droForeground() {
        return Color.BLACK;
    }

    // --- Grid colour ---

    /** Semi-transparent grid line colour derived from the L&amp;F separator colour. */
    public static Color gridColor() {
        Color base = UIManager.getColor("Separator.foreground");
        if (base == null) {
            return new Color(0, 0, 0, 64);
        }
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 64);
    }

    // --- Camera overlay colours (fixed — render over camera images, not UI backgrounds) ---

    private static final Color CAMERA_OVERLAY_BG = new Color(0, 0, 0, 0.75f);
    private static final Color CAMERA_JOG_HANDLE_INACTIVE = new Color(125, 125, 125);
    private static final Color CAMERA_JOG_HANDLE_ACTIVE = Color.white;
    private static final Color CAMERA_LIGHT_TOGGLE_INACTIVE_BG = new Color(255, 255, 255, 100);
    private static final Color CAMERA_LIGHT_TOGGLE_ACTIVE_BG = new Color(0, 0, 0, 100);
    private static final Color CAMERA_LIGHT_TOGGLE_INACTIVE = new Color(0, 0, 0, 200);
    private static final Color CAMERA_LIGHT_TOGGLE_ACTIVE = new Color(255, 255, 125, 200);
    private static final Color CAMERA_SELECTION_HANDLE = new Color(153, 153, 187);
    private static final Color CAMERA_HISTOGRAM_BG = new Color(1, 1, 1, 0.20f);

    public static Color cameraOverlayBg() {
        return CAMERA_OVERLAY_BG;
    }

    public static Color cameraJogHandleInactive() {
        return CAMERA_JOG_HANDLE_INACTIVE;
    }

    public static Color cameraJogHandleActive() {
        return CAMERA_JOG_HANDLE_ACTIVE;
    }

    public static Color cameraLightToggleInactiveBg() {
        return CAMERA_LIGHT_TOGGLE_INACTIVE_BG;
    }

    public static Color cameraLightToggleActiveBg() {
        return CAMERA_LIGHT_TOGGLE_ACTIVE_BG;
    }

    public static Color cameraLightToggleInactive() {
        return CAMERA_LIGHT_TOGGLE_INACTIVE;
    }

    public static Color cameraLightToggleActive() {
        return CAMERA_LIGHT_TOGGLE_ACTIVE;
    }

    public static Color cameraSelectionHandle() {
        return CAMERA_SELECTION_HANDLE;
    }

    public static Color cameraHistogramBg() {
        return CAMERA_HISTOGRAM_BG;
    }
}
