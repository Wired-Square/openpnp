package org.openpnp.mcp;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.prefs.Preferences;

import org.openpnp.mcp.McpTool.Tier;

/**
 * MCP server settings, kept in the user's Java preferences rather than machine.xml so that the
 * token never travels with configuration backups.
 */
public final class McpSettings {
    public static final int DEFAULT_PORT = 8765;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private static final int TOKEN_BYTES = 32;
    private static final String ENABLED = "enabled";
    private static final String PORT = "port";
    private static final String TOKEN = "token";
    private static final String ALLOW_WRITE = "allowWrite";

    private final Preferences preferences;

    public McpSettings(Preferences preferences) {
        this.preferences = preferences;
    }

    public static McpSettings forUser() {
        return new McpSettings(Preferences.userNodeForPackage(McpSettings.class));
    }

    public boolean isEnabled() {
        return preferences.getBoolean(ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        preferences.putBoolean(ENABLED, enabled);
    }

    public int getPort() {
        return preferences.getInt(PORT, DEFAULT_PORT);
    }

    public void setPort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                    String.format("Port must be between %d and %d", MIN_PORT, MAX_PORT));
        }
        preferences.putInt(PORT, port);
    }

    /** Creates the token on first use. */
    public String getToken() {
        String token = preferences.get(TOKEN, null);
        if (token == null || token.isBlank()) {
            token = regenerateToken();
        }
        return token;
    }

    public String regenerateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        preferences.put(TOKEN, token);
        return token;
    }

    public boolean isAllowWrite() {
        return preferences.getBoolean(ALLOW_WRITE, false);
    }

    public void setAllowWrite(boolean allowWrite) {
        preferences.putBoolean(ALLOW_WRITE, allowWrite);
    }

    public boolean isTierEnabled(Tier tier) {
        switch (tier) {
            case READ:
            case SUGGEST:
                return true;
            case WRITE:
                return isAllowWrite();
            default:
                throw new IllegalArgumentException("Unknown tier " + tier);
        }
    }
}
