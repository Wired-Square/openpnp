package org.openpnp.util;

import java.util.List;
import java.util.Map;

import org.freedesktop.dbus.Tuple;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusMemberName;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * Queries the Linux desktop environment via D-Bus for display
 * configuration and appearance settings.  Used to detect the GNOME
 * display scale factor (for correct FlatLaf scaling on Wayland) and the
 * system dark/light colour scheme preference.
 *
 * <p>All methods return safe defaults if D-Bus is unavailable or the
 * desktop is not supported.
 */
public final class GnomeDisplayConfig {

    private GnomeDisplayConfig() {
    }

    // -- D-Bus types and interface proxies --------------------------------

    /**
     * Tuple for the four OUT parameters of
     * {@code org.gnome.Mutter.DisplayConfig.GetCurrentState}.
     * The complex nested fields (monitors, logicalMonitors, properties)
     * are kept as raw {@code Object} and parsed manually, because
     * dbus-java cannot resolve deeply nested generic Struct types at
     * runtime due to type erasure.
     */
    public static final class DisplayState extends Tuple {
        @Position(0) private final UInt32 serial;
        @Position(1) private final Object monitors;
        @Position(2) private final Object logicalMonitors;
        @Position(3) private final Object properties;

        public DisplayState(UInt32 serial, Object monitors,
                            Object logicalMonitors, Object properties) {
            this.serial = serial;
            this.monitors = monitors;
            this.logicalMonitors = logicalMonitors;
            this.properties = properties;
        }

        public Object getMonitors() { return monitors; }
        public Object getLogicalMonitors() { return logicalMonitors; }
    }

    /** Proxy for org.gnome.Mutter.DisplayConfig (GNOME-specific). */
    @DBusInterfaceName("org.gnome.Mutter.DisplayConfig") //$NON-NLS-1$
    public interface DisplayConfigIface extends DBusInterface {
        @DBusMemberName("GetCurrentState") //$NON-NLS-1$
        DisplayState getCurrentState();
    }

    /** Proxy for org.freedesktop.portal.Settings (cross-desktop). */
    @DBusInterfaceName("org.freedesktop.portal.Settings") //$NON-NLS-1$
    public interface PortalSettingsIface extends DBusInterface {
        @DBusMemberName("Read") //$NON-NLS-1$
        Variant<?> read(String namespace, String key);
    }

    // -- Display scale query (GNOME/Mutter) ------------------------------

    /**
     * Queries the primary monitor's scale factor and the native width of
     * its current display mode from GNOME's Mutter compositor via the
     * session D-Bus.
     *
     * <p>The raw D-Bus response contains:
     * <pre>(serial, monitors[], logicalMonitors[], properties)</pre>
     * where each logical monitor is {@code (x, y, scale, transform,
     * primary, monitors, props)} and each monitor mode is
     * {@code (name, width, height, rate, scale, scales[], props)}.
     *
     * @return a two-element array {@code {gnomeScale, nativeWidth}}, or
     *         {@code {1.0, 0}} if the query fails or the desktop is not
     *         GNOME
     */
    public static double[] queryScaleAndNativeWidth() {
        try (DBusConnection conn = DBusConnectionBuilder
                .forSessionBus().build()) {

            DisplayConfigIface proxy = conn.getRemoteObject(
                    "org.gnome.Mutter.DisplayConfig", //$NON-NLS-1$
                    "/org/gnome/Mutter/DisplayConfig", //$NON-NLS-1$
                    DisplayConfigIface.class);

            DisplayState state = proxy.getCurrentState();

            double gnomeScale = extractPrimaryScale(
                    state.getLogicalMonitors());
            double nativeWidth = extractCurrentModeWidth(
                    state.getMonitors());

            return new double[]{gnomeScale, nativeWidth};

        } catch (Exception e) {
            // Not on GNOME, D-Bus unavailable, or Mutter not running.
            return new double[]{1.0, 0};
        }
    }

    /**
     * Extracts the scale factor from the primary logical monitor.
     * Logical monitor struct: (x, y, scale, transform, primary,
     * monitors, properties).
     */
    @SuppressWarnings("unchecked")
    private static double extractPrimaryScale(Object logicalMonitors) {
        if (!(logicalMonitors instanceof List)) {
            return 1.0;
        }
        for (Object lm : (List<Object>) logicalMonitors) {
            if (!(lm instanceof Object[])) {
                continue;
            }
            Object[] fields = (Object[]) lm;
            // fields[2] = scale (double), fields[4] = primary (boolean)
            if (fields.length > 4
                    && fields[4] instanceof Boolean
                    && (Boolean) fields[4]
                    && fields[2] instanceof Number) {
                return ((Number) fields[2]).doubleValue();
            }
        }
        return 1.0;
    }

    /**
     * Extracts the native width from the current display mode.
     * Monitor struct: (id, modes[], properties).
     * Mode struct: (name, width, height, rate, scale, scales[],
     * properties).
     */
    @SuppressWarnings("unchecked")
    private static double extractCurrentModeWidth(Object monitors) {
        if (!(monitors instanceof List)) {
            return 0;
        }
        for (Object mon : (List<Object>) monitors) {
            if (!(mon instanceof Object[])) {
                continue;
            }
            Object[] monFields = (Object[]) mon;
            // monFields[1] = modes (list of mode structs)
            if (monFields.length <= 1 || !(monFields[1] instanceof List)) {
                continue;
            }
            for (Object mode : (List<Object>) monFields[1]) {
                if (!(mode instanceof Object[])) {
                    continue;
                }
                Object[] modeFields = (Object[]) mode;
                // modeFields[1] = width, modeFields[6] = properties dict
                if (modeFields.length > 6
                        && modeFields[6] instanceof Map) {
                    Map<String, Variant<?>> props =
                            (Map<String, Variant<?>>) modeFields[6];
                    Variant<?> isCurrent =
                            props.get("is-current"); //$NON-NLS-1$
                    if (isCurrent != null
                            && Boolean.TRUE.equals(isCurrent.getValue())
                            && modeFields[1] instanceof Number) {
                        return ((Number) modeFields[1]).doubleValue();
                    }
                }
            }
        }
        return 0;
    }

    // -- Dark mode detection (cross-desktop) -----------------------------

    /**
     * Queries whether the desktop environment prefers a dark colour
     * scheme via the XDG Desktop Portal Settings interface.  This works
     * on any freedesktop-compliant desktop (GNOME, KDE, etc.).
     *
     * @return {@code true} if the desktop prefers dark mode
     */
    public static boolean isDesktopDarkMode() {
        try (DBusConnection conn = DBusConnectionBuilder
                .forSessionBus().build()) {
            return queryDarkMode(conn);
        } catch (Exception e) {
            // Portal unavailable — fall through
        }
        return false;
    }

    /**
     * Queries both the display scale/resolution and the dark mode
     * preference using a single D-Bus session connection.  Avoids the
     * overhead of opening two separate connections when both values are
     * needed on startup.
     *
     * @return a three-element array
     *         {@code {gnomeScale, nativeWidth, isDark}} where isDark is
     *         1.0 for dark and 0.0 for light/unknown
     */
    public static double[] queryScaleWidthAndDarkMode() {
        try (DBusConnection conn = DBusConnectionBuilder
                .forSessionBus().build()) {

            double gnomeScale = 1.0;
            double nativeWidth = 0;
            try {
                DisplayConfigIface proxy = conn.getRemoteObject(
                        "org.gnome.Mutter.DisplayConfig", //$NON-NLS-1$
                        "/org/gnome/Mutter/DisplayConfig", //$NON-NLS-1$
                        DisplayConfigIface.class);
                DisplayState state = proxy.getCurrentState();
                gnomeScale = extractPrimaryScale(state.getLogicalMonitors());
                nativeWidth = extractCurrentModeWidth(state.getMonitors());
            } catch (Exception e) {
                // Not on GNOME or Mutter not running
            }

            boolean dark = queryDarkMode(conn);
            return new double[]{gnomeScale, nativeWidth, dark ? 1.0 : 0.0};

        } catch (Exception e) {
            return new double[]{1.0, 0, 0.0};
        }
    }

    private static boolean queryDarkMode(DBusConnection conn) {
        try {
            PortalSettingsIface settings = conn.getRemoteObject(
                    "org.freedesktop.portal.Desktop", //$NON-NLS-1$
                    "/org/freedesktop/portal/desktop", //$NON-NLS-1$
                    PortalSettingsIface.class);

            Variant<?> result = settings.read(
                    "org.freedesktop.appearance", //$NON-NLS-1$
                    "color-scheme"); //$NON-NLS-1$

            // Portal returns Variant<Variant<uint32>>:
            //   0 = no preference, 1 = prefer dark, 2 = prefer light
            Object value = result.getValue();
            if (value instanceof Variant<?>) {
                value = ((Variant<?>) value).getValue();
            }
            if (value instanceof UInt32) {
                return ((UInt32) value).intValue() == 1;
            }
            if (value instanceof Number) {
                return ((Number) value).intValue() == 1;
            }
        } catch (Exception e) {
            // Portal unavailable
        }
        return false;
    }
}
