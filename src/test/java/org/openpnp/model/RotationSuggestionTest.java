package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.StringWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceLoosePartFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTubeFeeder;
import org.openpnp.model.RotationSuggestion.Confidence;
import org.openpnp.spi.Feeder;
import org.simpleframework.xml.Serializer;

import com.google.common.io.Files;

public class RotationSuggestionTest {
    private static final double EPSILON = 1e-9;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
    }

    @Test
    public void aTrayFeederAppliesTheRotationToItsLocation() throws Exception {
        ReferenceTrayFeeder tray = new ReferenceTrayFeeder();
        tray.setLocation(new Location(LengthUnit.Millimeters, 10, 20, 3, 0));
        tray.setRotationSuggestion(new RotationSuggestion(-90, Confidence.Medium, "strip"));

        tray.applyRotationSuggestion();

        assertEquals(-90, tray.getLocation().getRotation(), EPSILON);
        assertEquals(10, tray.getLocation().getX(), EPSILON);
        assertEquals(-90, tray.getPickLocation().getRotation(), EPSILON);
        assertNull(tray.getRotationSuggestion());
    }

    @Test
    public void aLoosePartFeederAppliesTheRotationToItsLocation() throws Exception {
        ReferenceLoosePartFeeder loose = new ReferenceLoosePartFeeder();
        loose.setLocation(new Location(LengthUnit.Millimeters, 296, 103, 5, 0));
        loose.setRotationSuggestion(new RotationSuggestion(-90, Confidence.High, "pin 1 back-right"));

        loose.applyRotationSuggestion();

        assertEquals(-90, loose.getLocation().getRotation(), EPSILON);
        assertEquals(296, loose.getLocation().getX(), EPSILON);
        assertNull(loose.getRotationSuggestion());
    }

    @Test
    public void applyingWithoutASuggestionFails() {
        assertThrows(IllegalStateException.class,
                () -> new ReferenceTrayFeeder().applyRotationSuggestion());
    }

    @Test
    public void feedersCannotApplyARotationByDefault() {
        Feeder plain = new ReferenceTubeFeeder();

        assertFalse(plain.canApplyPickRotation());
        assertThrows(UnsupportedOperationException.class, () -> plain.applyPickRotation(0));
    }

    @Test
    public void aRationaleThatCouldNotBeSavedIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new RotationSuggestion(0, Confidence.High, "pin 1\u0001"));
        assertThrows(IllegalArgumentException.class,
                () -> new RotationSuggestion(0, Confidence.High, "half \uD800 pair"));
        new RotationSuggestion(0, Confidence.High, "tab\tline\nemoji \uD83D\uDE00");
    }

    @Test
    public void theSuggestionIsSavedWithTheFeeder() throws Exception {
        ReferenceTrayFeeder tray = new ReferenceTrayFeeder();
        tray.setLocation(new Location(LengthUnit.Millimeters));
        tray.setPart(null);
        tray.setRotationSuggestion(
                new RotationSuggestion(180, Confidence.Low, "pin 1 & cathode <toward> holes"));
        Serializer serializer = Configuration.createSerializer();
        StringWriter xml = new StringWriter();

        serializer.write(tray, xml);
        ReferenceTrayFeeder loaded = serializer.read(ReferenceTrayFeeder.class, xml.toString());

        RotationSuggestion suggestion = loaded.getRotationSuggestion();
        assertEquals(180, suggestion.getRotation(), EPSILON);
        assertEquals(Confidence.Low, suggestion.getConfidence());
        assertEquals("pin 1 & cathode <toward> holes", suggestion.getRationale());
    }
}
