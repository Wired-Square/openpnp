package org.openpnp.mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Job;
import org.openpnp.model.Package;
import org.openpnp.model.Placement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class ResolvePartSizesTool implements McpTool {
    static final String KICAD_PCB = "kicadPcb";
    static final String PACKAGE_IDS = "packageIds";

    static final String SOURCE_FAB = "kicadFab";
    static final String SOURCE_COURTYARD = "kicadCourtyard";
    static final String SOURCE_NAME = "name";

    /** Sources disagreeing by more than this fraction of a dimension are pointed out. */
    private static final double DISAGREEMENT = 0.1;
    /** Outlines that agree to 0.01 mm count as the same size. */
    private static final double SAME_SIZE_SCALE = 100;
    /**
     * A fabrication outline this much thinner than its courtyard in either direction is likely a
     * marking, not the body.
     */
    private static final double THIN_FAB_FRACTION = 0.25;
    /** A body centre closer than this to the footprint origin is reported as at the origin. */
    private static final double CENTRED_MM = 0.01;

    private final Supplier<Configuration> configuration;
    private final Supplier<Job> job;

    public ResolvePartSizesTool(Supplier<Configuration> configuration, Supplier<Job> job) {
        this.configuration = configuration;
        this.job = job;
    }

    @Override
    public String getName() {
        return "resolve_part_sizes";
    }

    @Override
    public String getDescription() {
        return "Proposes a body size (mm, width along the footprint's X) for each package, "
                + "without changing anything. Sources, best first: the board's KiCad PCB "
                + "fabrication outline (F.Fab, matched to the job's placements by reference, "
                + "else by footprint name), the package name (KiCad and IPC-7351 conventions), "
                + "and the courtyard as a loose upper bound. Each proposal has its source, "
                + "confidence and, where the body is not centred on the footprint origin, the "
                + "body centre. Record a confirmed size with set_package_body_size. Defaults to "
                + "the packages of the loaded job's enabled placements.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string(KICAD_PCB, "Absolute path of the board's .kicad_pcb file")
                .stringArray(PACKAGE_IDS, "Only these packages")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Configuration configuration = this.configuration.get();
        Job loaded = job.get();
        Map<Package, Set<String>> references = referencesByPackage(loaded);

        List<Package> packages = new ArrayList<>();
        List<String> ids = McpJson.optionalStrings(arguments, PACKAGE_IDS);
        if (ids != null) {
            for (String id : ids) {
                Package pkg = configuration.getPackage(id);
                if (pkg == null) {
                    throw new IllegalArgumentException("No package " + id);
                }
                packages.add(pkg);
            }
        }
        else {
            JobPlacements.require(loaded);
            packages.addAll(references.keySet());
        }

        String path = McpJson.optionalString(arguments, KICAD_PCB);
        KicadBoard board = path == null ? null : KicadBoard.read(new File(path));

        JsonArray results = new JsonArray();
        for (Package pkg : packages) {
            results.add(resolve(pkg, references.getOrDefault(pkg, Set.of()), board));
        }
        JsonObject result = new JsonObject();
        if (board == null) {
            result.addProperty("note", "No " + KICAD_PCB + " given; only package names were read");
        }
        result.add("packages", results);
        return McpToolResult.json(result);
    }

    private static Map<Package, Set<String>> referencesByPackage(Job job) {
        Map<Package, Set<String>> references = new LinkedHashMap<>();
        if (job == null) {
            return references;
        }
        for (Placement placement : JobPlacements.enabled(job)) {
            Package pkg = placement.getPart().getPackage();
            if (pkg != null) {
                references.computeIfAbsent(pkg, p -> new LinkedHashSet<>()).add(placement.getId());
            }
        }
        return references;
    }

    static JsonObject resolve(Package pkg, Set<String> references, KicadBoard board) {
        JsonObject json = new JsonObject();
        json.addProperty("packageId", pkg.getId());
        Footprint footprint = pkg.getFootprint();
        if (footprint != null && footprint.getBodyWidth() > 0 && footprint.getBodyHeight() > 0) {
            JsonObject current = new JsonObject();
            current.addProperty("width", McpJson.millimetres(footprint.getBodyWidth(),
                    footprint.getUnits()));
            current.addProperty("height", McpJson.millimetres(footprint.getBodyHeight(),
                    footprint.getUnits()));
            json.add("current", current);
        }

        List<String> notes = new ArrayList<>();
        JsonObject proposal = null;
        if (board != null) {
            List<KicadBoard.Footprint> matches = matchByReference(board, references);
            String matchedBy = "reference";
            if (matches.isEmpty()) {
                matches = matchByName(board, pkg.getId());
                matchedBy = "footprint name";
            }
            if (matches.isEmpty()) {
                notes.add("No footprint in the KiCad file matches by reference or name");
            }
            else {
                proposal = fromOutlines(matches, matchedBy, notes);
                if (proposal != null && "reference".equals(matchedBy)) {
                    checkNames(pkg, matches, proposal, notes);
                }
            }
        }

        PackageNameSizes.Size named = PackageNameSizes.of(pkg.getId());
        if (named != null) {
            if (proposal == null) {
                proposal = proposal(named.width, named.height, SOURCE_NAME, "medium",
                        named.rule);
            }
            else if (SOURCE_FAB.equals(proposal.get("source").getAsString())
                    && disagrees(proposal, named)) {
                notes.add(String.format(Locale.ROOT, "The name suggests %.2f × %.2f mm (%s)",
                        named.width, named.height, named.rule));
            }
        }
        if (proposal == null) {
            notes.add("No source gives a size; measure the part with check_job_picks or "
                    + "the datasheet");
        }
        else {
            json.add("proposal", proposal);
        }
        if (!notes.isEmpty()) {
            JsonArray notesJson = new JsonArray();
            notes.forEach(notesJson::add);
            json.add("notes", notesJson);
        }
        return json;
    }

    private static List<KicadBoard.Footprint> matchByReference(KicadBoard board,
            Set<String> references) {
        List<KicadBoard.Footprint> matches = new ArrayList<>();
        for (KicadBoard.Footprint footprint : board.getFootprints()) {
            if (footprint.reference != null && references.contains(footprint.reference)) {
                matches.add(footprint);
            }
        }
        return matches;
    }

    /** KiCad names may hold spaces that an OpenPnP id import turned into underscores. */
    private static List<KicadBoard.Footprint> matchByName(KicadBoard board, String packageId) {
        String wanted = normaliseName(packageId);
        List<KicadBoard.Footprint> matches = new ArrayList<>();
        for (KicadBoard.Footprint footprint : board.getFootprints()) {
            if (normaliseName(footprint.name).equals(wanted)) {
                matches.add(footprint);
            }
        }
        return matches;
    }

    /**
     * A reference names a different footprint when the KiCad file is not the job's board, or
     * the package was renamed; the outline is then only as good as the operator's check.
     */
    private static void checkNames(Package pkg, List<KicadBoard.Footprint> matches,
            JsonObject proposal, List<String> notes) {
        String wanted = normaliseName(pkg.getId());
        Set<String> others = new LinkedHashSet<>();
        for (KicadBoard.Footprint footprint : matches) {
            if (!normaliseName(footprint.name).equals(wanted)) {
                others.add(footprint.name);
            }
        }
        if (others.isEmpty()) {
            return;
        }
        if ("high".equals(proposal.get("confidence").getAsString())) {
            proposal.addProperty("confidence", "medium");
        }
        notes.add("The matching references use footprint " + String.join(", ", others)
                + ", not " + pkg.getId() + "; check the KiCad file is this job's board");
    }

    private static String normaliseName(String name) {
        return name.replace(' ', '_').toLowerCase(Locale.ROOT);
    }

    private static JsonObject fromOutlines(List<KicadBoard.Footprint> matches, String matchedBy,
            List<String> notes) {
        List<KicadBoard.Box> fabs = new ArrayList<>();
        List<KicadBoard.Box> courtyards = new ArrayList<>();
        Set<String> referencesUsed = new LinkedHashSet<>();
        for (KicadBoard.Footprint footprint : matches) {
            if (footprint.reference != null) {
                referencesUsed.add(footprint.reference);
            }
            if (footprint.fab != null) {
                fabs.add(footprint.fab);
            }
            if (footprint.courtyard != null) {
                courtyards.add(footprint.courtyard);
            }
        }
        String detail = String.format(Locale.ROOT, "%s, matched by %s (%s)",
                matches.get(0).name, matchedBy, String.join(", ", referencesUsed));
        if (!fabs.isEmpty()) {
            KicadBoard.Box fab = mostCommon(fabs, notes);
            String confidence = "high";
            if (!courtyards.isEmpty() && isThin(fab, mostCommon(courtyards, new ArrayList<>()))) {
                confidence = "medium";
                notes.add("The fabrication outline is much thinner than the courtyard, so it "
                        + "may not be the body; check it");
            }
            JsonObject proposal = proposal(fab.width(), fab.height(), SOURCE_FAB, confidence,
                    detail);
            addCentre(proposal, fab, notes);
            return proposal;
        }
        if (!courtyards.isEmpty()) {
            KicadBoard.Box courtyard = mostCommon(courtyards, notes);
            notes.add("No fabrication outline; the courtyard includes the pads and a clearance, "
                    + "so the body is smaller");
            return proposal(courtyard.width(), courtyard.height(), SOURCE_COURTYARD, "low",
                    detail);
        }
        notes.add("The matching footprints have no fabrication outline or courtyard");
        return null;
    }

    /** Footprints of one package normally agree; if not, the majority wins and it is noted. */
    private static KicadBoard.Box mostCommon(List<KicadBoard.Box> boxes, List<String> notes) {
        Map<String, List<KicadBoard.Box>> bySize = new LinkedHashMap<>();
        for (KicadBoard.Box box : boxes) {
            String key = Math.round(box.width() * SAME_SIZE_SCALE) + "x"
                    + Math.round(box.height() * SAME_SIZE_SCALE);
            bySize.computeIfAbsent(key, k -> new ArrayList<>()).add(box);
        }
        List<KicadBoard.Box> best = null;
        for (List<KicadBoard.Box> group : bySize.values()) {
            if (best == null || group.size() > best.size()) {
                best = group;
            }
        }
        if (bySize.size() > 1) {
            notes.add(String.format(Locale.ROOT,
                    "The matching footprints have %d different outline sizes; the most common "
                            + "is proposed",
                    bySize.size()));
        }
        return best.get(0);
    }

    private static boolean isThin(KicadBoard.Box fab, KicadBoard.Box courtyard) {
        return fab.width() < THIN_FAB_FRACTION * courtyard.width()
                || fab.height() < THIN_FAB_FRACTION * courtyard.height();
    }

    private static void addCentre(JsonObject proposal, KicadBoard.Box box, List<String> notes) {
        if (Math.hypot(box.centreX(), box.centreY()) < CENTRED_MM) {
            return;
        }
        JsonObject centre = new JsonObject();
        centre.addProperty("x", McpJson.round(box.centreX()));
        centre.addProperty("y", McpJson.round(box.centreY()));
        proposal.add("bodyCentre", centre);
        notes.add("The body is not centred on the footprint origin; bodyCentre is in the "
                + "footprint's frame");
    }

    private static boolean disagrees(JsonObject proposal, PackageNameSizes.Size named) {
        double width = proposal.get("width").getAsDouble();
        double height = proposal.get("height").getAsDouble();
        return Math.abs(width - named.width) > DISAGREEMENT * named.width
                || Math.abs(height - named.height) > DISAGREEMENT * named.height;
    }

    private static JsonObject proposal(double width, double height, String source,
            String confidence, String detail) {
        JsonObject proposal = new JsonObject();
        proposal.addProperty("width", McpJson.round(width));
        proposal.addProperty("height", McpJson.round(height));
        proposal.addProperty("source", source);
        proposal.addProperty("confidence", confidence);
        proposal.addProperty("detail", detail);
        return proposal;
    }
}
