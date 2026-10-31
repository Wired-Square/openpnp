package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;
import org.openpnp.util.Cycles;
import org.openpnp.util.VisionUtils;
import org.simpleframework.xml.Serializer;

import com.google.common.io.Files;

public class PackagePickOffsetTest {
    private static final double EPSILON = 1e-6;
    private static final String PART_ID = "R0805-1K";
    private static final double OFFSET_Y_MM = -3.6;

    private Machine machine;
    private Part part;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        machine = Configuration.get().getMachine();
        part = Configuration.get().getPart(PART_ID);
        part.getPackage().setPickOffset(
                new Location(LengthUnit.Millimeters, 0, OFFSET_Y_MM, 0, 0));
    }

    @Test
    public void theNozzleLocationTurnsTheOffsetWithThePart() {
        Location nozzle = part.getPackage()
                .getNozzleLocation(new Location(LengthUnit.Millimeters, 100, 50, 4, 90));

        assertEquals(100 - OFFSET_Y_MM, nozzle.getX(), EPSILON);
        assertEquals(50, nozzle.getY(), EPSILON);
        assertEquals(4, nozzle.getZ(), EPSILON);
        assertEquals(90, nozzle.getRotation(), EPSILON);
    }

    @Test
    public void thePickOffsetIsSavedWithThePackage() throws Exception {
        Serializer serializer = Configuration.createSerializer();
        StringWriter xml = new StringWriter();
        serializer.write(part.getPackage(), xml);

        Package loaded = serializer.read(Package.class, xml.toString());

        assertEquals(OFFSET_Y_MM, loaded.getPickOffset().getY(), EPSILON);
    }

    @Test
    public void theNozzlePicksAtTheOffset() throws Exception {
        ReferenceTrayFeeder tray = new ReferenceTrayFeeder();
        tray.setPart(part);
        tray.setLocation(new Location(LengthUnit.Millimeters, 100, 50, 0, 0));
        machine.addFeeder(tray);
        machine.setEnabled(true);
        ReferenceNozzle nozzle = (ReferenceNozzle) machine.getDefaultHead().getDefaultNozzle();
        machine.submit(() -> {
            machine.home();
            nozzle.moveToPickLocation(tray);
            return null;
        }).get();

        Location location = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location expected = part.getPackage().getNozzleLocation(tray.getPickLocation());
        assertEquals(expected.getX(), location.getX(), 1e-3);
        assertEquals(expected.getY(), location.getY(), 1e-3);
    }

    @Test
    public void theToleranceCheckMeasuresFromThePickPoint() throws Exception {
        CheckingBottomVision bottomVision = new CheckingBottomVision();
        ReferenceNozzle nozzle = (ReferenceNozzle) machine.getDefaultHead().getDefaultNozzle();
        double tolerance = nozzle.getNozzleTip().getMaxPickTolerance()
                .convertToUnits(LengthUnit.Millimeters).getValue();
        Location nominal = ReferenceBottomVision.getNominalOffsets(part, 90);

        assertEquals(OFFSET_Y_MM, nominal.getX(), EPSILON);
        bottomVision.check(part, nozzle, nominal, 90);
        bottomVision.check(part, nozzle,
                nominal.add(new Location(LengthUnit.Millimeters, tolerance / 2, 0, 0, 0)), 90);
        assertThrows(Exception.class, () -> bottomVision.check(part, nozzle,
                nominal.add(new Location(LengthUnit.Millimeters, tolerance * 2, 0, 0, 0)), 90));
        assertThrows(Exception.class,
                () -> bottomVision.check(part, nozzle, new Location(LengthUnit.Millimeters), 90));
    }

    @Test
    public void aPerfectPickIsNoPickError() {
        Location nominal = ReferenceBottomVision.getNominalOffsets(part, 90);
        PartAlignmentOffset offsets = new PartAlignmentOffset(
                nominal.derive(null, null, null, 2.0), false, nominal);

        Location error = offsets.getPickError();

        assertEquals(0, error.getX(), EPSILON);
        assertEquals(0, error.getY(), EPSILON);
        assertEquals(2, error.getRotation(), EPSILON);
    }

    @Test
    public void aPickOffsetTakesOneWideShot() throws Exception {
        ReferenceNozzle nozzle = (ReferenceNozzle) machine.getDefaultHead().getDefaultNozzle();
        BottomVisionSettings settings =
                ReferenceBottomVision.getDefault().getInheritedVisionSettings(part);
        Camera camera = VisionUtils.getBottomVisionCamera();
        double maxPartDiameter = nozzle.getNozzleTip().getMaxPartDiameter()
                .convertToUnits(LengthUnit.Millimeters).getValue();

        VisionCompositing.Composite composite = part.getPackage().getVisionCompositing().new Composite(
                part.getPackage(), settings, nozzle, nozzle.getNozzleTip(), camera,
                camera.getLocation(nozzle));

        assertEquals(VisionCompositing.CompositingSolution.PickOffset,
                composite.getCompositingSolution());
        assertEquals(1, composite.getShotsTravel().size());
        assertTrue(composite.getShotsTravel().get(0).getMaxMaskRadius()
                >= maxPartDiameter / 2 + Math.abs(OFFSET_Y_MM));
    }

    @Test
    public void aDiscardDropsThePartOriginOnTheDiscardLocation() throws Exception {
        ReferenceTrayFeeder tray = new ReferenceTrayFeeder();
        tray.setPart(part);
        tray.setLocation(new Location(LengthUnit.Millimeters, 100, 50, 0, 90));
        machine.addFeeder(tray);
        machine.setEnabled(true);
        ReferenceNozzle nozzle = (ReferenceNozzle) machine.getDefaultHead().getDefaultNozzle();
        Location discard = machine.getDiscardLocation();
        machine.submit(() -> {
            machine.home();
            nozzle.moveToPickLocation(tray);
            nozzle.pick(part, tray);
            nozzle.moveToSafeZ();
            Cycles.discard(nozzle);
            return null;
        }).get();

        Location expected = part.getPackage().getNozzleLocation(discard
                .derive(null, null, null, nozzle.getLocation().getRotation()))
                .convertToUnits(LengthUnit.Millimeters);
        Location location = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        assertEquals(expected.getX(), location.getX(), 1e-3);
        assertEquals(expected.getY(), location.getY(), 1e-3);
    }

    private static class CheckingBottomVision extends ReferenceBottomVision {
        void check(Part part, ReferenceNozzle nozzle, Location offsets, double rotation)
                throws Exception {
            offsetsCheck(part, nozzle, offsets, rotation);
        }
    }
}
