package org.openpnp.machine.reference.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.vision.pipeline.stages.FilterContours;

import com.google.common.io.Files;

public class ReferenceLoosePartFeederSearchTest {
    private static final double EPSILON = 1e-9;
    private static final Location LOCATION = new Location(LengthUnit.Millimeters, 100, 80, 0, -90);

    private Machine machine;
    private Camera camera;
    private ReferenceLoosePartFeeder feeder;
    private double frameWidth;
    private double frameHeight;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        machine = Configuration.get().getMachine();
        camera = machine.getDefaultHead().getDefaultCamera();
        feeder = new ReferenceLoosePartFeeder();
        feeder.setLocation(LOCATION);
        machine.addFeeder(feeder);
        Location unitsPerPixel = camera.getUnitsPerPixel(LOCATION.getLengthZ())
                .convertToUnits(LengthUnit.Millimeters);
        frameWidth = camera.getWidth() * Math.abs(unitsPerPixel.getX());
        frameHeight = camera.getHeight() * Math.abs(unitsPerPixel.getY());
    }

    @Test
    public void withoutASearchAreaItLooksAtTheLocationOnly() {
        List<Location> frames = feeder.getSearchFrames(camera);

        assertEquals(List.of(LOCATION), frames);
    }

    @Test
    public void aTallerAreaIsCoveredByOverlappingFrames() {
        feeder.setSearchWidth(new Length(frameWidth * 0.8, LengthUnit.Millimeters));
        feeder.setSearchHeight(new Length(frameHeight * 1.4, LengthUnit.Millimeters));

        List<Location> frames = feeder.getSearchFrames(camera);

        assertEquals(2, frames.size());
        double offset = frameHeight * 0.2;
        assertEquals(LOCATION.getY() - offset, frames.get(0).getY(), EPSILON);
        assertEquals(LOCATION.getY() + offset, frames.get(1).getY(), EPSILON);
        for (Location frame : frames) {
            assertEquals(LOCATION.getX(), frame.getX(), EPSILON);
            assertEquals(LOCATION.getRotation(), frame.getRotation(), EPSILON);
        }
    }

    @Test
    public void framesAreNeverMoreThanHalfAFrameApart() {
        feeder.setSearchWidth(new Length(frameWidth * 3.1, LengthUnit.Millimeters));
        feeder.setSearchHeight(new Length(frameHeight * 0.5, LengthUnit.Millimeters));

        List<Location> frames = feeder.getSearchFrames(camera);

        for (int i = 1; i < frames.size(); i++) {
            assertTrue(frames.get(i).getX() - frames.get(i - 1).getX() <= frameWidth / 2 + EPSILON);
        }
        assertEquals(LOCATION.getX() - (frameWidth * 3.1 - frameWidth) / 2, frames.get(0).getX(),
                EPSILON);
    }

    @Test
    public void framesOverlapByThePartSize() {
        double part = frameHeight * 0.8;
        feeder.setSearchHeight(new Length(frameHeight * 1.4, LengthUnit.Millimeters));
        feeder.setPartSize(new Length(part, LengthUnit.Millimeters));

        List<Location> frames = feeder.getSearchFrames(camera);

        assertEquals(3, frames.size());
        for (int i = 1; i < frames.size(); i++) {
            double step = frames.get(i).getY() - frames.get(i - 1).getY();
            assertTrue(frameHeight - step >= part - EPSILON);
        }
    }

    @Test
    public void aPartLargerThanAFrameIsAConfigurationError() {
        feeder.setSearchHeight(new Length(frameHeight * 2, LengthUnit.Millimeters));
        feeder.setPartSize(new Length(frameHeight, LengthUnit.Millimeters));

        assertThrows(IllegalStateException.class, () -> feeder.getSearchFrames(camera));
    }

    @Test
    public void anEmptySearchAreaReportsTheFeederEmpty() throws Exception {
        machine.setEnabled(true);
        machine.submit(() -> {
            machine.home();
            return null;
        }).get();
        feeder.setSearchHeight(new Length(frameHeight * 1.4, LengthUnit.Millimeters));
        ((FilterContours) feeder.getPipeline().getStage("filtered_contours"))
                .setMinArea(Double.MAX_VALUE);

        Exception thrown = assertThrows(Exception.class, () -> machine.submit(() -> {
            feeder.feed(machine.getDefaultHead().getDefaultNozzle());
            return null;
        }).get());

        assertTrue(thrown.getCause() instanceof Feeder.FeederEmptyException, thrown.toString());
    }
}
