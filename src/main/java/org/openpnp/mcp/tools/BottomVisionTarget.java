package org.openpnp.mcp.tools;

import org.opencv.core.RotatedRect;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.mcp.McpJson;
import org.openpnp.model.AbstractVisionSettings;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;
import org.openpnp.spi.PartAlignment;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.PipelineHolder;

import com.google.gson.JsonObject;

/**
 * Bottom vision settings as a pipeline holder, prepared and interpreted as the first pass of a
 * ReferenceBottomVision alignment of a part at the given angle. Only single-shot alignment is
 * supported, and nothing moves.
 */
final class BottomVisionTarget implements PipelineHolder {
    static final String PART_ID = "partId";
    static final String VISION_SETTINGS_ID = "visionSettingsId";
    static final String NOZZLE = "nozzle";
    static final String ANGLE = "angle";
    private static final String LEGACY_RESULTS_NAME = "result";
    /** Set by ReferenceBottomVision.preparePipeline only for an advanced compositing shot. */
    private static final String ADVANCED_SHOT_PROPERTY = "partmask.center";
    private static final double HALF_TURN_DEGREES = 180;

    private final Machine machine;
    private final ReferenceBottomVision bottomVision;
    private final BottomVisionSettings settings;
    /** Null when only the settings were named, which is enough to read or edit them. */
    private final Part part;
    private final String nozzleName;
    private final double angle;

    private BottomVisionTarget(Machine machine, ReferenceBottomVision bottomVision,
            BottomVisionSettings settings, Part part, String nozzleName, double angle) {
        this.machine = machine;
        this.bottomVision = bottomVision;
        this.settings = settings;
        this.part = part;
        this.nozzleName = nozzleName;
        this.angle = angle;
    }

    static boolean isNamed(JsonObject arguments) {
        return McpJson.optionalString(arguments, PART_ID) != null
                || McpJson.optionalString(arguments, VISION_SETTINGS_ID) != null;
    }

    /**
     * A part's effective bottom vision settings, or the named settings (with the part's package
     * as context, if a part is named too).
     */
    static BottomVisionTarget require(Configuration configuration, JsonObject arguments) {
        Machine machine = configuration.getMachine();
        ReferenceBottomVision bottomVision = requireBottomVision(machine);
        Part part = null;
        String partId = McpJson.optionalString(arguments, PART_ID);
        if (partId != null) {
            part = configuration.getPart(partId);
            if (part == null) {
                throw new IllegalArgumentException("No part " + partId);
            }
        }
        BottomVisionSettings settings;
        String settingsId = McpJson.optionalString(arguments, VISION_SETTINGS_ID);
        if (settingsId != null) {
            AbstractVisionSettings named = configuration.getVisionSettings(settingsId);
            if (!(named instanceof BottomVisionSettings)) {
                throw new IllegalArgumentException("No bottom vision settings " + settingsId);
            }
            settings = (BottomVisionSettings) named;
        }
        else {
            settings = bottomVision.getInheritedVisionSettings(part);
            if (settings == null) {
                throw new IllegalArgumentException("Part " + partId + " has no bottom vision settings");
            }
        }
        double angle = arguments.has(ANGLE) && !arguments.get(ANGLE).isJsonNull()
                ? McpJson.requiredRotation(arguments, ANGLE)
                : 0;
        return new BottomVisionTarget(machine, bottomVision, settings, part,
                McpJson.optionalString(arguments, NOZZLE), angle);
    }

    private static ReferenceBottomVision requireBottomVision(Machine machine) {
        for (PartAlignment alignment : machine.getPartAlignments()) {
            if (alignment instanceof ReferenceBottomVision) {
                return (ReferenceBottomVision) alignment;
            }
        }
        throw new IllegalArgumentException("The machine has no bottom vision");
    }

    @Override
    public CvPipeline getPipeline() {
        return settings.getPipeline();
    }

    Camera getCamera() {
        for (Camera camera : machine.getCameras()) {
            if (camera.getLooking() == Camera.Looking.Up) {
                return camera;
            }
        }
        throw new IllegalArgumentException("The machine has no up-looking camera");
    }

    @Override
    public void preparePipeline(CvPipeline pipeline, Camera camera) throws Exception {
        if (part == null) {
            throw new IllegalArgumentException(
                    "Running bottom vision needs a partId: its package gives the footprint");
        }
        Package pkg = part.getPackage();
        if (pkg == null) {
            throw new IllegalArgumentException("Part " + part.getId() + " has no package");
        }
        if (part.isPartHeightUnknown()) {
            throw new IllegalArgumentException("Part " + part.getId()
                    + " has no height. Set it first: finding it would move the nozzle to focus");
        }
        if (!isPreRotate() && angle != 0) {
            throw new IllegalArgumentException("Bottom vision settings " + settings.getName()
                    + " align post-rotate, always at 0°: pass angle 0, or switch pre-rotate on");
        }
        bottomVision.prepareStandalonePipeline(pipeline, settings.getPipelineParameterAssignments(),
                camera, requireNozzle(pkg), part, pkg, getWantedAngle(), settings);
        if (pipeline.getPipelineShotsCount() > 1) {
            throw new IllegalArgumentException("Vision compositing needs "
                    + pipeline.getPipelineShotsCount() + " shots for package " + pkg.getId()
                    + "; only single-shot alignment can run through the MCP server");
        }
        // An advanced shot is taken with the nozzle moved off the camera centre.
        if (pipeline.getProperty(ADVANCED_SHOT_PROPERTY) != null
                || isOffset(pipeline.getProperty("footprint.xOffset"))
                || isOffset(pipeline.getProperty("footprint.yOffset"))) {
            throw new IllegalArgumentException("Vision compositing takes an off-centre shot of "
                    + "package " + pkg.getId() + ", which the MCP server can't run");
        }
    }

    private static boolean isOffset(Object length) {
        return length instanceof Length && ((Length) length).getValue() != 0;
    }

    private boolean isPreRotate() {
        return bottomVision.usesPreRotate(settings);
    }

    /** As findOffsetsPreRotate normalises a placement angle; post-rotate always aligns at 0°. */
    private double getWantedAngle() {
        return isPreRotate() ? Utils2D.angleNorm(angle, HALF_TURN_DEGREES) : 0;
    }

    /**
     * The named nozzle, else one holding the part, else one with a nozzle tip loaded that suits the
     * package. Its tip and Z set the context; it doesn't have to be over the camera.
     */
    private Nozzle requireNozzle(Package pkg) {
        if (nozzleName != null) {
            for (Head head : machine.getHeads()) {
                for (Nozzle nozzle : head.getNozzles()) {
                    if (nozzle.getName().equals(nozzleName)) {
                        return nozzle;
                    }
                }
            }
            throw new IllegalArgumentException("No nozzle " + nozzleName);
        }
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getPart() == part) {
                    return nozzle;
                }
            }
        }
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                NozzleTip tip = nozzle.getNozzleTip();
                if (tip != null && pkg.getCompatibleNozzleTips().contains(tip)) {
                    return nozzle;
                }
            }
        }
        throw new IllegalArgumentException("No nozzle has a nozzle tip loaded that suits package "
                + pkg.getId() + "; load one or name a nozzle");
    }

    JsonObject describe() {
        JsonObject json = new JsonObject();
        json.addProperty("id", settings.getId());
        json.addProperty("name", settings.getName());
        json.add("visionOffset", McpJson.location(settings.getVisionOffset()));
        json.addProperty("maxRotation", settings.getMaxRotation().name());
        json.addProperty("preRotate", isPreRotate());
        if (part != null) {
            json.addProperty(PART_ID, part.getId());
            json.addProperty(ANGLE, angle);
        }
        return json;
    }

    /**
     * Interprets the pipeline result as one pass of ReferenceBottomVision does, relative to the
     * nozzle at the camera centre: the detected feature's offset, the angle offset, and the part
     * centre's offset once the vision offset is subtracted. Pre-rotate also says whether the pass
     * is accepted or a real alignment would measure again at the corrected position.
     */
    JsonObject describeAlignment(CvPipeline pipeline, Camera camera) throws Exception {
        JsonObject json = new JsonObject();
        CvStage.Result result = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
        if (result == null) {
            result = pipeline.getResult(LEGACY_RESULTS_NAME);
        }
        if (result == null || !(result.model instanceof RotatedRect)) {
            json.addProperty("error", "No RotatedRect result in a stage named "
                    + VisionUtils.PIPELINE_RESULTS_NAME);
            return json;
        }
        CvPipeline.PipelineShot shot = pipeline.getCurrentPipelineShot();
        shot.processResult(result);
        RotatedRect rect = (RotatedRect) shot.processCompositeResult().getModel();

        double wantedAngle = getWantedAngle();
        Location featureOffset = VisionUtils.getPixelCenterOffsets(camera, rect.center.x,
                rect.center.y);
        double angleOffset = ReferenceBottomVision.normaliseAngleOffset(
                VisionUtils.getPixelAngle(camera, rect.angle) - wantedAngle, settings);
        Location partOffset;
        double footprintRotation;
        if (isPreRotate()) {
            footprintRotation = wantedAngle;
            // The nozzle turns by the angle offset before placing, swinging an off-centre part.
            Location offsets = featureOffset.rotateXy(-angleOffset)
                    .derive(null, null, null, angleOffset);
            json.addProperty("firstPassAccepted",
                    bottomVision.isFixAccepted(rect, offsets, angleOffset, camera));
            partOffset = offsets.subtract(settings.getVisionOffset().rotateXy(wantedAngle));
        }
        else {
            footprintRotation = angleOffset;
            partOffset = featureOffset.subtract(settings.getVisionOffset().rotateXy(angleOffset));
        }
        Location pickDeviation = partOffset
                .subtract(ReferenceBottomVision.getNominalOffsets(part, footprintRotation))
                .convertToUnits(LengthUnit.Millimeters);
        featureOffset = featureOffset.convertToUnits(LengthUnit.Millimeters);
        partOffset = partOffset.convertToUnits(LengthUnit.Millimeters);
        json.addProperty("wantedAngle", wantedAngle);
        json.addProperty("featureXMm", McpJson.round(featureOffset.getX()));
        json.addProperty("featureYMm", McpJson.round(featureOffset.getY()));
        json.addProperty("angleOffset", McpJson.round(angleOffset));
        json.addProperty("partXMm", McpJson.round(partOffset.getX()));
        json.addProperty("partYMm", McpJson.round(partOffset.getY()));
        json.addProperty("pickDeviationXMm", McpJson.round(pickDeviation.getX()));
        json.addProperty("pickDeviationYMm", McpJson.round(pickDeviation.getY()));
        return json;
    }
}
