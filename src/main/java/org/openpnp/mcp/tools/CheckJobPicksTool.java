package org.openpnp.mcp.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.mcp.MotionConfirmer;
import org.openpnp.mcp.MotionTour;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Job;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.NextPickLocatable;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class CheckJobPicksTool implements McpTool {
    static final String FEEDER_IDS = "feederIds";
    static final String FEED_FEEDER_IDS = "feedFeederIds";
    static final String OK_FRACTION = "okFraction";
    static final String MARGINAL_FRACTION = "marginalFraction";
    static final String IMAGES = "images";
    static final String IMAGES_FLAGGED = "flagged";
    static final String IMAGES_ALL = "all";
    static final String IMAGES_NONE = "none";

    static final String STATUS_NO_SIZE = "noBodySize";
    static final String STATUS_NOT_FOUND = "notFound";
    static final String STATUS_ERROR = "error";

    private static final int MIN_CROP_PX = 200;
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Supplier<Configuration> configuration;
    private final Supplier<Job> job;
    private final MotionConfirmer confirmer;
    /** Read on the EDT. A paused job counts: the tour would move and feed under it. */
    private final BooleanSupplier jobActive;
    /** One tour at a time: a second would interleave its moves with the first. */
    private final AtomicBoolean running = new AtomicBoolean();

    public CheckJobPicksTool(Supplier<Configuration> configuration, Supplier<Job> job,
            BooleanSupplier jobActive, MotionConfirmer confirmer) {
        this.configuration = configuration;
        this.job = job;
        this.jobActive = jobActive;
        this.confirmer = confirmer;
    }

    @Override
    public String getName() {
        return "check_job_picks";
    }

    @Override
    public String getDescription() {
        return "Surveys the current pick of every enabled feeder holding a part of the loaded "
                + "job's enabled placements (or of the given feeders): the top camera visits "
                + "each feeder's next part at safe Z (for tray and strip feeders the cell or part "
                + "the next feed presents), captures with its light, saves the frame to snapshots "
                + "and finds the part body nearest the centre, sized from the package's "
                + "footprint body (see resolve_part_sizes). Reports each body's X/Y offset and "
                + "angle from where the feeder says the part is, flagged ok, marginal or off "
                + "(defaults 15% and 30% of the body's shorter side, or beyond the nozzle tip's "
                + "Max Pick Tolerance; 5° and 15°), with cropped frames of flagged picks. The "
                + "operator allows the whole planned route once in OpenPnP and can pause or "
                + "stop it between feeders. Look-only: it feeds only the feeders named in "
                + FEED_FEEDER_IDS + ", which the confirmation lists; nothing is picked. "
                + "Expected positions assume the body is centred on the footprint origin.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .stringArray(FEEDER_IDS, "Only these feeders, job or not")
                .stringArray(FEED_FEEDER_IDS,
                        "Feed these feeders before looking, e.g. where a part seemed missing")
                .number(OK_FRACTION, "Offset up to this fraction of the shorter side is ok")
                .number(MARGINAL_FRACTION,
                        "Offset up to this fraction of the shorter side is marginal")
                .oneOf(IMAGES, "Which cropped frames to return (default flagged)", IMAGES_FLAGGED,
                        IMAGES_ALL, IMAGES_NONE)
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.MACHINE;
    }

    /** It waits for the operator and for each move, so it stays off the EDT. */
    @Override
    public boolean runsOnEdt() {
        return false;
    }

    /** One feeder visit, planned on the EDT. */
    static final class Stop {
        final Feeder feeder;
        final Part part;
        final boolean feed;
        /** The pick location when planned; a feed may change it. */
        final Location plannedPick;
        final double bodyWidth;
        final double bodyHeight;
        final double maxPickTolerance;

        Stop(Feeder feeder, Part part, boolean feed, Location plannedPick, double bodyWidth,
                double bodyHeight, double maxPickTolerance) {
            this.feeder = feeder;
            this.part = part;
            this.feed = feed;
            this.plannedPick = plannedPick;
            this.bodyWidth = bodyWidth;
            this.bodyHeight = bodyHeight;
            this.maxPickTolerance = maxPickTolerance;
        }
    }

    /** What the machine thread brings back from one visit. */
    static final class Shot {
        final BufferedImage image;
        final Location pick;
        final Location camera;
        final Location unitsPerPixel;

        Shot(BufferedImage image, Location pick, Location camera, Location unitsPerPixel) {
            this.image = image;
            this.pick = pick;
            this.camera = camera;
            this.unitsPerPixel = unitsPerPixel;
        }
    }

    static final class Plan {
        final List<Stop> stops = new ArrayList<>();
        final JsonArray skipped = new JsonArray();
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        List<String> feederIds = McpJson.optionalStrings(arguments, FEEDER_IDS);
        List<String> feedIds = McpJson.optionalStrings(arguments, FEED_FEEDER_IDS);
        double okFraction = fraction(arguments, OK_FRACTION, PickCheck.DEFAULT_OK_FRACTION);
        double marginalFraction = fraction(arguments, MARGINAL_FRACTION,
                PickCheck.DEFAULT_MARGINAL_FRACTION);
        if (marginalFraction < okFraction) {
            throw new IllegalArgumentException(
                    MARGINAL_FRACTION + " must be at least " + OK_FRACTION);
        }
        String images = McpJson.optionalString(arguments, IMAGES);
        if (images == null) {
            images = IMAGES_FLAGGED;
        }
        else if (!Set.of(IMAGES_FLAGGED, IMAGES_ALL, IMAGES_NONE).contains(images)) {
            throw new IllegalArgumentException(IMAGES + " must be " + IMAGES_FLAGGED + ", "
                    + IMAGES_ALL + " or " + IMAGES_NONE);
        }

        Configuration configuration = this.configuration.get();
        Machine machine = configuration.getMachine();
        requireReady(machine);
        if (onEdt(jobActive::getAsBoolean)) {
            throw new IllegalArgumentException("A job is running or paused; stop it first");
        }
        Camera camera = machine.getDefaultHead().getDefaultCamera();
        Plan plan = onEdt(() -> plan(machine, job.get(), feederIds, feedIds));
        if (plan.stops.isEmpty()) {
            throw new IllegalArgumentException("No feeder to visit"
                    + (plan.skipped.size() > 0 ? ": " + plan.skipped : ""));
        }
        List<Stop> route = nearestNeighbourRoute(camera.getLocation(), plan.stops,
                stop -> stop.plannedPick);
        String description = describe(camera, route);

        if (!running.compareAndSet(false, true)) {
            throw new IllegalArgumentException("A pick check is already running or waiting "
                    + "for the operator");
        }
        try {
            if (!confirmer.confirm(description)) {
                return McpToolResult.error("The operator did not allow the tour: "
                        + description);
            }
            String notReady = notReady(machine);
            if (notReady != null) {
                return McpToolResult.error(notReady + "; nothing moved");
            }
            return tour(configuration, machine, camera, route, plan.skipped, okFraction,
                    marginalFraction, images);
        }
        finally {
            running.set(false);
        }
    }

    private McpToolResult tour(Configuration configuration, Machine machine, Camera camera,
            List<Stop> route, JsonArray skipped, double okFraction, double marginalFraction,
            String images) throws Exception {
        JsonArray results = new JsonArray();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<BufferedImage> crops = new ArrayList<>();
        List<String> captions = new ArrayList<>();
        String stamp = LocalDateTime.now().format(TIMESTAMP);
        boolean stopped = false;
        String aborted = null;
        int visited = 0;
        try (MotionTour tour = confirmer.startTour(
                String.format(Locale.ROOT, "Checking %d job picks", route.size()))) {
            for (int i = 0; i < route.size(); i++) {
                Stop stop = route.get(i);
                if (!tour.beforeStep(i, route.size(), stop.feeder.getName())) {
                    stopped = true;
                    break;
                }
                // A pause may have let a job start or the operator take the machine.
                aborted = notReady(machine);
                if (aborted != null) {
                    break;
                }
                visited++;
                JsonObject result = describeStop(stop);
                try {
                    Shot shot = machine.submit(() -> visit(machine, camera, stop)).get();
                    String frame = String.format(Locale.ROOT, "pick-check-%s-%02d-%s%s", stamp,
                            i + 1, stop.feeder.getName().replaceAll("[^A-Za-z0-9_-]", "_"),
                            Snapshots.PNG_SUFFIX);
                    File file = Snapshots.write(configuration, frame, shot.image);
                    result.addProperty("frame", file.getName());
                    result.add("pickLocation", McpJson.location(shot.pick));
                    BufferedImage crop = judge(stop, shot, okFraction, marginalFraction, result);
                    String status = result.get("status").getAsString();
                    boolean flagged = !PickCheck.Flag.OK.name().toLowerCase(Locale.ROOT)
                            .equals(status);
                    if (IMAGES_ALL.equals(images) || (flagged && IMAGES_FLAGGED.equals(images))) {
                        crops.add(crop);
                        captions.add(stop.feeder.getName() + ": " + status);
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                catch (Exception e) {
                    result.addProperty("status", STATUS_ERROR);
                    result.addProperty("reason", rootMessage(e));
                    if (e.getCause() instanceof FeedFailedException) {
                        aborted = "Feeding " + stop.feeder.getName() + " failed";
                    }
                }
                counts.merge(result.get("status").getAsString(), 1, Integer::sum);
                results.add(result);
                if (aborted != null) {
                    break;
                }
            }
        }

        JsonObject summary = new JsonObject();
        counts.forEach(summary::addProperty);
        JsonObject result = new JsonObject();
        result.addProperty("visited", visited);
        result.addProperty("planned", route.size());
        if (stopped) {
            result.addProperty("stoppedByOperator", true);
        }
        if (aborted != null) {
            result.addProperty("aborted", aborted);
        }
        result.add("summary", summary);
        result.add("picks", results);
        if (skipped.size() > 0) {
            result.add("skipped", skipped);
        }
        McpToolResult toolResult = McpToolResult.json(result);
        for (int i = 0; i < crops.size(); i++) {
            toolResult = toolResult.withImage(captions.get(i), crops.get(i));
        }
        return toolResult;
    }

    /** A feed that failed may have left the feeder half advanced; the tour goes no further. */
    private static final class FeedFailedException extends Exception {
        private static final long serialVersionUID = 1L;

        FeedFailedException(Exception cause) {
            super(cause);
        }
    }

    /** Runs on the machine thread. */
    private static Shot visit(Machine machine, Camera camera, Stop stop) throws Exception {
        Location pick;
        if (stop.feed) {
            Nozzle nozzle = machine.getDefaultHead().getDefaultNozzle();
            try {
                stop.feeder.feed(nozzle);
            }
            catch (Exception e) {
                throw new FeedFailedException(e);
            }
            finally {
                // A strip feeder's vision lowers the camera to the tape.
                camera.getHead().moveToSafeZ();
            }
            pick = stop.feeder.getPickLocation();
        }
        else {
            pick = FeederLookup.nextPart(stop.feeder);
            if (pick == null) {
                throw new IllegalStateException("The feeder has no parts left");
            }
        }
        Location partTop = pick;
        if (stop.feeder.isPartHeightAbovePickLocation() && stop.part.getHeight() != null) {
            Length height = stop.part.getHeight().convertToUnits(pick.getUnits());
            partTop = pick.add(new Location(pick.getUnits(), 0, 0, height.getValue(), 0));
        }
        camera.getHead().moveToSafeZ();
        camera.moveTo(pick.derive(null, null, Double.NaN, Double.NaN));
        camera.actuateLightBeforeCapture();
        BufferedImage image;
        try {
            image = camera.settleAndCapture();
        }
        finally {
            camera.actuateLightAfterCapture();
        }
        Location unitsPerPixel = camera.getUnitsPerPixel(partTop.getLengthZ())
                .convertToUnits(LengthUnit.Millimeters);
        return new Shot(image, pick, camera.getLocation(), unitsPerPixel);
    }

    /** Fills in the result and returns the annotated crop. */
    static BufferedImage judge(Stop stop, Shot shot, double okFraction,
            double marginalFraction, JsonObject result) {
        Location pick = shot.pick.convertToUnits(LengthUnit.Millimeters);
        Location cameraAt = shot.camera.convertToUnits(LengthUnit.Millimeters);
        double uppX = shot.unitsPerPixel.getX();
        double uppY = shot.unitsPerPixel.getY();
        double centreX = shot.image.getWidth() / 2.0;
        double centreY = shot.image.getHeight() / 2.0;
        // Machine Y points up and image Y down; a counter-clockwise machine angle is clockwise
        // in the image.
        Point expectedPx = new Point(centreX + (pick.getX() - cameraAt.getX()) / uppX,
                centreY - (pick.getY() - cameraAt.getY()) / uppY);
        double cropHalf;
        if (stop.bodyWidth <= 0 || stop.bodyHeight <= 0) {
            result.addProperty("status", STATUS_NO_SIZE);
            result.addProperty("reason", "The package has no footprint body size; see "
                    + "resolve_part_sizes");
            cropHalf = MIN_CROP_PX / 2.0;
            return crop(PickDetector.annotate(shot.image,
                    new RotatedRect(expectedPx, new Size(0, 0), 0), null), expectedPx, cropHalf);
        }
        // A mirrored camera axis has negative units per pixel: sizes use the magnitudes.
        double pixelX = Math.abs(uppX);
        double pixelY = Math.abs(uppY);
        RotatedRect expected = new RotatedRect(expectedPx,
                new Size(stop.bodyWidth / pixelX, stop.bodyHeight / pixelY),
                -pick.getRotation());
        double longPx = Math.max(stop.bodyWidth / pixelX, stop.bodyHeight / pixelY);
        double shortPx = Math.min(stop.bodyWidth / pixelX, stop.bodyHeight / pixelY);
        cropHalf = Math.max(MIN_CROP_PX / 2.0, PickDetector.searchHalfWidth(longPx));

        PickDetector.Detection found = PickDetector.detect(shot.image, longPx, shortPx);
        BufferedImage annotated = PickDetector.annotate(shot.image, expected, found);
        if (found == null) {
            result.addProperty("status", STATUS_NOT_FOUND);
            result.addProperty("reason", "No outline the size of the body near the centre; "
                    + "the part may be missing or the background too like it"
                    + (stop.feeder instanceof NextPickLocatable || stop.feed
                            ? ""
                            : ". This feeder presents a part when fed, so its pocket is empty "
                                    + "after a pick; ask to feed it"));
            return crop(annotated, expectedPx, cropHalf);
        }
        double measuredX = cameraAt.getX() + (found.x - centreX) * uppX;
        double measuredY = cameraAt.getY() - (found.y - centreY) * uppY;
        PickCheck check = PickCheck.evaluate(pick.getX(), pick.getY(), pick.getRotation(),
                measuredX, measuredY, machineAngle(found.longAxisImageDegrees, uppX, uppY),
                stop.bodyWidth,
                stop.bodyHeight, okFraction, marginalFraction, stop.maxPickTolerance);
        result.addProperty("status", check.flag.name().toLowerCase(Locale.ROOT));
        if (check.reason != null) {
            result.addProperty("reason", check.reason);
        }
        JsonObject offset = new JsonObject();
        offset.addProperty("x", McpJson.round(check.dx));
        offset.addProperty("y", McpJson.round(check.dy));
        offset.addProperty("distance", McpJson.round(check.distance));
        offset.addProperty("rotation", McpJson.round(check.angleError));
        result.add("offset", offset);
        JsonObject body = new JsonObject();
        body.addProperty("x", McpJson.round(measuredX));
        body.addProperty("y", McpJson.round(measuredY));
        body.addProperty("longSide", McpJson.round(found.longPx * pixelX));
        body.addProperty("shortSide", McpJson.round(found.shortPx * pixelY));
        body.addProperty("lightOnDark", found.light);
        result.add("foundBody", body);
        return crop(annotated, expectedPx, cropHalf);
    }

    /** An image direction (Y down) as a machine angle, through each axis' signed scale. */
    static double machineAngle(double imageDegrees, double uppX, double uppY) {
        double radians = Math.toRadians(imageDegrees);
        return Math.toDegrees(Math.atan2(-Math.sin(radians) * uppY, Math.cos(radians) * uppX));
    }

    private static BufferedImage crop(BufferedImage image, Point centre, double half) {
        int left = (int) Math.max(0, Math.round(centre.x - half));
        int top = (int) Math.max(0, Math.round(centre.y - half));
        int right = (int) Math.min(image.getWidth(), Math.round(centre.x + half));
        int bottom = (int) Math.min(image.getHeight(), Math.round(centre.y + half));
        if (right <= left || bottom <= top) {
            return image;
        }
        return image.getSubimage(left, top, right - left, bottom - top);
    }

    static Plan plan(Machine machine, Job job, List<String> feederIds, List<String> feedIds) {
        Plan plan = new Plan();
        List<Feeder> feeders = new ArrayList<>();
        if (feederIds != null) {
            for (String id : feederIds) {
                Feeder feeder = machine.getFeeder(id);
                if (feeder == null) {
                    throw new IllegalArgumentException("No feeder " + id);
                }
                feeders.add(feeder);
            }
        }
        else {
            Set<Part> parts = new LinkedHashSet<>();
            for (Placement placement : JobPlacements.enabled(JobPlacements.require(job))) {
                parts.add(placement.getPart());
            }
            for (Feeder feeder : machine.getFeeders()) {
                if (feeder.isEnabled() && parts.contains(feeder.getPart())) {
                    feeders.add(feeder);
                }
            }
            if (feeders.isEmpty()) {
                throw new IllegalArgumentException(
                        "No enabled feeder holds a part of the job's enabled placements");
            }
        }
        Set<String> toFeed = feedIds == null ? Set.of() : new LinkedHashSet<>(feedIds);
        for (String id : toFeed) {
            if (feeders.stream().noneMatch(feeder -> feeder.getId().equals(id))) {
                throw new IllegalArgumentException(id + " in " + FEED_FEEDER_IDS
                        + " is not one of the feeders to visit");
            }
        }

        for (Feeder feeder : feeders) {
            Part part = feeder.getPart();
            if (part == null || part.getPackage() == null) {
                plan.skipped.add(skip(feeder, "The feeder has no part with a package"));
                continue;
            }
            Location pick;
            try {
                pick = FeederLookup.nextPart(feeder);
            }
            catch (Exception e) {
                plan.skipped.add(skip(feeder, "No pick location: " + rootMessage(e)));
                continue;
            }
            if (pick == null) {
                plan.skipped.add(skip(feeder, "No parts left"));
                continue;
            }
            if (!Double.isFinite(pick.getX()) || !Double.isFinite(pick.getY())) {
                plan.skipped.add(skip(feeder, "No pick location"));
                continue;
            }
            Package pkg = part.getPackage();
            Footprint footprint = pkg.getFootprint();
            double width = 0;
            double height = 0;
            if (footprint != null) {
                width = McpJson.millimetres(footprint.getBodyWidth(), footprint.getUnits());
                height = McpJson.millimetres(footprint.getBodyHeight(), footprint.getUnits());
            }
            plan.stops.add(new Stop(feeder, part, toFeed.contains(feeder.getId()), pick, width,
                    height, maxPickTolerance(pkg)));
        }
        return plan;
    }

    /** The tightest of the compatible nozzle tips', in mm, or 0 when none states one. */
    static double maxPickTolerance(Package pkg) {
        double tightest = 0;
        for (NozzleTip tip : pkg.getCompatibleNozzleTips()) {
            if (tip instanceof ReferenceNozzleTip) {
                Length tolerance = ((ReferenceNozzleTip) tip).getMaxPickTolerance();
                double mm = tolerance == null ? 0 : McpJson.millimetres(tolerance);
                if (mm > 0 && (tightest == 0 || mm < tightest)) {
                    tightest = mm;
                }
            }
        }
        return tightest;
    }

    /** Greedy nearest neighbour from the start: short enough for a few dozen feeders. */
    static <T> List<T> nearestNeighbourRoute(Location start, List<T> items,
            Function<T, Location> location) {
        List<T> remaining = new ArrayList<>(items);
        List<T> route = new ArrayList<>();
        Location here = start;
        while (!remaining.isEmpty()) {
            int nearest = 0;
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                double distance = here.getLinearDistanceTo(location.apply(remaining.get(i)));
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = i;
                }
            }
            T next = remaining.remove(nearest);
            route.add(next);
            here = location.apply(next);
        }
        return route;
    }

    private static String describe(Camera camera, List<Stop> route) {
        StringBuilder stops = new StringBuilder();
        List<String> fed = new ArrayList<>();
        for (int i = 0; i < route.size(); i++) {
            Stop stop = route.get(i);
            Location mm = stop.plannedPick.convertToUnits(LengthUnit.Millimeters);
            stops.append(String.format(Locale.ROOT, "%s%d. %s at X %.2f, Y %.2f", i == 0 ? "" : "; ",
                    i + 1, stop.feeder.getName(), mm.getX(), mm.getY()));
            if (stop.feed) {
                fed.add(stop.feeder.getName());
            }
        }
        String feeds = fed.isEmpty()
                ? " Nothing is fed or picked."
                : " Feeds " + String.join(", ", fed) + " at its stop before looking: a feed "
                        + "advances the feeder and may move the head, e.g. a strip feeder's "
                        + "vision lowers the camera to the tape; the head returns to safe Z "
                        + "after. Nothing is picked.";
        return String.format(Locale.ROOT,
                "Requested at %s: tour camera \"%s\" over %d feeders' next parts, moving at "
                        + "safe Z, capturing with its light at each: %s."
                        + "%s Rotation is unchanged. A progress window lets you pause or stop "
                        + "between feeders.",
                LocalTime.now().truncatedTo(ChronoUnit.SECONDS), camera.getName(), route.size(),
                stops, feeds);
    }

    private static JsonObject describeStop(Stop stop) {
        JsonObject json = new JsonObject();
        json.addProperty("feederId", stop.feeder.getId());
        json.addProperty("feederName", stop.feeder.getName());
        json.addProperty("partId", stop.part.getId());
        json.addProperty("packageId", stop.part.getPackage().getId());
        if (stop.feed) {
            json.addProperty("fed", true);
        }
        return json;
    }

    private static JsonObject skip(Feeder feeder, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("feederId", feeder.getId());
        json.addProperty("feederName", feeder.getName());
        json.addProperty("reason", reason);
        return json;
    }

    /** Why the machine can't take the next move, or null. Called off the EDT. */
    private String notReady(Machine machine) throws Exception {
        if (!machine.isEnabled() || !machine.isHomed()) {
            return "The machine is no longer enabled and homed";
        }
        if (machine.isBusy()) {
            return "The machine is busy";
        }
        if (onEdt(jobActive::getAsBoolean)) {
            return "A job is running or paused";
        }
        return null;
    }

    private static void requireReady(Machine machine) {
        if (!machine.isEnabled() || !machine.isHomed()) {
            throw new IllegalArgumentException("The machine must be enabled and homed");
        }
        if (machine.isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
    }

    private static double fraction(JsonObject arguments, String name, double fallback) {
        if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
            return fallback;
        }
        double value = McpJson.requiredNumber(arguments, name);
        if (value <= 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be more than 0 and at most 1");
        }
        return value;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return task.call();
        }
        FutureTask<T> future = new FutureTask<>(task);
        SwingUtilities.invokeLater(future);
        try {
            return future.get();
        }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }
}
