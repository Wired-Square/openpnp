package org.openpnp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PreRotateUsage;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.MinAreaRect;
import org.openpnp.vision.pipeline.stages.Threshold;

import com.google.common.io.Files;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class BottomVisionToolsTest {
    private static final double EPSILON = 1e-3;
    private static final String PART_ID = "R0805-1K";
    private static final String SETTINGS_ID = "BVS_TEST";
    private static final String FRAME = "frame.png";
    /** Feature offset from the image centre, in pixels with OpenCV's Y down. */
    private static final int FEATURE_DX_PX = 50;
    private static final int FEATURE_DY_PX = -30;
    private static final int FEATURE_WIDTH_PX = 100;
    private static final int FEATURE_HEIGHT_PX = 60;
    private static final double TURN_DEGREES = 5;
    private static final double ANGLE_TOLERANCE = 0.5;
    private static final double VISION_OFFSET_X_MM = 0.5;
    private static final double VISION_OFFSET_Y_MM = 0.2;
    private static final String THRESHOLD_PIPELINE = "<cv-pipeline><stages>"
            + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.ImageCapture\" name=\"image\" "
            + "enabled=\"true\" default-light=\"true\" count=\"1\"/>"
            + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.ConvertColor\" name=\"gray\" "
            + "enabled=\"true\" conversion=\"Bgr2Gray\"/>"
            + "<cv-stage class=\"org.openpnp.vision.pipeline.stages.MinAreaRect\" name=\"results\" "
            + "enabled=\"true\" threshold-min=\"100\" threshold-max=\"255\"/>"
            + "</stages></cv-pipeline>";

    private Configuration configuration;
    private Camera camera;
    private BottomVisionSettings settings;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        configuration = Configuration.get();
        camera = VisionUtils.getBottomVisionCamera();
        settings = new BottomVisionSettings(SETTINGS_ID);
        settings.setName("Test");
        settings.setPipeline(new CvPipeline(THRESHOLD_PIPELINE));
        settings.setVisionOffset(new Location(LengthUnit.Millimeters, VISION_OFFSET_X_MM,
                VISION_OFFSET_Y_MM, 0, 0));
        configuration.addVisionSettings(settings);
    }

    private McpToolResult call(McpTool tool, String arguments) throws Exception {
        return tool.call(JsonParser.parseString(arguments).getAsJsonObject());
    }

    private String partArgs(String extra) {
        return "{\"partId\":\"" + PART_ID + "\",\"visionSettingsId\":\"" + SETTINGS_ID + "\""
                + extra + "}";
    }

    private void saveFrame(String name, int width, int height) throws Exception {
        saveFrame(name, width, height, 0);
    }

    private void saveFrame(String name, int width, int height, double degrees) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.rotate(Math.toRadians(degrees), width / 2.0 + FEATURE_DX_PX,
                height / 2.0 + FEATURE_DY_PX);
        g.fillRect(width / 2 + FEATURE_DX_PX - FEATURE_WIDTH_PX / 2,
                height / 2 + FEATURE_DY_PX - FEATURE_HEIGHT_PX / 2, FEATURE_WIDTH_PX,
                FEATURE_HEIGHT_PX);
        g.dispose();
        File directory = Snapshots.directory(configuration);
        directory.mkdirs();
        ImageIO.write(image, "png", new File(directory, name));
    }

    @Test
    public void getPipelineDescribesAPartsBottomVision() throws Exception {
        JsonObject json = call(new GetPipelineTool(() -> configuration), partArgs(""))
                .getStructuredContent();

        assertEquals(SETTINGS_ID, json.getAsJsonObject("visionSettings").get("id").getAsString());
        assertEquals(3, json.getAsJsonArray("stages").size());
    }

    @Test
    public void aPartAloneUsesItsEffectiveSettings() throws Exception {
        Part part = configuration.getPart(PART_ID);
        BottomVisionSettings effective =
                ReferenceBottomVision.getDefault().getInheritedVisionSettings(part);

        JsonObject json = call(new GetPipelineTool(() -> configuration),
                "{\"partId\":\"" + PART_ID + "\"}").getStructuredContent();

        assertEquals(effective.getId(),
                json.getAsJsonObject("visionSettings").get("id").getAsString());
    }

    @Test
    public void exactlyOneKindOfTargetIsAccepted() {
        McpTool tool = new GetPipelineTool(() -> configuration);

        assertThrows(IllegalArgumentException.class, () -> call(tool, "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> call(tool, "{\"feederId\":\"F1\",\"partId\":\"" + PART_ID + "\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> call(tool, "{\"visionSettingsId\":\"FDS_NOPE\"}"));
    }

    @Test
    public void thePipelineGetsTheAlignmentContext() throws Exception {
        settings.setPreRotateUsage(PreRotateUsage.AlwaysOn);
        BottomVisionTarget target = BottomVisionTarget.require(configuration,
                JsonParser.parseString(partArgs(",\"angle\":90")).getAsJsonObject());

        try (CvPipeline pipeline = settings.getPipeline().clone()) {
            target.preparePipeline(pipeline, camera);

            assertSame(camera, pipeline.getProperty("camera"));
            assertNotNull(pipeline.getProperty("footprint"));
            assertEquals(90.0, (Double) pipeline.getProperty("footprint.rotation"), EPSILON);
            assertEquals(90.0, (Double) pipeline.getProperty("MinAreaRect.expectedAngle"),
                    EPSILON);
            Location center = (Location) pipeline.getProperty("DetectRectlinearSymmetry.center");
            Location expected = camera.getLocation(configuration.getMachine().getDefaultHead()
                    .getDefaultNozzle()).add(new Location(LengthUnit.Millimeters, 0, 0, 1, 0));
            assertEquals(expected.getZ(), center.convertToUnits(LengthUnit.Millimeters).getZ(),
                    EPSILON);
            assertTrue(pipeline.getProperty("BlurGaussian.kernelSize") instanceof Length);
        }
    }

    @Test
    public void runPipelineAlignsASavedFrame() throws Exception {
        saveFrame(FRAME, camera.getWidth(), camera.getHeight());
        double mmPerPixel = camera.getUnitsPerPixel().convertToUnits(LengthUnit.Millimeters)
                .getX();

        JsonObject alignment = call(new RunPipelineTool(() -> configuration),
                partArgs(",\"imageFile\":\"" + FRAME + "\"")).getStructuredContent()
                .getAsJsonObject("alignment");

        // OpenCV puts a filled rectangle's centre half a pixel off its drawn centre.
        double tolerance = mmPerPixel;
        double featureX = FEATURE_DX_PX * mmPerPixel;
        double featureY = -FEATURE_DY_PX * mmPerPixel;
        assertEquals(featureX, alignment.get("featureXMm").getAsDouble(), tolerance);
        assertEquals(featureY, alignment.get("featureYMm").getAsDouble(), tolerance);
        assertEquals(0, alignment.get("angleOffset").getAsDouble(), EPSILON);
        assertEquals(alignment.get("featureXMm").getAsDouble() - VISION_OFFSET_X_MM,
                alignment.get("partXMm").getAsDouble(), EPSILON);
        assertEquals(alignment.get("featureYMm").getAsDouble() - VISION_OFFSET_Y_MM,
                alignment.get("partYMm").getAsDouble(), EPSILON);
    }

    @Test
    public void theVisionOffsetTurnsWithThePart() throws Exception {
        settings.setPreRotateUsage(PreRotateUsage.AlwaysOn);
        saveFrame(FRAME, camera.getWidth(), camera.getHeight());

        JsonObject alignment = call(new RunPipelineTool(() -> configuration),
                partArgs(",\"angle\":90,\"imageFile\":\"" + FRAME + "\""))
                .getStructuredContent().getAsJsonObject("alignment");

        assertEquals(0, alignment.get("angleOffset").getAsDouble(), EPSILON);
        assertEquals(alignment.get("featureXMm").getAsDouble() + VISION_OFFSET_Y_MM,
                alignment.get("partXMm").getAsDouble(), EPSILON);
        assertEquals(alignment.get("featureYMm").getAsDouble() - VISION_OFFSET_X_MM,
                alignment.get("partYMm").getAsDouble(), EPSILON);
    }

    @Test
    public void postRotateAlignsOnlyAtZero() {
        assertThrows(IllegalArgumentException.class,
                () -> call(new RunPipelineTool(() -> configuration), partArgs(",\"angle\":90")));
    }

    private JsonObject alignTurnedFrame() throws Exception {
        saveFrame(FRAME, camera.getWidth(), camera.getHeight(), TURN_DEGREES);
        JsonObject alignment = call(new RunPipelineTool(() -> configuration),
                partArgs(",\"imageFile\":\"" + FRAME + "\"")).getStructuredContent()
                .getAsJsonObject("alignment");
        assertEquals(TURN_DEGREES, Math.abs(alignment.get("angleOffset").getAsDouble()),
                ANGLE_TOLERANCE);
        return alignment;
    }

    @Test
    public void postRotateTurnsTheVisionOffsetByTheAngleOffset() throws Exception {
        JsonObject alignment = alignTurnedFrame();

        Location expected = feature(alignment).subtract(visionOffset()
                .rotateXy(alignment.get("angleOffset").getAsDouble()));
        assertEquals(expected.getX(), alignment.get("partXMm").getAsDouble(), EPSILON);
        assertEquals(expected.getY(), alignment.get("partYMm").getAsDouble(), EPSILON);
    }

    @Test
    public void preRotateSwingsTheFeatureBackByTheAngleOffset() throws Exception {
        settings.setPreRotateUsage(PreRotateUsage.AlwaysOn);

        JsonObject alignment = alignTurnedFrame();

        Location expected = feature(alignment)
                .rotateXy(-alignment.get("angleOffset").getAsDouble())
                .subtract(visionOffset());
        assertEquals(expected.getX(), alignment.get("partXMm").getAsDouble(), EPSILON);
        assertEquals(expected.getY(), alignment.get("partYMm").getAsDouble(), EPSILON);
        assertTrue(alignment.get("firstPassAccepted").getAsBoolean());
    }

    @Test
    public void theLegacyResultStageNameIsRead() throws Exception {
        settings.getPipeline().getStage("results").setName("result");
        saveFrame(FRAME, camera.getWidth(), camera.getHeight());

        JsonObject alignment = call(new RunPipelineTool(() -> configuration),
                partArgs(",\"imageFile\":\"" + FRAME + "\"")).getStructuredContent()
                .getAsJsonObject("alignment");

        assertFalse(alignment.has("error"), alignment.toString());
    }

    private static Location feature(JsonObject alignment) {
        return new Location(LengthUnit.Millimeters, alignment.get("featureXMm").getAsDouble(),
                alignment.get("featureYMm").getAsDouble(), 0, 0);
    }

    private static Location visionOffset() {
        return new Location(LengthUnit.Millimeters, VISION_OFFSET_X_MM, VISION_OFFSET_Y_MM, 0, 0);
    }

    @Test
    public void anEnabledMachineRunsThePipelineOnItsThread() throws Exception {
        Machine machine = configuration.getMachine();
        machine.setEnabled(true);
        saveFrame(FRAME, camera.getWidth(), camera.getHeight());

        JsonObject alignment = call(new RunPipelineTool(() -> configuration),
                partArgs(",\"imageFile\":\"" + FRAME + "\"")).getStructuredContent()
                .getAsJsonObject("alignment");

        assertFalse(alignment.has("error"), alignment.toString());
        assertThrows(IllegalArgumentException.class,
                () -> call(new RunPipelineTool(() -> configuration), partArgs(",\"angle\":90")));
        call(new SaveCameraFrameTool(() -> configuration),
                "{\"camera\":\"" + camera.getName() + "\",\"file\":\"live.png\"}");
        assertTrue(new File(Snapshots.directory(configuration), "live.png").isFile());
    }

    @Test
    public void theLastCapturedFrameCanBeRunAndKept() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> call(
                new RunPipelineTool(() -> configuration), partArgs(",\"lastCapture\":true")));
        saveFrame(FRAME, camera.getWidth(), camera.getHeight());
        settings.getPipeline().setLastCapturedImage(Snapshots.read(configuration, FRAME));

        JsonObject alignment = call(new RunPipelineTool(() -> configuration),
                partArgs(",\"lastCapture\":true,\"saveImageAs\":\"kept.png\""))
                .getStructuredContent().getAsJsonObject("alignment");

        assertFalse(alignment.has("error"), alignment.toString());
        assertTrue(new File(Snapshots.directory(configuration), "kept.png").isFile());
        assertThrows(IllegalArgumentException.class, () -> call(
                new RunPipelineTool(() -> configuration),
                partArgs(",\"lastCapture\":true,\"imageFile\":\"" + FRAME + "\"")));
    }

    @Test
    public void freezePipelineFrameMakesTheFrameTheLastCapture() throws Exception {
        saveFrame(FRAME, camera.getWidth(), camera.getHeight());

        JsonObject json = call(new FreezePipelineFrameTool(() -> configuration),
                partArgs(",\"imageFile\":\"" + FRAME + "\"")).getStructuredContent();

        assertFalse(json.get("shownInEditor").getAsBoolean());
        assertEquals(camera.getWidth(), settings.getPipeline().getLastCapturedImage().getWidth());
        JsonObject alignment = call(new RunPipelineTool(() -> configuration),
                partArgs(",\"lastCapture\":true")).getStructuredContent()
                .getAsJsonObject("alignment");
        assertFalse(alignment.has("error"), alignment.toString());
    }

    @Test
    public void noPipelineEditorsAreListedWhenNoneAreOpen() throws Exception {
        JsonObject json = call(new GetPipelineEditorsTool(() -> configuration), "{}")
                .getStructuredContent();

        assertEquals(0, json.getAsJsonArray("editors").size());
    }

    private String settingsArgs(String extra) {
        return "{\"visionSettingsId\":\"" + SETTINGS_ID + "\"" + extra + "}";
    }

    private List<String> stageNames() {
        return settings.getPipeline().getStages().stream().map(CvStage::getName).toList();
    }

    @Test
    public void stagesCanBeAddedMovedAndRemoved() throws Exception {
        call(new AddPipelineStageTool(() -> configuration), settingsArgs(
                ",\"type\":\"Threshold\",\"name\":\"bright\",\"before\":\"results\","
                        + "\"properties\":{\"threshold\":240}"));

        assertEquals(List.of("image", "gray", "bright", "results"), stageNames());
        assertEquals(240, ((Threshold) settings.getPipeline().getStage("bright")).getThreshold());

        call(new MovePipelineStageTool(() -> configuration),
                settingsArgs(",\"stage\":\"bright\",\"before\":\"gray\""));
        assertEquals(List.of("image", "bright", "gray", "results"), stageNames());
        call(new MovePipelineStageTool(() -> configuration),
                settingsArgs(",\"stage\":\"bright\""));
        assertEquals(List.of("image", "gray", "results", "bright"), stageNames());

        call(new RemovePipelineStageTool(() -> configuration),
                settingsArgs(",\"stage\":\"bright\""));
        assertEquals(List.of("image", "gray", "results"), stageNames());
    }

    @Test
    public void badStageEditsLeaveThePipelineAlone() {
        McpTool add = new AddPipelineStageTool(() -> configuration);

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> call(add, settingsArgs(",\"type\":\"Nope\",\"name\":\"x\"")));
        assertTrue(unknown.getMessage().contains("FindContours"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> call(add, settingsArgs(",\"type\":\"ActuatorWrite\",\"name\":\"x\"")));
        assertThrows(IllegalArgumentException.class,
                () -> call(add, settingsArgs(",\"type\":\"Threshold\",\"name\":\"gray\"")));
        assertThrows(IllegalArgumentException.class, () -> call(add, settingsArgs(
                ",\"type\":\"Threshold\",\"name\":\"x\",\"properties\":{\"nope\":1}")));
        assertThrows(IllegalArgumentException.class, () -> call(
                new MovePipelineStageTool(() -> configuration),
                settingsArgs(",\"stage\":\"gray\",\"before\":\"nope\"")));

        assertEquals(List.of("image", "gray", "results"), stageNames());
    }

    @Test
    public void setFeederLocationChangesOnlyTheGivenAxes() throws Exception {
        ReferenceTrayFeeder tray = new ReferenceTrayFeeder();
        tray.setLocation(new Location(LengthUnit.Millimeters, 10, 20, 4, 90));
        configuration.getMachine().addFeeder(tray);
        McpTool tool = new SetFeederLocationTool(configuration::getMachine);

        call(tool, "{\"feederId\":\"" + tray.getId() + "\",\"y\":23.6}");

        Location location = tray.getLocation();
        assertEquals(10, location.getX(), EPSILON);
        assertEquals(23.6, location.getY(), EPSILON);
        assertEquals(4, location.getZ(), EPSILON);
        assertEquals(90, location.getRotation(), EPSILON);
        assertThrows(IllegalArgumentException.class,
                () -> call(tool, "{\"feederId\":\"" + tray.getId() + "\"}"));
    }

    @Test
    public void thePickDeviationIsMeasuredFromThePackagesPickPoint() throws Exception {
        saveFrame(FRAME, camera.getWidth(), camera.getHeight());
        String args = partArgs(",\"imageFile\":\"" + FRAME + "\"");
        JsonObject first = call(new RunPipelineTool(() -> configuration), args)
                .getStructuredContent().getAsJsonObject("alignment");

        call(new SetPackagePickOffsetTool(() -> configuration),
                "{\"packageId\":\"R0805\",\"x\":" + -first.get("partXMm").getAsDouble()
                        + ",\"y\":" + -first.get("partYMm").getAsDouble() + "}");
        JsonObject aligned = call(new RunPipelineTool(() -> configuration), args)
                .getStructuredContent().getAsJsonObject("alignment");

        assertEquals(first.get("partXMm").getAsDouble(),
                first.get("pickDeviationXMm").getAsDouble(), EPSILON);
        assertEquals(0, aligned.get("pickDeviationXMm").getAsDouble(), EPSILON);
        assertEquals(0, aligned.get("pickDeviationYMm").getAsDouble(), EPSILON);
        assertEquals(-first.get("partYMm").getAsDouble(), call(
                new ListPackagesTool(() -> configuration), "{\"id\":\"R0805\"}")
                .getStructuredContent().getAsJsonArray("packages").get(0).getAsJsonObject()
                .getAsJsonObject("pickOffset").get("y").getAsDouble(), EPSILON);
    }

    @Test
    public void aFrameOfTheWrongSizeIsRefused() throws Exception {
        saveFrame(FRAME, camera.getWidth() / 2, camera.getHeight());

        assertThrows(IllegalArgumentException.class, () -> call(
                new RunPipelineTool(() -> configuration),
                partArgs(",\"imageFile\":\"" + FRAME + "\"")));
    }

    @Test
    public void snapshotNamesCannotLeaveTheFolder() {
        McpTool tool = new RunPipelineTool(() -> configuration);

        for (String name : new String[] {"../machine.xml", "/etc/passwd", "..", "sub/frame.png",
                "missing.png"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> call(tool, partArgs(",\"imageFile\":\"" + name + "\"")), name);
        }
    }

    @Test
    public void aPartWithoutAHeightIsRefusedRatherThanFocused() throws Exception {
        configuration.getPart(PART_ID).setHeight(new Length(0, LengthUnit.Millimeters));

        assertThrows(IllegalArgumentException.class,
                () -> call(new RunPipelineTool(() -> configuration), partArgs("")));
    }

    @Test
    public void settingsAloneCanBeEditedButNotRun() throws Exception {
        String args = "{\"visionSettingsId\":\"" + SETTINGS_ID + "\"";

        call(new SetPipelineStagePropertyTool(() -> configuration),
                args + ",\"stage\":\"results\",\"property\":\"thresholdMin\",\"value\":120}");

        assertThrows(IllegalArgumentException.class,
                () -> call(new RunPipelineTool(() -> configuration), args + "}"));
        assertEquals(120,
                ((MinAreaRect) settings.getPipeline().getStage("results")).getThresholdMin());
    }

    @Test
    public void saveCameraFrameWritesTheFullFrame() throws Exception {
        McpTool tool = new SaveCameraFrameTool(() -> configuration);

        JsonObject json = call(tool, "{\"camera\":\"" + camera.getName()
                + "\",\"file\":\"" + FRAME + "\",\"light\":true}").getStructuredContent();

        BufferedImage saved = ImageIO.read(new File(Snapshots.directory(configuration), FRAME));
        assertEquals(camera.getWidth(), saved.getWidth());
        assertEquals(camera.getHeight(), saved.getHeight());
        assertTrue(json.has("nozzles"));
        assertThrows(IllegalArgumentException.class, () -> call(tool,
                "{\"camera\":\"" + camera.getName() + "\",\"file\":\"" + FRAME + "\"}"));
        assertThrows(IllegalArgumentException.class, () -> call(tool,
                "{\"camera\":\"" + camera.getName() + "\",\"file\":\"frame.jpg\"}"));
    }

    @Test
    public void saveCameraFrameNamesTheFileWhenAsked() throws Exception {
        JsonObject json = call(new SaveCameraFrameTool(() -> configuration),
                "{\"camera\":\"" + camera.getName() + "\"}").getStructuredContent();

        String name = json.get("file").getAsString();
        assertTrue(name.startsWith(camera.getName()) && name.endsWith(".png"), name);
        assertTrue(new File(Snapshots.directory(configuration), name).isFile());
        assertFalse(json.get("light").getAsBoolean());
    }
}
