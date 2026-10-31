package org.openpnp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceLoosePartFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;

import com.google.common.io.Files;
import com.google.gson.JsonObject;

/**
 * Frames from the machine's top camera, cropped about the pick point. The expected positions
 * were checked by eye on the machine: the 0603 tray location was corrected to the pocket centre
 * found here.
 */
public class PickFramesTest {
    /** Top camera mm per pixel at the 0603 tray's Z 4 and the Photon's Z 4.7. */
    private static final double TRAY_MM_PER_PIXEL = 0.01114;
    private static final double PHOTON_MM_PER_PIXEL = 0.0110;
    private static final double MM = 0.03;
    private static final double DEGREES = 1.5;

    /** Feeders and parts need a configuration, though nothing reads from it here. */
    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
    }

    private static BufferedImage frame(String name) throws Exception {
        return ImageIO.read(ClassLoader.getSystemResource("mcp/pick-check/" + name));
    }

    private static PickDetector.Result detect(BufferedImage image, double mmPerPixel,
            double bodyLong, double bodyShort) {
        return PickDetector.detect(image, bodyLong / mmPerPixel, bodyShort / mmPerPixel,
                CheckJobPicksTool.NEAR_BODY_LENGTHS * bodyLong / mmPerPixel);
    }

    /** Machine X, Y offset in mm of a detection from the frame centre. */
    private static double[] offset(BufferedImage image, PickDetector.Detection found,
            double mmPerPixel) {
        return new double[] {(found.x - image.getWidth() / 2.0) * mmPerPixel,
                -(found.y - image.getHeight() / 2.0) * mmPerPixel};
    }

    @Test
    public void an0603InPaperTapeIsJudgedByItsPocket() throws Exception {
        BufferedImage image = frame("tray-0603.png");

        PickDetector.Result found = detect(image, TRAY_MM_PER_PIXEL, 1.6, 0.8);

        assertNotNull(found.pocket);
        double[] pocket = offset(image, found.pocket, TRAY_MM_PER_PIXEL);
        assertEquals(-0.18, pocket[0], MM);
        assertEquals(0.13, pocket[1], MM);
        assertEquals(2.07, found.pocket.longPx * TRAY_MM_PER_PIXEL, MM);
        assertEquals(1.15, found.pocket.shortPx * TRAY_MM_PER_PIXEL, MM);
    }

    @Test
    public void theCorrected0603TrayIsCentredOnItsPocketBesideAShadow() throws Exception {
        JsonObject result = judge("tray-0603-centred.png", TRAY_MM_PER_PIXEL,
                new ReferenceTrayFeeder(), 0, 1.6, 0.8);

        // The tray location was moved onto this pocket by hand.
        assertEquals("ok", result.get("status").getAsString(), result.toString());
        assertEquals(CheckJobPicksTool.MEASURED_BY_POCKET,
                result.get("measuredBy").getAsString());
        assertOffset(result, 0, -0.05);
        assertTrue(result.getAsJsonObject("part").get("fromFeatures").getAsBoolean());
        // As seen on the machine, the part sits about 0.1 mm left in its pocket.
        assertEquals(-0.1, result.getAsJsonObject("part").getAsJsonObject("inPocket").get("x")
                .getAsDouble(), MM);
    }

    @Test
    public void an0603sDarkBodyIsFoundFromItsTerminations() throws Exception {
        BufferedImage image = frame("tray-0603.png");

        PickDetector.Detection part = detect(image, TRAY_MM_PER_PIXEL, 1.6, 0.8).part;

        assertNotNull(part);
        assertTrue(part.fromFeatures);
        double[] at = offset(image, part, TRAY_MM_PER_PIXEL);
        assertEquals(-0.28, at[0], MM);
        assertEquals(0.12, at[1], MM);
        assertEquals(1.6, part.longPx * TRAY_MM_PER_PIXEL, 2 * MM);
        assertEquals(0.85, part.shortPx * TRAY_MM_PER_PIXEL, 2 * MM);
        assertEquals(0, PickCheck.fold(part.longAxisImageDegrees, 180), 2 * DEGREES);
    }

    @Test
    public void aBlackSoicOnBlackTapeIsFoundFromItsLeads() throws Exception {
        BufferedImage image = frame("photon-soic8.png");

        PickDetector.Result found = detect(image, PHOTON_MM_PER_PIXEL, 4.9, 3.9);

        assertNull(found.pocket);
        assertNotNull(found.part);
        assertTrue(found.part.fromFeatures);
        double[] at = offset(image, found.part, PHOTON_MM_PER_PIXEL);
        assertEquals(-0.10, at[0], MM);
        assertEquals(0.11, at[1], MM);
        // Across the leads (6.0 mm nominal) by along a row of four leads.
        assertEquals(5.9, found.part.longPx * PHOTON_MM_PER_PIXEL, 3 * MM);
        assertEquals(4.1, found.part.shortPx * PHOTON_MM_PER_PIXEL, 3 * MM);
        // The long side runs across the leads, a quarter turn from the body's 4.9 mm length.
        assertEquals(0, PickCheck.fold(found.part.longAxisImageDegrees - 90, 90), DEGREES);
    }

    /** Judges a frame as check_job_picks does, with the camera over the pick point. */
    private static JsonObject judge(String name, double mmPerPixel, Feeder feeder,
            double rotation, double width, double height) throws Exception {
        Part part = new Part("part");
        part.setPackage(new Package("package"));
        Location pick = new Location(LengthUnit.Millimeters, 100, 50, 4, rotation);
        if (feeder instanceof ReferenceTrayFeeder) {
            ((ReferenceTrayFeeder) feeder).setLocation(pick);
        }
        CheckJobPicksTool.Stop stop = new CheckJobPicksTool.Stop(feeder, part, false, pick,
                width, height, 0);
        CheckJobPicksTool.Shot shot = new CheckJobPicksTool.Shot(frame(name), pick, pick,
                new Location(LengthUnit.Millimeters, mmPerPixel, mmPerPixel, 0, 0));
        JsonObject result = new JsonObject();
        CheckJobPicksTool.judge(stop, shot, PickCheck.DEFAULT_OK_FRACTION,
                PickCheck.DEFAULT_MARGINAL_FRACTION, result);
        return result;
    }

    private static void assertOffset(JsonObject result, double x, double y) {
        JsonObject offset = result.getAsJsonObject("offset");
        assertEquals(x, offset.get("x").getAsDouble(), MM, result.toString());
        assertEquals(y, offset.get("y").getAsDouble(), MM, result.toString());
    }

    @Test
    public void aSixLeadSotInBlackTapeIsJudgedByItsLeads() throws Exception {
        JsonObject result = judge("tray-sot23-6.png", 0.01126, new ReferenceTrayFeeder(), 180,
                1.6, 2.9);

        assertEquals("ok", result.get("status").getAsString(), result.toString());
        assertEquals(CheckJobPicksTool.MEASURED_BY_FEATURES,
                result.get("measuredBy").getAsString());
        assertOffset(result, -0.07, -0.14);
        // A lead hull can be longest across the body.
        assertEquals("quarterTurn", result.get("rotationCheckedTo").getAsString());
    }

    @Test
    public void aSot23WithThreeLeadsIsJudgedByThem() throws Exception {
        JsonObject result = judge("tray-sot23.png", 0.01114, new ReferenceTrayFeeder(), 180,
                1.3, 2.9);

        assertEquals("ok", result.get("status").getAsString(), result.toString());
        assertOffset(result, 0.03, -0.06);
    }

    @Test
    public void an0402InPaperTapeIsJudgedByItsPocketWithThePartInside() throws Exception {
        JsonObject result = judge("tray-0402.png", 0.01100, new ReferenceTrayFeeder(), 0, 1.05,
                0.54);

        assertEquals(CheckJobPicksTool.MEASURED_BY_POCKET,
                result.get("measuredBy").getAsString(), result.toString());
        assertOffset(result, -0.07, 0.055);
        JsonObject inPocket = result.getAsJsonObject("part").getAsJsonObject("inPocket");
        assertEquals(0.03, inPocket.get("x").getAsDouble(), MM);
        assertEquals(-0.01, inPocket.get("y").getAsDouble(), MM);
        // A 1.17 × 0.67 mm pocket can only hold the 1.05 × 0.54 mm body one way.
        assertEquals("halfTurn", result.get("rotationCheckedTo").getAsString());
    }

    @Test
    public void anEmptyEmbossedPocketNextToTheMelfIsNotTakenForIt() throws Exception {
        JsonObject result = judge("tray-minimelf.png", 0.01114, new ReferenceTrayFeeder(), 180,
                3.3, 1.6);

        // The glass MELF is found from its end caps and highlights, about 0.3 mm left of the
        // pick (checked on the machine); the empty pocket beside it plays no part.
        assertEquals(CheckJobPicksTool.MEASURED_BY_FEATURES,
                result.get("measuredBy").getAsString(), result.toString());
        assertOffset(result, -0.31, -0.05);
        assertEquals(3.3, result.getAsJsonObject("part").get("longSide").getAsDouble(),
                3 * MM);
    }

    @Test
    public void anEmptyPhotonPocketIsNotJudgedByTheNextOne() throws Exception {
        JsonObject result = judge("photon-1206-empty.png", 0.01100,
                new ReferenceLoosePartFeeder(), 0, 3.2, 1.6);

        assertEquals(CheckJobPicksTool.STATUS_NOT_FOUND, result.get("status").getAsString(),
                result.toString());
        assertEquals(1.75, result.getAsJsonObject("farMatch").get("distance").getAsDouble(),
                0.1);
    }

    @Test
    public void aWashedOut0402PocketIsFoundWithItsOwnThreshold() throws Exception {
        JsonObject result = judge("photon-0402.png", 0.01108, new ReferenceTrayFeeder(), 0, 1.05,
                0.54);

        // Over the whole window the washed-out tape hides the pocket; around the pick point it
        // shows, 1.23 × 0.72 mm. The dark outline inside may be its floor, and the note says so.
        assertEquals(CheckJobPicksTool.MEASURED_BY_POCKET,
                result.get("measuredBy").getAsString(), result.toString());
        assertOffset(result, -0.12, -0.13);
        assertTrue(result.get("reason").getAsString().contains("pocket floor"));
    }

    @Test
    public void aBodyLargerThanTheViewIsSaidSo() throws Exception {
        JsonObject result = judge("tray-0603.png", 0.01114, new ReferenceTrayFeeder(), 90, 18,
                25.5);

        assertEquals(CheckJobPicksTool.STATUS_LARGER_THAN_VIEW,
                result.get("status").getAsString());
    }
}
