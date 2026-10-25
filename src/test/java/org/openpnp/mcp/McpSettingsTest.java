package org.openpnp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.prefs.Preferences;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.mcp.McpTool.Tier;

public class McpSettingsTest {
    private static final int TOKEN_LENGTH = 43;

    private Preferences node;
    private McpSettings settings;

    @BeforeEach
    public void setUp() {
        node = Preferences.userRoot().node("openpnp-test/mcp-" + UUID.randomUUID());
        settings = new McpSettings(node);
    }

    @AfterEach
    public void tearDown() throws Exception {
        node.removeNode();
    }

    @Test
    public void defaultsAreOffAndReadOnly() {
        assertFalse(settings.isEnabled());
        assertEquals(McpSettings.DEFAULT_PORT, settings.getPort());
        assertTrue(settings.isTierEnabled(Tier.READ));
        assertTrue(settings.isTierEnabled(Tier.SUGGEST));
        assertFalse(settings.isTierEnabled(Tier.WRITE));
    }

    @Test
    public void allowWriteEnablesTheWriteTier() {
        settings.setAllowWrite(true);

        assertTrue(settings.isTierEnabled(Tier.WRITE));
    }

    @Test
    public void theTokenIsCreatedOnceAndKept() {
        String token = settings.getToken();

        assertEquals(TOKEN_LENGTH, token.length());
        assertEquals(token, settings.getToken());
        assertEquals(token, new McpSettings(node).getToken());
    }

    @Test
    public void aBlankTokenIsReplaced() {
        node.put("token", " ");

        assertEquals(TOKEN_LENGTH, settings.getToken().length());
    }

    @Test
    public void regeneratingReplacesTheToken() {
        String token = settings.getToken();

        String regenerated = settings.regenerateToken();

        assertNotEquals(token, regenerated);
        assertEquals(regenerated, settings.getToken());
    }

    @Test
    public void portsOutsideTheUnprivilegedRangeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> settings.setPort(80));
        assertThrows(IllegalArgumentException.class,
                () -> settings.setPort(McpSettings.MAX_PORT + 1));
        settings.setPort(McpSettings.MIN_PORT);
        assertEquals(McpSettings.MIN_PORT, settings.getPort());
    }
}
