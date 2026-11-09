package org.openpnp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.MaxRotation;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PartSizeCheckMethod;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PreRotateUsage;
import org.openpnp.model.AbstractVisionSettings;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.common.io.Files;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SetBottomVisionSettingsToolTest {
    private static final double EPSILON = 1e-9;
    private static final String PART_ID = "R0805-1K";
    private static final String OTHER_PART_ID = "R0805-2K";
    private static final String SETTINGS_ID = "BVS_OWN";
    private static final double BODY_WIDTH_MM = 2;
    private static final double BODY_HEIGHT_MM = 1.25;

    private Configuration configuration;
    private SetBottomVisionSettingsTool tool;
    private Part part;
    private BottomVisionSettings settings;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        configuration = Configuration.get();
        tool = new SetBottomVisionSettingsTool(() -> configuration);
        part = configuration.getPart(PART_ID);
        settings = new BottomVisionSettings(SETTINGS_ID);
        settings.setName(PART_ID);
        settings.setEnabled(true);
        configuration.addVisionSettings(settings);
        part.setBottomVisionSettings(settings);
    }

    private JsonObject call(String arguments) throws Exception {
        return tool.call(JsonParser.parseString(arguments).getAsJsonObject())
                .getStructuredContent();
    }

    private void assertRefused(String arguments) {
        assertThrows(IllegalArgumentException.class, () -> call(arguments));
    }

    private void giveThePackageABody() {
        Footprint footprint = part.getPackage().getFootprint();
        footprint.setUnits(LengthUnit.Millimeters);
        footprint.setBodyWidth(BODY_WIDTH_MM);
        footprint.setBodyHeight(BODY_HEIGHT_MM);
    }

    @Test
    public void setsOnlyTheGivenFieldsAndReportsBeforeAndAfter() throws Exception {
        giveThePackageABody();

        JsonObject result = call("{\"partId\":\"" + PART_ID + "\",\"enabled\":false,"
                + "\"preRotateUsage\":\"AlwaysOn\",\"maxRotation\":\"Full\","
                + "\"checkPartSizeMethod\":\"BodySize\",\"checkSizeTolerancePercent\":15}");

        assertFalse(settings.isEnabled());
        assertEquals(PreRotateUsage.AlwaysOn, settings.getPreRotateUsage());
        assertEquals(MaxRotation.Full, settings.getMaxRotation());
        assertEquals(PartSizeCheckMethod.BodySize, settings.getCheckPartSizeMethod());
        assertEquals(15, settings.getCheckSizeTolerancePercent());
        assertFalse(settings.isAsymmetric());
        JsonObject before = result.getAsJsonObject("before");
        JsonObject after = result.getAsJsonObject("after");
        assertTrue(before.get("enabled").getAsBoolean());
        assertEquals("Disabled", before.get("checkPartSizeMethod").getAsString());
        assertEquals("BodySize", after.get("checkPartSizeMethod").getAsString());
        assertEquals("AlwaysOn", after.get("preRotateUsage").getAsString());
        assertEquals(SETTINGS_ID, after.get("id").getAsString());
    }

    @Test
    public void namesTheSettingsWithoutThePart() throws Exception {
        call("{\"visionSettingsId\":\"" + SETTINGS_ID + "\",\"maxRotation\":\"Full\"}");

        assertEquals(MaxRotation.Full, settings.getMaxRotation());
    }

    @Test
    public void namesThePartAndItsOwnSettings() throws Exception {
        call("{\"partId\":\"" + PART_ID + "\",\"visionSettingsId\":\"" + SETTINGS_ID
                + "\",\"preRotateUsage\":\"AlwaysOff\"}");

        assertEquals(PreRotateUsage.AlwaysOff, settings.getPreRotateUsage());
    }

    @Test
    public void refusesSettingsSharedWithAnotherPart() {
        Part other = new Part(OTHER_PART_ID);
        other.setPackage(part.getPackage());
        configuration.addPart(other);
        other.setBottomVisionSettings(settings);

        assertRefused("{\"partId\":\"" + PART_ID + "\",\"maxRotation\":\"Full\"}");
        assertEquals(MaxRotation.Adjust, settings.getMaxRotation());
    }

    @Test
    public void refusesInheritedPackageAndStockSettings() {
        Package pkg = part.getPackage();
        part.setBottomVisionSettings(null);
        pkg.setBottomVisionSettings(settings);

        // The part only inherits its package's settings.
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"maxRotation\":\"Full\"}");
        assertRefused("{\"visionSettingsId\":\"" + SETTINGS_ID + "\",\"maxRotation\":\"Full\"}");
        assertRefused("{\"visionSettingsId\":\"" + AbstractVisionSettings.STOCK_BOTTOM_ID
                + "\",\"maxRotation\":\"Full\"}");
        assertEquals(MaxRotation.Adjust, settings.getMaxRotation());
    }

    @Test
    public void refusesSettingsThatBelongToAnotherPart() {
        Part other = new Part(OTHER_PART_ID);
        other.setPackage(part.getPackage());
        configuration.addPart(other);

        assertRefused("{\"partId\":\"" + OTHER_PART_ID + "\",\"visionSettingsId\":\""
                + SETTINGS_ID + "\",\"maxRotation\":\"Full\"}");
    }

    @Test
    public void aVisionOffsetNeedsAsymmetric() throws Exception {
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"visionOffsetX\":0.5}");

        JsonObject after = call("{\"partId\":\"" + PART_ID + "\",\"asymmetric\":true,"
                + "\"visionOffsetX\":0.5,\"visionOffsetY\":-0.2}").getAsJsonObject("after");
        JsonObject reported = after.getAsJsonObject("visionOffset");
        assertTrue(after.get("asymmetric").getAsBoolean());
        assertEquals(0.5, reported.get("x").getAsDouble(), EPSILON);
        assertEquals(-0.2, reported.get("y").getAsDouble(), EPSILON);
        assertEquals(0, reported.get("z").getAsDouble(), EPSILON);
        assertEquals(0, reported.get("rotation").getAsDouble(), EPSILON);
        Location offset = settings.getVisionOffset().convertToUnits(LengthUnit.Millimeters);
        assertTrue(settings.isAsymmetric());
        assertEquals(0.5, offset.getX(), EPSILON);
        assertEquals(-0.2, offset.getY(), EPSILON);

        call("{\"partId\":\"" + PART_ID + "\",\"visionOffsetY\":0.3}");
        offset = settings.getVisionOffset().convertToUnits(LengthUnit.Millimeters);
        assertEquals(0.5, offset.getX(), EPSILON);
        assertEquals(0.3, offset.getY(), EPSILON);

        assertRefused("{\"partId\":\"" + PART_ID + "\",\"asymmetric\":false,"
                + "\"visionOffsetX\":1}");
        call("{\"partId\":\"" + PART_ID + "\",\"asymmetric\":false}");
        assertFalse(settings.isAsymmetric());
        assertEquals(0, settings.getVisionOffset().getX(), EPSILON);
    }

    @Test
    public void refusesASizeCheckWithNothingToCompareTo() throws Exception {
        Footprint footprint = part.getPackage().getFootprint();
        footprint.setBodyWidth(0);
        footprint.setBodyHeight(0);
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"checkPartSizeMethod\":\"BodySize\"}");
        footprint.removeAllPads();
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"checkPartSizeMethod\":\"PadExtents\"}");
        assertEquals(PartSizeCheckMethod.Disabled, settings.getCheckPartSizeMethod());

        giveThePackageABody();
        call("{\"partId\":\"" + PART_ID + "\",\"checkPartSizeMethod\":\"BodySize\"}");
        assertEquals(PartSizeCheckMethod.BodySize, settings.getCheckPartSizeMethod());
    }

    @Test
    public void validatesEveryFieldBeforeChangingAny() {
        assertRefused("{\"partId\":\"" + PART_ID + "\"}");
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"maxRotation\":\"Sideways\"}");
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"checkSizeTolerancePercent\":0}");
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"checkSizeTolerancePercent\":101}");
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"checkSizeTolerancePercent\":12.5}");
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"enabled\":\"no\"}");
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"maxRotation\":\"Full\","
                + "\"visionOffsetX\":1}");
        assertRefused("{\"partId\":\"" + PART_ID + "\",\"maxRotation\":\"Full\","
                + "\"asymmetric\":true,\"visionOffsetX\":"
                + (SetPackageBodySizeTool.MAX_BODY_MM + 1) + "}");
        assertFalse(settings.isAsymmetric());
        assertEquals(MaxRotation.Adjust, settings.getMaxRotation());
        assertTrue(settings.isEnabled());
    }
}
