package org.openpnp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

import com.google.common.io.Files;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PartSizesTest {
    private static final double EPSILON = 1e-9;

    /** A KiCad 8 SOIC-8 at 90°: its F.Fab polygon is the 3.9 × 4.9 mm body. */
    private static final String SOIC = "(footprint \"Package_SO:SOIC-8_3.9x4.9mm_P1.27mm\"\n"
            + "  (layer \"F.Cu\") (at 120.795 83.015 90)\n"
            + "  (property \"Reference\" \"U6\" (at 0 -1.3 90) (layer \"F.SilkS\"))\n"
            + "  (fp_line (start 2.06 -2.56) (end 2.06 -2.465) (layer \"F.SilkS\"))\n"
            + "  (fp_line (start 3.7 -2.46) (end 3.7 2.46) (layer \"F.CrtYd\"))\n"
            + "  (fp_line (start -3.7 2.46) (end -3.7 -2.7) (layer \"F.CrtYd\"))\n"
            + "  (fp_poly (pts (xy -0.975 -2.45) (xy 1.95 -2.45) (xy 1.95 2.45) (xy -1.95 2.45)"
            + " (xy -1.95 -1.475)) (layer \"F.Fab\"))\n"
            + "  (pad \"1\" smd roundrect (at -2.475 -1.905 90) (size 1.95 0.6)))\n";
    /** A KiCad 6 module whose body sits off the origin: 18 × 25.5 mm, centred 3.5 mm up. */
    private static final String MODULE = "(footprint \"RF_Module:ESP32-WROOM-32\" (layer \"F.Cu\")\n"
            + "  (at 10 20)\n"
            + "  (fp_text reference \"U1\" (at 0 0) (layer \"F.SilkS\"))\n"
            + "  (fp_rect (start -9 -16.25) (end 9 9.25) (layer \"F.Fab\")))\n";
    /** On the back, with only a courtyard drawn as a circle and an arc on the fab layer of the top. */
    private static final String BACK = "(footprint \"Custom:Odd Part\" (layer \"B.Cu\") (at 0 0)\n"
            + "  (property \"Reference\" \"X1\")\n"
            + "  (fp_circle (center 0 0) (end 1.5 0) (layer \"B.CrtYd\"))\n"
            + "  (fp_arc (start -1 0) (mid 0 -1) (end 1 0) (layer \"F.Fab\")))\n";

    private static KicadBoard board(String... footprints) {
        return KicadBoard.parse("(kicad_pcb (version 20260206) (generator \"pcbnew\")\n"
                + String.join("\n", footprints) + ")");
    }

    private static KicadBoard.Footprint only(KicadBoard board) {
        assertEquals(1, board.getFootprints().size());
        return board.getFootprints().get(0);
    }

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
    }

    @Test
    public void theFabOutlineIsTheBodyInTheFootprintFrame() {
        KicadBoard.Footprint soic = only(board(SOIC));

        assertEquals("U6", soic.reference);
        assertEquals("SOIC-8_3.9x4.9mm_P1.27mm", soic.name);
        assertFalse(soic.bottom);
        assertEquals(3.9, soic.fab.width(), EPSILON);
        assertEquals(4.9, soic.fab.height(), EPSILON);
        assertEquals(0, soic.fab.centreX(), EPSILON);
        assertEquals(0, soic.fab.centreY(), EPSILON);
        assertEquals(7.4, soic.courtyard.width(), EPSILON);
    }

    @Test
    public void kicadSixReferencesAndOffCentreBodiesAreRead() {
        KicadBoard.Footprint module = only(board(MODULE));

        assertEquals("U1", module.reference);
        assertEquals(18, module.fab.width(), EPSILON);
        assertEquals(25.5, module.fab.height(), EPSILON);
        // KiCad's Y points down: a body drawn toward -Y is above the origin in OpenPnP.
        assertEquals(3.5, module.fab.centreY(), EPSILON);
    }

    @Test
    public void aBackFootprintUsesTheBackLayers() {
        KicadBoard.Footprint back = only(board(BACK));

        assertTrue(back.bottom);
        assertNull(back.fab);
        assertEquals(3, back.courtyard.width(), EPSILON);
        assertEquals(3, back.courtyard.height(), EPSILON);
    }

    @Test
    public void aBackFootprintIsMirroredBackToTheLibraryFrame() {
        String body = " (fp_rect (start -3 -1) (end -1 1) (layer \"%s.Fab\")))";
        // KiCad stores a back footprint flipped left to right.
        KicadBoard board = board(
                "(footprint \"Lib:Off\" (layer \"F.Cu\")" + String.format(body, "F"),
                "(footprint \"Lib:Off\" (layer \"B.Cu\")"
                        + String.format(body, "B").replace("-3 -1", "1 -1").replace("-1 1", "3 1"));

        assertEquals(-2, board.getFootprints().get(0).fab.centreX(), EPSILON);
        assertEquals(-2, board.getFootprints().get(1).fab.centreX(), EPSILON);
    }

    @Test
    public void quotedStringsKeepEscapesAndParentheses() {
        KicadBoard.Footprint part = only(board("(footprint \"Lib:Name (\\\"x\\\")\" (layer \"F.Cu\")"
                + " (property \"Reference\" \"R(1)\"))"));

        assertEquals("Name (\"x\")", part.name);
        assertEquals("R(1)", part.reference);
    }

    @Test
    public void notAKicadFileIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> KicadBoard.parse("(kicad_sch)"));
        assertThrows(IllegalArgumentException.class, () -> KicadBoard.parse("(kicad_pcb (a"));
        assertThrows(IllegalArgumentException.class, () -> KicadBoard.parse("hello"));
        assertThrows(IllegalArgumentException.class,
                () -> KicadBoard.parse("(kicad_pcb (module \"Lib:R\" (layer F.Cu)))"));
        assertThrows(IllegalArgumentException.class,
                () -> KicadBoard.read(new File("board.txt")));
    }

    @Test
    public void namesGiveSizesByConvention() {
        assertSize(1.6, 0.8, "C_0603_1608Metric");
        assertSize(4.5, 3.2, "Fuse_1812_4532Metric");
        assertSize(0.6, 0.3, "R_0201_0603Metric");
        assertSize(3.9, 4.9, "SOIC-8_3.9x4.9mm_P1.27mm");
        assertSize(4.4, 5, "TSSOP-16_4.4x5mm_P0.65mm");
        assertSize(4, 4, "QFN50P400X400X80-21N");
        assertSize(2, 1.25, "R0805");
        assertSize(1, 0.5, "0402");
        assertSize(6.3, 3.2, "R2512");
        // Leaded IPC names give the lead span, not the body.
        assertNull(PackageNameSizes.of("SOP50P600X148-17N"));
        assertNull(PackageNameSizes.of("SOT-23"));
        assertNull(PackageNameSizes.of("TQFP32"));
        assertNull(PackageNameSizes.of("74438357100"));
    }

    private static void assertSize(double width, double height, String name) {
        PackageNameSizes.Size size = PackageNameSizes.of(name);
        assertEquals(width, size.width, EPSILON, name);
        assertEquals(height, size.height, EPSILON, name);
    }

    @Test
    public void aFabOutlineMatchedByReferenceWinsOverTheName() {
        Package pkg = new Package("SOIC-8_3.9x4.9mm_P1.27mm");

        JsonObject proposal = ResolvePartSizesTool.resolve(pkg, Set.of("U6"), board(SOIC))
                .getAsJsonObject("proposal");

        assertEquals(3.9, proposal.get("width").getAsDouble(), EPSILON);
        assertEquals(4.9, proposal.get("height").getAsDouble(), EPSILON);
        assertEquals(ResolvePartSizesTool.SOURCE_FAB, proposal.get("source").getAsString());
        assertEquals("high", proposal.get("confidence").getAsString());
        assertTrue(proposal.get("detail").getAsString().contains("reference (U6)"));
        assertFalse(proposal.has("bodyCentre"));
    }

    @Test
    public void anUnreferencedPackageMatchesByFootprintName() {
        Package pkg = new Package("ESP32-WROOM-32");

        JsonObject json = ResolvePartSizesTool.resolve(pkg, Set.of(), board(SOIC, MODULE));

        JsonObject proposal = json.getAsJsonObject("proposal");
        assertEquals(18, proposal.get("width").getAsDouble(), EPSILON);
        assertTrue(proposal.get("detail").getAsString().contains("footprint name"));
        assertEquals(3.5, proposal.getAsJsonObject("bodyCentre").get("y").getAsDouble(),
                EPSILON);
        assertTrue(json.getAsJsonArray("notes").toString().contains("not centred"));
    }

    @Test
    public void spacesInKicadNamesMatchUnderscores() {
        JsonObject proposal = ResolvePartSizesTool.resolve(new Package("Odd_Part"), Set.of(),
                board(BACK)).getAsJsonObject("proposal");

        assertEquals(ResolvePartSizesTool.SOURCE_COURTYARD, proposal.get("source").getAsString());
        assertEquals("low", proposal.get("confidence").getAsString());
    }

    @Test
    public void withoutTheBoardTheNameIsUsed() {
        JsonObject json = ResolvePartSizesTool.resolve(new Package("C_0603_1608Metric"),
                Set.of(), null);

        assertEquals(ResolvePartSizesTool.SOURCE_NAME,
                json.getAsJsonObject("proposal").get("source").getAsString());
        assertFalse(json.has("current"));
    }

    @Test
    public void aFabOutlineThatDisagreesWithTheNameIsPointedOut() {
        String bigger = SOIC.replace("1.95", "2.95");

        JsonObject json = ResolvePartSizesTool.resolve(new Package("SOIC-8_3.9x4.9mm_P1.27mm"),
                Set.of("U6"), board(bigger));

        assertTrue(json.getAsJsonArray("notes").toString().contains("The name suggests"));
    }

    @Test
    public void aFabMarkingMuchThinnerThanTheCourtyardIsDoubted() {
        String connector = "(footprint \"Custom:JST\" (layer \"F.Cu\")"
                + " (property \"Reference\" \"J4\")"
                + " (fp_line (start -3.3 0) (end 3.3 0.5) (layer \"F.Fab\"))"
                + " (fp_rect (start -3.5 -2.2) (end 3.5 2.2) (layer \"F.CrtYd\")))";

        JsonObject json = ResolvePartSizesTool.resolve(new Package("JST"), Set.of("J4"),
                board(connector));

        assertEquals("medium",
                json.getAsJsonObject("proposal").get("confidence").getAsString());
        assertTrue(json.getAsJsonArray("notes").toString().contains("thinner"));
    }

    @Test
    public void aReferenceToAnotherFootprintIsDoubted() {
        JsonObject json = ResolvePartSizesTool.resolve(new Package("SOT-23"), Set.of("U6"),
                board(SOIC));

        assertEquals("medium",
                json.getAsJsonObject("proposal").get("confidence").getAsString());
        assertTrue(json.getAsJsonArray("notes").toString().contains("this job's board"));
    }

    @Test
    public void noSourceSaysSo() {
        JsonObject json = ResolvePartSizesTool.resolve(new Package("SOT-23"), Set.of(),
                board(SOIC));

        assertFalse(json.has("proposal"));
        assertTrue(json.getAsJsonArray("notes").toString().contains("No source"));
    }

    @Test
    public void theToolResolvesTheJobsPackagesFromTheBoardFile() throws Exception {
        Configuration configuration = Configuration.get();
        Package pkg = new Package("SOIC-8_3.9x4.9mm_P1.27mm");
        configuration.addPackage(pkg);
        Part part = new Part("SN65HVD230D");
        part.setPackage(pkg);
        configuration.addPart(part);
        Board board = new Board();
        Placement placement = new Placement("U6");
        placement.setPart(part);
        board.addPlacement(placement);
        Job job = new Job();
        job.addBoardOrPanelLocation(new BoardLocation(board));
        File pcb = new File(Files.createTempDir(), "board" + KicadBoard.FILE_SUFFIX);
        Files.asCharSink(pcb, StandardCharsets.UTF_8).write("(kicad_pcb " + SOIC + ")");

        JsonArray packages = new ResolvePartSizesTool(() -> configuration, () -> job)
                .call(JsonParser.parseString("{\"kicadPcb\":\""
                        + pcb.getAbsolutePath().replace("\\", "\\\\") + "\"}")
                        .getAsJsonObject())
                .getStructuredContent().getAsJsonArray("packages");

        assertEquals(1, packages.size());
        assertEquals(3.9, packages.get(0).getAsJsonObject().getAsJsonObject("proposal")
                .get("width").getAsDouble(), EPSILON);
    }

    @Test
    public void theToolNeedsAJobOrPackageIds() {
        Configuration configuration = Configuration.get();
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvePartSizesTool(() -> configuration, () -> null)
                        .call(new JsonObject()));
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvePartSizesTool(() -> configuration, () -> null)
                        .call(JsonParser.parseString("{\"packageIds\":[\"nope\"]}")
                                .getAsJsonObject()));
    }

    @Test
    public void setPackageBodySizeConvertsToTheFootprintUnits() throws Exception {
        Configuration configuration = Configuration.get();
        Package pkg = new Package("P1");
        pkg.getFootprint().setUnits(LengthUnit.Inches);
        configuration.addPackage(pkg);
        SetPackageBodySizeTool tool = new SetPackageBodySizeTool(() -> configuration);

        JsonObject result = tool.call(JsonParser.parseString(
                "{\"packageId\":\"P1\",\"width\":25.4,\"height\":12.7}").getAsJsonObject())
                .getStructuredContent();

        assertEquals(1, pkg.getFootprint().getBodyWidth(), EPSILON);
        assertEquals(0.5, pkg.getFootprint().getBodyHeight(), EPSILON);
        assertEquals(25.4, result.getAsJsonObject("after").get("width").getAsDouble(), EPSILON);
        assertEquals(0, result.getAsJsonObject("before").get("width").getAsDouble(), EPSILON);
        assertThrows(IllegalArgumentException.class, () -> tool.call(JsonParser.parseString(
                "{\"packageId\":\"P1\",\"width\":0,\"height\":1}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> tool.call(JsonParser.parseString(
                "{\"packageId\":\"P1\",\"width\":1,\"height\":1000}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> tool.call(JsonParser.parseString(
                "{\"packageId\":\"nope\",\"width\":1,\"height\":1}").getAsJsonObject()));
    }
}
