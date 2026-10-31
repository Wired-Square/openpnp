package org.openpnp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.feeder.ReferenceRotatedTrayFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.mcp.MotionConfirmer;
import org.openpnp.mcp.MotionTour;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.OpenCvUtils;

import com.google.common.io.Files;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CheckJobPicksToolTest {
    private static final double EPSILON = 1e-3;
    private static final double TRAY_PITCH_MM = 5;

    private Configuration configuration;
    private Machine machine;
    private Job job;
    private ReferenceTrayFeeder near;
    private ReferenceTrayFeeder middle;
    private ReferenceTrayFeeder far;
    private File snapshots;
    private boolean jobActive;

    /** Allows or refuses, remembering what it was asked, and can stop a tour at a step. */
    private static class Operator implements MotionConfirmer {
        final AtomicReference<String> asked = new AtomicReference<>();
        final boolean allow;
        final int stopAtStep;
        boolean tourClosed;

        Operator(boolean allow, int stopAtStep) {
            this.allow = allow;
            this.stopAtStep = stopAtStep;
        }

        @Override
        public boolean confirm(String description) {
            asked.set(description);
            return allow;
        }

        @Override
        public MotionTour startTour(String title) {
            return new MotionTour() {
                @Override
                public boolean beforeStep(int index, int count, String step) {
                    return index != stopAtStep;
                }

                @Override
                public void close() {
                    tourClosed = true;
                }
            };
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        configuration = Configuration.get();
        configuration.load();
        machine = configuration.getMachine();
        snapshots = Snapshots.directory(configuration);

        Package pkg = new Package("C_0603_1608Metric");
        pkg.getFootprint().setBodyWidth(1.6);
        pkg.getFootprint().setBodyHeight(0.8);
        configuration.addPackage(pkg);
        Part part = new Part("C1-part");
        part.setPackage(pkg);
        configuration.addPart(part);
        Part other = new Part("not-in-job");
        other.setPackage(pkg);
        configuration.addPart(other);

        far = tray(part, 90, 90);
        near = tray(part, 10, 10);
        middle = tray(part, 40, 20);
        tray(other, 20, 10);
        tray(part, 15, 10).setEnabled(false);

        Board board = new Board();
        Placement placement = new Placement("C1");
        placement.setPart(part);
        board.addPlacement(placement);
        job = new Job();
        job.addBoardOrPanelLocation(new BoardLocation(board));
    }

    /**
     * Every test runs in one JVM: a machine left enabled keeps its camera threads and frames,
     * and enough of them exhaust the heap for later tests.
     */
    @AfterEach
    public void tearDown() throws Exception {
        machine.setEnabled(false);
        for (Camera camera : machine.getDefaultHead().getCameras()) {
            camera.close();
        }
        for (Camera camera : machine.getCameras()) {
            camera.close();
        }
    }

    private ReferenceTrayFeeder tray(Part part, double x, double y) throws Exception {
        ReferenceTrayFeeder feeder = new ReferenceTrayFeeder();
        feeder.setPart(part);
        feeder.setLocation(new Location(LengthUnit.Millimeters, x, y, 0, 0));
        feeder.setOffsets(new Location(LengthUnit.Millimeters, TRAY_PITCH_MM, 0, 0, 0));
        feeder.setTrayCountX(3);
        feeder.setTrayCountY(1);
        feeder.setEnabled(true);
        machine.addFeeder(feeder);
        return feeder;
    }

    private void enableAndHome() throws Exception {
        machine.setEnabled(true);
        machine.submit(() -> {
            machine.home();
            return null;
        }).get();
    }

    private McpToolResult call(MotionConfirmer operator, String arguments) throws Exception {
        return new CheckJobPicksTool(() -> configuration, () -> job, () -> jobActive, operator)
                .call(JsonParser.parseString(arguments).getAsJsonObject());
    }

    private static List<String> feederIds(JsonArray picks) {
        return picks.asList().stream()
                .map(pick -> pick.getAsJsonObject().get("feederId").getAsString()).toList();
    }

    private String[] snapshotNames() {
        String[] names = snapshots.list();
        return names == null ? new String[0] : names;
    }

    @Test
    public void theTourVisitsTheJobsFeedersNearestFirstAndOnlyLooks() throws Exception {
        enableAndHome();
        Operator operator = new Operator(true, -1);

        McpToolResult result = call(operator, "{\"images\":\"all\"}");

        assertFalse(result.isError(), result.toJson().toString());
        assertTrue(operator.asked.get().contains("over 3 feeders"), operator.asked.get());
        assertTrue(operator.asked.get().contains("Nothing is fed or picked"));
        JsonObject json = result.getStructuredContent();
        assertEquals(3, json.get("visited").getAsInt());
        assertEquals(List.of(near.getId(), middle.getId(), far.getId()),
                feederIds(json.getAsJsonArray("picks")));
        assertEquals(3, snapshotNames().length);
        for (ReferenceTrayFeeder feeder : List.of(near, middle, far)) {
            assertEquals(0, feeder.getFeedCount());
        }
        Location camera = machine.getDefaultHead().getDefaultCamera().getLocation()
                .convertToUnits(LengthUnit.Millimeters);
        assertEquals(90, camera.getX(), EPSILON);
        assertEquals(90, camera.getY(), EPSILON);
        assertTrue(operator.tourClosed);
        JsonObject first = json.getAsJsonArray("picks").get(0).getAsJsonObject();
        assertTrue(Set.of("ok", "marginal", "off", CheckJobPicksTool.STATUS_NOT_FOUND)
                .contains(first.get("status").getAsString()), first.toString());
        assertTrue(new File(snapshots, first.get("frame").getAsString()).isFile());
        // The JSON, then a caption and an image for each feeder.
        assertEquals(1 + 2 * 3, result.toJson().getAsJsonArray("content").size());
    }

    @Test
    public void onlyTheNamedFeedersAreFedAndTheirNextPartIsChecked() throws Exception {
        enableAndHome();
        Operator operator = new Operator(true, -1);
        middle.setFeedCount(1);

        JsonObject json = call(operator, "{\"feedFeederIds\":[\"" + middle.getId() + "\"]}")
                .getStructuredContent();

        assertTrue(operator.asked.get().contains("Feeds " + middle.getName() + " at its stop"),
                operator.asked.get());
        assertEquals(2, middle.getFeedCount());
        assertEquals(0, near.getFeedCount());
        JsonObject checked = json.getAsJsonArray("picks").get(1).getAsJsonObject();
        assertTrue(checked.get("fed").getAsBoolean());
        assertEquals(40 + TRAY_PITCH_MM,
                checked.getAsJsonObject("pickLocation").get("x").getAsDouble(), EPSILON);
    }

    @Test
    public void theNextTrayCellIsVisitedAndAnEmptyTraySkipped() throws Exception {
        enableAndHome();
        // One fed and picked: that cell is empty, the next one holds the part to check.
        middle.setFeedCount(1);
        far.setFeedCount(3);

        JsonObject json = call(new Operator(true, -1), "{\"feederIds\":[\"" + middle.getId()
                + "\",\"" + far.getId() + "\"]}").getStructuredContent();

        JsonArray picks = json.getAsJsonArray("picks");
        assertEquals(1, picks.size());
        assertEquals(40 + TRAY_PITCH_MM, picks.get(0).getAsJsonObject()
                .getAsJsonObject("pickLocation").get("x").getAsDouble(), EPSILON);
        assertEquals(far.getId(), json.getAsJsonArray("skipped").get(0).getAsJsonObject()
                .get("feederId").getAsString());
        assertEquals(1, middle.getFeedCount());
    }

    @Test
    public void trayFeedersReportTheirNextCell() {
        assertEquals(10, near.getNextPickLocation().getX(), EPSILON);
        near.setFeedCount(1);
        assertEquals(10 + TRAY_PITCH_MM, near.getNextPickLocation().getX(), EPSILON);
        near.setFeedOptions(ReferenceTrayFeeder.FeedOptions.SkipNext);
        assertEquals(10, near.getNextPickLocation().getX(), EPSILON);
        near.setFeedCount(3);
        assertNull(near.getNextPickLocation());
        near.setFeedOptions(ReferenceTrayFeeder.FeedOptions.Normal);
        assertNull(near.getNextPickLocation());

        ReferenceRotatedTrayFeeder rotated = new ReferenceRotatedTrayFeeder();
        rotated.setTrayCountCols(2);
        rotated.setTrayCountRows(1);
        assertEquals(rotated.getPickLocation(), rotated.getNextPickLocation());
        rotated.setFeedCount(2);
        assertNull(rotated.getNextPickLocation());
    }

    @Test
    public void aJobStartedDuringAPauseEndsTheTour() throws Exception {
        enableAndHome();
        MotionConfirmer operator = new Operator(true, -1) {
            @Override
            public MotionTour startTour(String title) {
                return new MotionTour() {
                    @Override
                    public boolean beforeStep(int index, int count, String step) {
                        jobActive = index == 1;
                        return true;
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };

        JsonObject json = call(operator, "{}").getStructuredContent();

        assertEquals(1, json.get("visited").getAsInt());
        assertTrue(json.get("aborted").getAsString().contains("job"));
    }

    @Test
    public void aFailedFeedEndsTheTourAtSafeZ() throws Exception {
        enableAndHome();
        ReferenceTrayFeeder jammed = new ReferenceTrayFeeder() {
            @Override
            public void feed(Nozzle nozzle) throws Exception {
                machine.getDefaultHead().getDefaultCamera()
                        .moveTo(new Location(LengthUnit.Millimeters, 12, 12, -5, 0));
                throw new Exception("jammed");
            }
        };
        jammed.setPart(near.getPart());
        jammed.setLocation(new Location(LengthUnit.Millimeters, 12, 12, 0, 0));
        jammed.setEnabled(true);
        machine.addFeeder(jammed);
        Operator operator = new Operator(true, -1);

        JsonObject json = call(operator, "{\"feedFeederIds\":[\"" + jammed.getId() + "\"]}")
                .getStructuredContent();

        assertTrue(operator.asked.get().contains("lowers the camera"), operator.asked.get());
        assertTrue(json.get("aborted").getAsString().contains("Feeding"), json.toString());
        assertEquals(json.get("visited").getAsInt(), json.getAsJsonArray("picks").size());
        assertTrue(json.get("visited").getAsInt() < 4);
        assertNotEquals(-5, machine.getDefaultHead().getDefaultCamera().getLocation()
                .convertToUnits(LengthUnit.Millimeters).getZ(), EPSILON);
    }

    @Test
    public void aRunningJobIsRefusedBeforeAsking() throws Exception {
        enableAndHome();
        jobActive = true;
        Operator operator = new Operator(true, -1);

        assertThrows(IllegalArgumentException.class, () -> call(operator, "{}"));
        assertNull(operator.asked.get());
    }

    @Test
    public void theOffsetAndTurnAreMeasuredInMachineCoordinates() throws Exception {
        double mmPerPixel = 0.02;
        Mat frame = new Mat(480, 640, CvType.CV_8UC3, new Scalar(200, 200, 200));
        // 0.5 mm right and 0.3 mm up of the expected body, turned 10° counter-clockwise: Y and
        // the turn are mirrored in the image.
        Point[] corners = new Point[4];
        new RotatedRect(new Point(320 + 0.5 / mmPerPixel, 240 - 0.3 / mmPerPixel),
                new Size(1.6 / mmPerPixel, 0.8 / mmPerPixel), -10).points(corners);
        Imgproc.fillPoly(frame, List.of(new MatOfPoint(corners)), new Scalar(40, 40, 40));
        Location pick = new Location(LengthUnit.Millimeters, 100, 50, 0, 0);
        CheckJobPicksTool.Stop stop = new CheckJobPicksTool.Stop(near, near.getPart(), false,
                pick, 1.6, 0.8, 0);
        CheckJobPicksTool.Shot shot = new CheckJobPicksTool.Shot(
                OpenCvUtils.toBufferedImage(frame), pick, pick,
                new Location(LengthUnit.Millimeters, mmPerPixel, mmPerPixel, 0, 0));
        JsonObject result = new JsonObject();

        CheckJobPicksTool.judge(stop, shot, PickCheck.DEFAULT_OK_FRACTION,
                PickCheck.DEFAULT_MARGINAL_FRACTION, result);

        JsonObject offset = result.getAsJsonObject("offset");
        assertEquals(0.5, offset.get("x").getAsDouble(), 2 * mmPerPixel);
        assertEquals(0.3, offset.get("y").getAsDouble(), 2 * mmPerPixel);
        assertEquals(10, offset.get("rotation").getAsDouble(), 1);
        assertEquals("off", result.get("status").getAsString());
        assertEquals(1.6, result.getAsJsonObject("foundBody").get("longSide").getAsDouble(),
                2 * mmPerPixel);
    }

    @Test
    public void aRefusedTourDoesNotMove() throws Exception {
        enableAndHome();
        Camera camera = machine.getDefaultHead().getDefaultCamera();
        Location before = camera.getLocation();

        McpToolResult result = call(new Operator(false, -1), "{}");

        assertTrue(result.isError());
        assertEquals(before, camera.getLocation());
        assertEquals(0, snapshotNames().length);
    }

    @Test
    public void theOperatorCanStopBetweenFeeders() throws Exception {
        enableAndHome();
        Operator operator = new Operator(true, 1);

        JsonObject json = call(operator, "{\"images\":\"none\"}").getStructuredContent();

        assertEquals(1, json.get("visited").getAsInt());
        assertEquals(3, json.get("planned").getAsInt());
        assertTrue(json.get("stoppedByOperator").getAsBoolean());
        assertTrue(operator.tourClosed);
    }

    @Test
    public void aPackageWithoutABodySizeIsVisitedButNotJudged() throws Exception {
        enableAndHome();
        near.getPart().getPackage().getFootprint().setBodyWidth(0);

        JsonObject pick = call(new Operator(true, -1), "{\"feederIds\":[\"" + near.getId()
                + "\"]}").getStructuredContent().getAsJsonArray("picks").get(0)
                .getAsJsonObject();

        assertEquals(CheckJobPicksTool.STATUS_NO_SIZE, pick.get("status").getAsString());
        assertNull(pick.get("offset"));
    }

    @Test
    public void badRequestsAreRefusedBeforeAsking() throws Exception {
        Operator operator = new Operator(true, -1);
        assertThrows(IllegalArgumentException.class, () -> call(operator, "{}"));
        enableAndHome();
        assertThrows(IllegalArgumentException.class,
                () -> call(operator, "{\"feedFeederIds\":[\"nope\"]}"));
        assertThrows(IllegalArgumentException.class,
                () -> call(operator, "{\"feederIds\":[\"nope\"]}"));
        assertThrows(IllegalArgumentException.class,
                () -> call(operator, "{\"okFraction\":0.5,\"marginalFraction\":0.2}"));
        assertThrows(IllegalArgumentException.class, () -> call(operator, "{\"images\":\"x\"}"));
        job = null;
        assertThrows(IllegalArgumentException.class, () -> call(operator, "{}"));
        assertNull(operator.asked.get());
    }

    @Test
    public void theRouteGoesToTheNearestRemainingFeeder() {
        Location origin = new Location(LengthUnit.Millimeters);
        List<Location> stops = List.of(
                new Location(LengthUnit.Millimeters, 100, 0, 0, 0),
                new Location(LengthUnit.Millimeters, 10, 0, 0, 0),
                new Location(LengthUnit.Millimeters, 50, 0, 0, 0));

        assertEquals(List.of(stops.get(1), stops.get(2), stops.get(0)),
                CheckJobPicksTool.nearestNeighbourRoute(origin, stops, stop -> stop));
    }

    @Test
    public void theTightestNozzleTipToleranceApplies() {
        Package pkg = new Package("tips");
        assertEquals(0, CheckJobPicksTool.maxPickTolerance(pkg), EPSILON);
        for (double mm : new double[] {0.8, 0.3, 0}) {
            ReferenceNozzleTip tip = new ReferenceNozzleTip();
            tip.setMaxPickTolerance(new Length(mm, LengthUnit.Millimeters));
            pkg.addCompatibleNozzleTip(tip);
        }

        assertEquals(0.3, CheckJobPicksTool.maxPickTolerance(pkg), EPSILON);
    }
}
