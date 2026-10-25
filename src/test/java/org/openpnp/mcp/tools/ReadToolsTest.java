package org.openpnp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonProperties;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.mcp.McpTool;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.openpnp.spi.Machine;

import com.google.common.io.Files;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ReadToolsTest {
    private static final String CONFIG = "config/BasicJobTest/";
    private static final String PART_ID = "R-0805-10K";
    private static final String FEEDER_ID = "F1";
    private static final int SLOT_ADDRESS = 7;
    private static final double EPSILON = 1e-9;

    private Configuration configuration;

    @BeforeEach
    public void setUp() throws Exception {
        File directory = new File(Files.createTempDir(), ".openpnp");
        for (String file : new String[] {"machine.xml", "packages.xml", "parts.xml"}) {
            FileUtils.copyURLToFile(ClassLoader.getSystemResource(CONFIG + file),
                    new File(directory, file));
        }
        Configuration.initialize(directory);
        configuration = Configuration.get();
        configuration.load();
    }

    private static JsonObject call(McpTool tool, String arguments) throws Exception {
        return tool.call(JsonParser.parseString(arguments).getAsJsonObject())
                .getStructuredContent();
    }

    private JsonObject feeder(JsonArray feeders, String id) {
        for (int i = 0; i < feeders.size(); i++) {
            JsonObject feeder = feeders.get(i).getAsJsonObject();
            if (id.equals(feeder.get("id").getAsString())) {
                return feeder;
            }
        }
        throw new AssertionError("No feeder " + id);
    }

    @Test
    public void listFeedersReportsThePickLocationInMillimetres() throws Exception {
        ReferenceFeeder f1 = (ReferenceFeeder) configuration.getMachine().getFeeder(FEEDER_ID);
        f1.setLocation(new Location(LengthUnit.Inches, 1, 2, 0, 90));

        JsonObject f1Json = feeder(call(new ListFeedersTool(configuration::getMachine), "{}")
                .getAsJsonArray("feeders"), FEEDER_ID);

        assertEquals(PART_ID, f1Json.get("partId").getAsString());
        assertEquals("0805", f1Json.get("packageId").getAsString());
        JsonObject pick = f1Json.getAsJsonObject("pickLocation");
        assertEquals(25.4, pick.get("x").getAsDouble(), EPSILON);
        assertEquals(50.8, pick.get("y").getAsDouble(), EPSILON);
        assertEquals(90, pick.get("rotation").getAsDouble(), EPSILON);
        assertEquals(90, f1Json.getAsJsonObject("location").get("rotation").getAsDouble(),
                EPSILON);
    }

    @Test
    public void listFeedersComposesPhotonSlotOffsetAndCorrection() throws Exception {
        Machine machine = configuration.getMachine();
        PhotonFeeder photon = new PhotonFeeder();
        machine.addFeeder(photon);
        new PhotonProperties(machine).getFeederSlots().getSlot(SLOT_ADDRESS)
                .setLocation(new Location(LengthUnit.Millimeters, 100, 50, 4, 90));
        photon.setSlotAddress(SLOT_ADDRESS);
        photon.setOffset(new Location(LengthUnit.Millimeters, 1, 0, 0, 10));
        photon.getPickOffsetCorrection().setEnabled(true);
        photon.getPickOffsetCorrection()
                .addOffset(new Location(LengthUnit.Millimeters, 0.3, 0, 0, 0));

        JsonObject json = feeder(call(new ListFeedersTool(configuration::getMachine), "{}")
                .getAsJsonArray("feeders"), photon.getId());

        JsonObject pick = json.getAsJsonObject("pickLocation");
        assertEquals(100.3, pick.get("x").getAsDouble(), EPSILON);
        assertEquals(51, pick.get("y").getAsDouble(), EPSILON);
        assertEquals(100, pick.get("rotation").getAsDouble(), EPSILON);
        JsonObject slot = json.getAsJsonObject("slot");
        assertEquals(SLOT_ADDRESS, slot.get("address").getAsInt());
        assertEquals(90, slot.getAsJsonObject("location").get("rotation").getAsDouble(), EPSILON);
        assertEquals(10, json.getAsJsonObject("offset").get("rotation").getAsDouble(), EPSILON);
        JsonObject correction = json.getAsJsonObject("pickOffsetCorrection");
        assertTrue(correction.get("enabled").getAsBoolean());
        assertEquals(0.3, correction.getAsJsonObject("offset").get("x").getAsDouble(), EPSILON);
    }

    @Test
    public void listFeedersFiltersByPart() throws Exception {
        JsonArray feeders = call(new ListFeedersTool(configuration::getMachine),
                "{\"partId\":\"" + PART_ID + "\"}").getAsJsonArray("feeders");

        assertFalse(feeders.isEmpty());
        for (int i = 0; i < feeders.size(); i++) {
            assertEquals(PART_ID, feeders.get(i).getAsJsonObject().get("partId").getAsString());
        }
        assertTrue(call(new ListFeedersTool(configuration::getMachine), "{\"partId\":\"nope\"}")
                .getAsJsonArray("feeders").isEmpty());
    }

    @Test
    public void listPartsFiltersIgnoringCase() throws Exception {
        JsonArray parts = call(new ListPartsTool(() -> configuration), "{\"idContains\":\"r-0805\"}")
                .getAsJsonArray("parts");

        assertEquals(1, parts.size());
        JsonObject part = parts.get(0).getAsJsonObject();
        assertEquals(PART_ID, part.get("id").getAsString());
        assertEquals("0805", part.get("packageId").getAsString());
        assertEquals(0.8255, part.get("heightMm").getAsDouble(), EPSILON);
    }

    @Test
    public void listPackagesGivesPadsOnlyForOnePackage() throws Exception {
        ListPackagesTool tool = new ListPackagesTool(() -> configuration);

        JsonObject all = call(tool, "{}");
        JsonObject one = call(tool, "{\"id\":\"0805\"}");

        for (int i = 0; i < all.getAsJsonArray("packages").size(); i++) {
            JsonObject pkg = all.getAsJsonArray("packages").get(i).getAsJsonObject();
            assertFalse(pkg.has("footprint") && pkg.getAsJsonObject("footprint").has("pads"));
        }
        JsonObject footprint = one.getAsJsonArray("packages").get(0).getAsJsonObject()
                .getAsJsonObject("footprint");
        assertEquals(2, footprint.get("padCount").getAsInt());
        JsonObject pad1 = footprint.getAsJsonArray("pads").get(0).getAsJsonObject();
        assertEquals("1", pad1.get("name").getAsString());
        assertEquals(-0.95, pad1.get("x").getAsDouble(), EPSILON);
        assertEquals(90, pad1.get("rotation").getAsDouble(), EPSILON);
    }

    @Test
    public void listPackagesRejectsAnUnknownId() {
        ListPackagesTool tool = new ListPackagesTool(() -> configuration);

        assertThrows(IllegalArgumentException.class, () -> call(tool, "{\"id\":\"nope\"}"));
    }

    @Test
    public void getJobGivesBoardAndMachineLocations() throws Exception {
        Board board = new Board();
        Placement placement = new Placement("R1");
        placement.setPart(configuration.getPart(PART_ID));
        placement.setSide(Side.Top);
        placement.setLocation(new Location(LengthUnit.Millimeters, 10, 5, 0, 90));
        board.addPlacement(placement);
        BoardLocation boardLocation = new BoardLocation(board);
        boardLocation.setGlobalSide(Side.Top);
        boardLocation.setLocation(new Location(LengthUnit.Millimeters, 100, 200, 0, 0));
        Job job = new Job();
        job.addBoardOrPanelLocation(boardLocation);

        JsonObject result = call(new GetJobTool(() -> job), "{}");

        JsonObject r1 = result.getAsJsonArray("boards").get(0).getAsJsonObject()
                .getAsJsonArray("placements").get(0).getAsJsonObject();
        assertEquals("R1", r1.get("id").getAsString());
        assertEquals(PART_ID, r1.get("partId").getAsString());
        assertTrue(r1.get("enabled").getAsBoolean());
        assertFalse(r1.get("placed").getAsBoolean());
        JsonObject machine = r1.getAsJsonObject("machineLocation");
        assertEquals(110, machine.get("x").getAsDouble(), EPSILON);
        assertEquals(205, machine.get("y").getAsDouble(), EPSILON);
        assertEquals(90, machine.get("rotation").getAsDouble(), EPSILON);
    }

    @Test
    public void getJobRotatesPlacementsWithTheBoardAndReportsLiveEnabledState() throws Exception {
        Board board = new Board();
        Placement placement = new Placement("R1");
        placement.setPart(configuration.getPart(PART_ID));
        placement.setSide(Side.Top);
        placement.setLocation(new Location(LengthUnit.Millimeters, 10, 5, 0, 0));
        board.addPlacement(placement);
        BoardLocation boardLocation = new BoardLocation(board);
        boardLocation.setGlobalSide(Side.Top);
        boardLocation.setLocation(new Location(LengthUnit.Millimeters, 100, 200, 0, 90));
        Job job = new Job();
        job.addBoardOrPanelLocation(boardLocation);
        job.storeEnabledState(boardLocation, placement, false);
        placement.setEnabled(true);

        JsonObject r1 = call(new GetJobTool(() -> job), "{}").getAsJsonArray("boards").get(0)
                .getAsJsonObject().getAsJsonArray("placements").get(0).getAsJsonObject();

        assertTrue(r1.get("enabled").getAsBoolean());
        JsonObject machine = r1.getAsJsonObject("machineLocation");
        assertEquals(95, machine.get("x").getAsDouble(), EPSILON);
        assertEquals(210, machine.get("y").getAsDouble(), EPSILON);
        assertEquals(90, machine.get("rotation").getAsDouble(), EPSILON);
    }

    @Test
    public void getJobNeedsALoadedJob() {
        assertThrows(IllegalArgumentException.class, () -> call(new GetJobTool(() -> null), "{}"));
    }
}
