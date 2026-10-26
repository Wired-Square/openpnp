package org.openpnp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonFeederSlots;
import org.openpnp.machine.photon.PhotonProperties;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTubeFeeder;
import org.openpnp.mcp.McpTool;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.RotationSuggestion.Confidence;
import org.openpnp.spi.Machine;
import org.openpnp.util.Utils2D;

import com.google.common.io.Files;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class RotationToolsTest {
    private static final int ADDRESS = 41;
    private static final double EPSILON = 1e-9;

    private Machine machine;
    private ReferenceTrayFeeder tray;
    private boolean applyDirectly;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        machine = Configuration.get().getMachine();
        tray = new ReferenceTrayFeeder();
        tray.setLocation(new Location(LengthUnit.Millimeters, 10, 20, 3, 0));
        machine.addFeeder(tray);
    }

    private static JsonObject call(McpTool tool, String arguments) throws Exception {
        return tool.call(JsonParser.parseString(arguments).getAsJsonObject())
                .getStructuredContent();
    }

    private SuggestFeederRotationTool suggestTool() {
        return new SuggestFeederRotationTool(() -> machine, () -> applyDirectly);
    }

    private String suggestion(String feederId, double rotation) {
        return String.format("{\"feederId\":\"%s\",\"rotation\":%s,\"confidence\":\"High\","
                + "\"rationale\":\"TI Q1\"}", feederId, rotation);
    }

    @Test
    public void aSuggestionIsRecordedForReview() throws Exception {
        JsonObject result = call(suggestTool(), suggestion(tray.getId(), -90));

        assertFalse(result.get("applied").getAsBoolean());
        assertEquals(Confidence.High, tray.getRotationSuggestion().getConfidence());
        assertEquals(-90, tray.getRotationSuggestion().getRotation(), EPSILON);
        assertEquals("TI Q1", tray.getRotationSuggestion().getRationale());
        assertEquals(0, tray.getLocation().getRotation(), EPSILON);
        assertEquals(-90, result.getAsJsonObject("rotationSuggestion").get("rotation")
                .getAsDouble(), EPSILON);
    }

    @Test
    public void aSuggestionIsAppliedAtOnceWhenTheOperatorChoseThat() throws Exception {
        applyDirectly = true;

        JsonObject result = call(suggestTool(), suggestion(tray.getId(), -90));

        assertTrue(result.get("applied").getAsBoolean());
        assertNull(tray.getRotationSuggestion());
        assertEquals(-90, tray.getLocation().getRotation(), EPSILON);
    }

    @Test
    public void aFeederThatCannotApplyARotationIsRefused() throws Exception {
        ReferenceTubeFeeder tube = new ReferenceTubeFeeder();
        machine.addFeeder(tube);

        assertThrows(IllegalArgumentException.class,
                () -> call(suggestTool(), suggestion(tube.getId(), 0)));
        assertNull(tube.getRotationSuggestion());
    }

    @Test
    public void invalidArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> call(suggestTool(), suggestion("nope", 0)));
        assertThrows(IllegalArgumentException.class, () -> call(suggestTool(),
                "{\"feederId\":\"" + tray.getId() + "\",\"rotation\":0,"
                        + "\"confidence\":\"certain\",\"rationale\":\"x\"}"));
        assertThrows(IllegalArgumentException.class, () -> call(suggestTool(),
                "{\"feederId\":\"" + tray.getId() + "\",\"rotation\":0,"
                        + "\"confidence\":\"high\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> call(suggestTool(), suggestion(tray.getId(), 1e10)));
    }

    @Test
    public void applyAndDismissActOnThePendingSuggestion() throws Exception {
        call(suggestTool(), suggestion(tray.getId(), 180));
        call(new DismissFeederRotationSuggestionTool(() -> machine),
                "{\"feederId\":\"" + tray.getId() + "\"}");
        assertNull(tray.getRotationSuggestion());
        assertThrows(IllegalArgumentException.class,
                () -> call(new ApplyFeederRotationSuggestionTool(() -> machine),
                        "{\"feederId\":\"" + tray.getId() + "\"}"));

        call(suggestTool(), suggestion(tray.getId(), 180));
        call(new ApplyFeederRotationSuggestionTool(() -> machine),
                "{\"feederId\":\"" + tray.getId() + "\"}");

        assertNull(tray.getRotationSuggestion());
        assertEquals(180, tray.getLocation().getRotation(), EPSILON);
    }

    @Test
    public void anUnknownSlotIsRefusedWithoutBeingCreated() throws Exception {
        PhotonFeederSlots slots = new PhotonProperties(machine).getFeederSlots();

        assertThrows(IllegalArgumentException.class,
                () -> call(new SetPhotonSlotRotationTool(() -> machine),
                        "{\"address\":9999,\"rotation\":0}"));
        assertNull(slots.findSlot(9999));
    }

    @Test
    public void aSlotWithNoFeederNeedsAllowEmpty() throws Exception {
        new PhotonProperties(machine).getFeederSlots().getSlot(ADDRESS)
                .setLocation(new Location(LengthUnit.Millimeters, 170, 416, 4.7, 0));

        assertThrows(IllegalArgumentException.class,
                () -> call(new SetPhotonSlotRotationTool(() -> machine),
                        "{\"address\":" + ADDRESS + ",\"rotation\":-90}"));
        call(new SetPhotonSlotRotationTool(() -> machine),
                "{\"address\":" + ADDRESS + ",\"rotation\":-90,\"allowEmpty\":true}");

        assertEquals(-90, new PhotonProperties(machine).getFeederSlots().findSlot(ADDRESS)
                .getLocation().getRotation(), EPSILON);
    }

    @Test
    public void settingASlotRotationKeepsTheInsertedFeedersPick() throws Exception {
        PhotonFeeder photon = new PhotonFeeder();
        machine.addFeeder(photon);
        new PhotonProperties(machine).getFeederSlots().getSlot(ADDRESS)
                .setLocation(new Location(LengthUnit.Millimeters, 170, 416, 4.7, -90));
        photon.setSlotAddress(ADDRESS);
        photon.setOffset(new Location(LengthUnit.Millimeters));

        JsonObject result = call(new SetPhotonSlotRotationTool(() -> machine),
                "{\"address\":" + ADDRESS + ",\"rotation\":-90.0}");
        call(new SetPhotonSlotRotationTool(() -> machine),
                "{\"address\":" + ADDRESS + ",\"rotation\":90}");

        assertEquals(-90, result.getAsJsonObject("location").get("rotation").getAsDouble(),
                EPSILON);
        assertEquals(-180, photon.getOffset().getRotation(), EPSILON);
        assertEquals(-90, Utils2D.normalizeAngle180(photon.getPickLocation().getRotation()),
                EPSILON);
        assertThrows(IllegalArgumentException.class,
                () -> call(new SetPhotonSlotRotationTool(() -> machine),
                        "{\"address\":1.5,\"rotation\":0}"));
    }
}
