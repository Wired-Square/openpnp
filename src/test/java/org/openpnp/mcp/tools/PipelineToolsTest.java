package org.openpnp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceLoosePartFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.vision.pipeline.stages.ActuatorWrite;
import org.openpnp.vision.pipeline.stages.FilterContours;
import org.openpnp.vision.pipeline.stages.Threshold;

import com.google.common.io.Files;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PipelineToolsTest {
    private static final double EPSILON = 1e-9;
    private static final String FILTER_STAGE = "filtered_contours";

    private Machine machine;
    private ReferenceLoosePartFeeder feeder;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        machine = Configuration.get().getMachine();
        feeder = new ReferenceLoosePartFeeder();
        feeder.setLocation(new Location(LengthUnit.Millimeters, 50, 60, 0, 0));
        machine.addFeeder(feeder);
    }

    private McpToolResult call(McpTool tool, String arguments) throws Exception {
        return tool.call(JsonParser.parseString(arguments).getAsJsonObject());
    }

    private String feederArgs(String extra) {
        return "{\"feederId\":\"" + feeder.getId() + "\"" + extra + "}";
    }

    private JsonObject stage(JsonArray stages, String name) {
        for (int i = 0; i < stages.size(); i++) {
            JsonObject stage = stages.get(i).getAsJsonObject();
            if (name.equals(stage.get("name").getAsString())) {
                return stage;
            }
        }
        throw new AssertionError("No stage " + name);
    }

    @Test
    public void getPipelineListsStagesAndProperties() throws Exception {
        JsonArray stages = call(new GetPipelineTool(() -> machine), feederArgs(""))
                .getStructuredContent().getAsJsonArray("stages");

        assertEquals(feeder.getPipeline().getStages().size(), stages.size());
        JsonObject filter = stage(stages, FILTER_STAGE);
        assertEquals("FilterContours", filter.get("type").getAsString());
        assertTrue(filter.getAsJsonObject("properties").has("minArea"));
    }

    @Test
    public void aFeederWithoutAPipelineIsRefused() throws Exception {
        ReferenceTrayFeeder tray = new ReferenceTrayFeeder();
        machine.addFeeder(tray);

        assertThrows(IllegalArgumentException.class, () -> call(new GetPipelineTool(() -> machine),
                "{\"feederId\":\"" + tray.getId() + "\"}"));
    }

    @Test
    public void setPipelineStagePropertyConvertsAndSaves() throws Exception {
        McpTool tool = new SetPipelineStagePropertyTool(() -> machine);

        call(tool, feederArgs(",\"stage\":\"" + FILTER_STAGE
                + "\",\"property\":\"minArea\",\"value\":250000"));
        call(tool, feederArgs(",\"stage\":\"" + FILTER_STAGE
                + "\",\"property\":\"enabled\",\"value\":false"));

        FilterContours filter = (FilterContours) feeder.getPipeline().getStage(FILTER_STAGE);
        assertEquals(250000, filter.getMinArea(), EPSILON);
        assertFalse(filter.isEnabled());
    }

    @Test
    public void badPropertiesAreRefused() {
        McpTool tool = new SetPipelineStagePropertyTool(() -> machine);

        assertThrows(IllegalArgumentException.class, () -> call(tool, feederArgs(
                ",\"stage\":\"" + FILTER_STAGE + "\",\"property\":\"nope\",\"value\":1")));
        assertThrows(IllegalArgumentException.class, () -> call(tool, feederArgs(
                ",\"stage\":\"" + FILTER_STAGE + "\",\"property\":\"minArea\",\"value\":\"big\"")));
        assertThrows(IllegalArgumentException.class, () -> call(tool, feederArgs(
                ",\"stage\":\"nope\",\"property\":\"minArea\",\"value\":1")));
    }

    @Test
    public void numbersAreConvertedExactlyOrRefused() throws Exception {
        McpTool tool = new SetPipelineStagePropertyTool(() -> machine);

        assertThrows(IllegalArgumentException.class, () -> call(tool, feederArgs(
                ",\"stage\":\"highlights\",\"property\":\"threshold\",\"value\":3.7")));
        assertThrows(IllegalArgumentException.class, () -> call(tool, feederArgs(
                ",\"stage\":\"highlights\",\"property\":\"threshold\",\"value\":1e10")));
        assertThrows(IllegalArgumentException.class, () -> call(tool, feederArgs(
                ",\"stage\":\"" + FILTER_STAGE + "\",\"property\":\"minArea\",\"value\":\"NaN\"")));
        call(tool, feederArgs(
                ",\"stage\":\"highlights\",\"property\":\"threshold\",\"value\":210"));

        assertEquals(210, ((Threshold) feeder.getPipeline().getStage("highlights")).getThreshold());
    }

    @Test
    public void aPipelineThatDrivesAnActuatorIsRefused() throws Exception {
        feeder.getPipeline().add("actuate", new ActuatorWrite());

        assertThrows(IllegalArgumentException.class,
                () -> call(new RunPipelineTool(() -> machine), feederArgs("")));
        assertThrows(IllegalArgumentException.class,
                () -> call(new SetPipelineStagePropertyTool(() -> machine), feederArgs(
                        ",\"stage\":\"" + FILTER_STAGE + "\",\"property\":\"minArea\","
                                + "\"value\":1")));
    }

    @Test
    public void runPipelineUsesACopyAndReturnsStagesAndAnImage() throws Exception {
        double minArea = ((FilterContours) feeder.getPipeline().getStage(FILTER_STAGE)).getMinArea();

        McpToolResult result = call(new RunPipelineTool(() -> machine), feederArgs(
                ",\"overrides\":[{\"stage\":\"" + FILTER_STAGE
                        + "\",\"property\":\"minArea\",\"value\":1}]"));

        JsonObject json = result.getStructuredContent();
        assertEquals(feeder.getPipeline().getStages().size(),
                json.getAsJsonArray("stages").size());
        assertEquals(50, json.getAsJsonObject("feederLocation").get("x").getAsDouble(), EPSILON);
        assertEquals("image", result.toJson().getAsJsonArray("content").get(2).getAsJsonObject()
                .get("type").getAsString());
        assertEquals(minArea,
                ((FilterContours) feeder.getPipeline().getStage(FILTER_STAGE)).getMinArea(),
                EPSILON);
    }

    private void enableAndHome() throws Exception {
        machine.setEnabled(true);
        machine.submit(() -> {
            machine.home();
            return null;
        }).get();
    }

    @Test
    public void aRefusedMoveDoesNotMove() throws Exception {
        enableAndHome();
        Camera camera = machine.getDefaultHead().getDefaultCamera();
        Location before = camera.getLocation();
        AtomicReference<String> asked = new AtomicReference<>();

        McpToolResult result = call(new MoveCameraToFeederTool(() -> machine, description -> {
            asked.set(description);
            return false;
        }), feederArgs(""));

        assertTrue(result.isError());
        assertTrue(asked.get().contains("X 50.00, Y 60.00"), asked.get());
        assertEquals(before, camera.getLocation());
    }

    @Test
    public void aMoveIsCheckedAgainAfterTheOperatorAllowsIt() throws Exception {
        enableAndHome();
        Camera camera = machine.getDefaultHead().getDefaultCamera();
        Location before = camera.getLocation();

        McpToolResult result = call(new MoveCameraToFeederTool(() -> machine, description -> {
            machine.setEnabled(false);
            return true;
        }), feederArgs(""));

        assertTrue(result.isError());
        assertEquals(before, camera.getLocation());
    }

    @Test
    public void aSecondMoveIsRefusedWhileTheOperatorIsDeciding() throws Exception {
        enableAndHome();
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        MoveCameraToFeederTool tool = new MoveCameraToFeederTool(() -> machine, description -> {
            asked.countDown();
            answer.await();
            return false;
        });
        Thread first = new Thread(() -> {
            try {
                call(tool, feederArgs(""));
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        first.start();
        asked.await();

        assertThrows(IllegalArgumentException.class, () -> call(tool, feederArgs("")));
        answer.countDown();
        first.join();
    }

    @Test
    public void anAllowedMoveMovesTheCameraOverTheFeeder() throws Exception {
        enableAndHome();
        Camera camera = machine.getDefaultHead().getDefaultCamera();

        McpToolResult result = call(
                new MoveCameraToFeederTool(() -> machine, description -> true), feederArgs(""));

        assertFalse(result.isError());
        Location location = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
        assertEquals(50, location.getX(), 1e-3);
        assertEquals(60, location.getY(), 1e-3);
    }

    @Test
    public void aMoveNeedsAnEnabledHomedMachine() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> call(
                new MoveCameraToFeederTool(() -> machine, description -> true), feederArgs("")));
        machine.setEnabled(true);
        assertThrows(IllegalArgumentException.class, () -> call(
                new MoveCameraToFeederTool(() -> machine, description -> true), feederArgs("")));
    }
}
