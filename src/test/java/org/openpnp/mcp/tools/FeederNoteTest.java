package org.openpnp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.StringWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceLoosePartFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Machine;
import org.openpnp.spi.base.AbstractFeeder;
import org.simpleframework.xml.Serializer;

import com.google.common.io.Files;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class FeederNoteTest {
    private static final String NOTE = "Lay CH340C with pin 1 towards -Y (front of the machine)";

    private Machine machine;
    private ReferenceLoosePartFeeder feeder;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        machine = Configuration.get().getMachine();
        feeder = new ReferenceLoosePartFeeder();
        feeder.setLocation(new Location(LengthUnit.Millimeters));
        feeder.setPart(null);
        machine.addFeeder(feeder);
    }

    private JsonObject setNote(String note) throws Exception {
        return setNote(note, feeder.getNote() == null ? "" : feeder.getNote());
    }

    private JsonObject setNote(String note, String expectedNote) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("feederId", feeder.getId());
        arguments.addProperty("note", note);
        arguments.addProperty("expectedNote", expectedNote);
        return new SetFeederNoteTool(() -> machine).call(arguments).getStructuredContent();
    }

    @Test
    public void theNoteIsSetReportedAndCleared() throws Exception {
        JsonObject described = setNote(NOTE);

        assertEquals(NOTE, feeder.getNote());
        assertEquals(NOTE, described.get("note").getAsString());

        assertFalse(setNote("  ").has("note"));
        assertNull(feeder.getNote());
    }

    @Test
    public void anOverlongNoteIsRefusedWhoeverSetsIt() {
        String overlong = "x".repeat(AbstractFeeder.MAX_NOTE_LENGTH + 1);

        assertThrows(IllegalArgumentException.class, () -> setNote(overlong));
        assertThrows(IllegalArgumentException.class, () -> feeder.setNote(overlong));
        assertNull(feeder.getNote());
    }

    @Test
    public void aNoteThatCouldNotBeSavedIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> setNote("pin 1\u0001"));
        assertThrows(IllegalArgumentException.class, () -> feeder.setNote("\uFFFE"));
        assertNull(feeder.getNote());
    }

    @Test
    public void aNoteChangedSinceItWasReadIsNotOverwritten() throws Exception {
        feeder.setNote("Operator's convention");

        assertThrows(IllegalArgumentException.class, () -> setNote(NOTE, ""));
        assertEquals("Operator's convention", feeder.getNote());

        setNote(NOTE, "Operator's convention");
        assertEquals(NOTE, feeder.getNote());
    }

    @Test
    public void lineEndingsAreStoredAsXmlReadsThemBack() {
        feeder.setNote("first\r\nsecond\rthird");

        assertEquals("first\nsecond\nthird", feeder.getNote());
    }

    @Test
    public void aMissingArgumentIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SetFeederNoteTool(() -> machine)
                .call(JsonParser.parseString("{\"feederId\":\"" + feeder.getId() + "\"}")
                        .getAsJsonObject()));
    }

    @Test
    public void theNoteIsSavedWithTheFeeder() throws Exception {
        feeder.setNote(NOTE + " & <keep> markup");
        Serializer serializer = Configuration.createSerializer();
        StringWriter xml = new StringWriter();

        serializer.write(feeder, xml);

        assertEquals(NOTE + " & <keep> markup",
                serializer.read(ReferenceLoosePartFeeder.class, xml.toString()).getNote());
    }
}
