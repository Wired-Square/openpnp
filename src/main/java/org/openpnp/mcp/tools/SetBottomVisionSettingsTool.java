package org.openpnp.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.openpnp.machine.reference.vision.ReferenceBottomVision.MaxRotation;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PartSizeCheckMethod;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PreRotateUsage;
import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.PartSettingsHolder;

import com.google.gson.JsonObject;

public final class SetBottomVisionSettingsTool implements McpTool {
    static final String ENABLED = "enabled";
    static final String PRE_ROTATE_USAGE = "preRotateUsage";
    static final String MAX_ROTATION = "maxRotation";
    static final String CHECK_PART_SIZE_METHOD = "checkPartSizeMethod";
    static final String CHECK_SIZE_TOLERANCE_PERCENT = "checkSizeTolerancePercent";
    static final String ASYMMETRIC = "asymmetric";
    static final String VISION_OFFSET_X = "visionOffsetX";
    static final String VISION_OFFSET_Y = "visionOffsetY";
    private static final String[] FIELDS = {ENABLED, PRE_ROTATE_USAGE, MAX_ROTATION,
            CHECK_PART_SIZE_METHOD, CHECK_SIZE_TOLERANCE_PERCENT, ASYMMETRIC, VISION_OFFSET_X,
            VISION_OFFSET_Y};
    /** 0 % would reject every measurement; past 100 % the lower limit goes negative. */
    static final int MIN_TOLERANCE_PERCENT = 1;
    static final int MAX_TOLERANCE_PERCENT = 100;

    private final Supplier<Configuration> configuration;

    public SetBottomVisionSettingsTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "set_bottom_vision_settings";
    }

    @Override
    public String getDescription() {
        return "Sets fields of a part's own bottom vision settings, as its Vision tab would: "
                + "enabled, pre-rotate usage, max rotation, the part size check and the vision "
                + "offset. Give only the fields to change. Settings used by anything but that one "
                + "part (stock, default or package settings, or shared by several parts) are "
                + "refused: specialise them for the part first in its Vision tab. The size check "
                + "compares the detected rectangle with the package's body size (BodySize, set it "
                + "with set_package_body_size) or pad extents (PadExtents). A vision offset (mm, "
                + "from the part's centre to the detected centre at 0°, for parts asymmetric by "
                + "design) needs asymmetric on; "
                + "switching asymmetric off clears it. Returns the settings before and after. "
                + "Nothing moves.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string(BottomVisionTarget.PART_ID, "A part id; its own bottom vision settings")
                .string(BottomVisionTarget.VISION_SETTINGS_ID,
                        "Bottom vision settings id, instead of or as well as the part")
                .bool(ENABLED, "Whether bottom vision aligns parts with these settings")
                .oneOf(PRE_ROTATE_USAGE, "Rotate to the placement angle before aligning",
                        names(PreRotateUsage.values()))
                .oneOf(MAX_ROTATION, "Adjust folds the measured angle into ±45°, for parts "
                        + "picked within 45° whose result has no orientation; Full corrects up to "
                        + "±180°, for pipelines that detect orientation (e.g. pin 1)",
                        names(MaxRotation.values()))
                .oneOf(CHECK_PART_SIZE_METHOD, "What the detected size is checked against",
                        names(PartSizeCheckMethod.values()))
                .integer(CHECK_SIZE_TOLERANCE_PERCENT, "Size check tolerance, "
                        + MIN_TOLERANCE_PERCENT + "–" + MAX_TOLERANCE_PERCENT + " %")
                .bool(ASYMMETRIC, "Whether the detected centre is offset from the part's centre")
                .number(VISION_OFFSET_X, "Vision offset X in mm")
                .number(VISION_OFFSET_Y, "Vision offset Y in mm")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.WRITE;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Configuration configuration = this.configuration.get();
        BottomVisionTarget target = BottomVisionTarget.require(configuration, arguments);
        BottomVisionSettings settings = target.getSettings();
        Part owner = requireSoleOwner(settings, target.getPart());
        Change change = Change.parse(arguments, settings);
        change.requireSizeReference(owner);
        // Alignment reads the settings on the machine thread.
        if (configuration.getMachine().isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }

        JsonObject result = new JsonObject();
        result.add("before", target.describe());
        change.apply(settings);
        result.add("after", target.describe());
        return McpToolResult.json(result);
    }

    /** The one part that uses the settings; anything else would change other parts' alignment. */
    private static Part requireSoleOwner(BottomVisionSettings settings, Part named) {
        List<PartSettingsHolder> users = settings.getUsedBottomVisionIn();
        String specialise = named == null
                ? ""
                : ": specialise them for part " + named.getId() + " first in its Vision tab";
        if (users.size() != 1 || !(users.get(0) instanceof Part)) {
            List<String> names = new ArrayList<>();
            for (PartSettingsHolder user : users) {
                names.add(user.getClass().getSimpleName() + " " + user.getId());
            }
            throw new IllegalArgumentException("Bottom vision settings " + settings.getName()
                    + " are used by " + (names.isEmpty() ? "nothing" : String.join(", ", names))
                    + ", not by one part alone" + specialise);
        }
        Part owner = (Part) users.get(0);
        if (named != null && named != owner) {
            throw new IllegalArgumentException("Bottom vision settings " + settings.getName()
                    + " belong to part " + owner.getId() + ", not " + named.getId());
        }
        return owner;
    }

    private static String[] names(Enum<?>[] values) {
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].name();
        }
        return names;
    }

    /** The requested fields, all validated before any is applied. */
    private static final class Change {
        private Boolean enabled;
        private PreRotateUsage preRotateUsage;
        private MaxRotation maxRotation;
        private PartSizeCheckMethod checkPartSizeMethod;
        private Integer tolerancePercent;
        private Boolean asymmetric;
        private Location visionOffset;
        private PartSizeCheckMethod effectiveCheck;

        static Change parse(JsonObject arguments, BottomVisionSettings settings) {
            Change change = new Change();
            boolean any = false;
            for (String field : FIELDS) {
                any |= arguments.has(field) && !arguments.get(field).isJsonNull();
            }
            if (!any) {
                throw new IllegalArgumentException("Give at least one field to change");
            }
            if (has(arguments, ENABLED)) {
                change.enabled = McpJson.optionalBoolean(arguments, ENABLED, true);
            }
            change.preRotateUsage = optionalEnum(arguments, PRE_ROTATE_USAGE,
                    PreRotateUsage.class);
            change.maxRotation = optionalEnum(arguments, MAX_ROTATION, MaxRotation.class);
            change.checkPartSizeMethod = optionalEnum(arguments, CHECK_PART_SIZE_METHOD,
                    PartSizeCheckMethod.class);
            change.effectiveCheck = change.checkPartSizeMethod != null
                    ? change.checkPartSizeMethod
                    : settings.getCheckPartSizeMethod();
            if (has(arguments, CHECK_SIZE_TOLERANCE_PERCENT)) {
                double percent = McpJson.requiredNumber(arguments, CHECK_SIZE_TOLERANCE_PERCENT);
                if (percent != Math.rint(percent) || percent < MIN_TOLERANCE_PERCENT
                        || percent > MAX_TOLERANCE_PERCENT) {
                    throw new IllegalArgumentException(CHECK_SIZE_TOLERANCE_PERCENT
                            + " must be a whole number from " + MIN_TOLERANCE_PERCENT + " to "
                            + MAX_TOLERANCE_PERCENT);
                }
                change.tolerancePercent = (int) percent;
            }
            if (has(arguments, ASYMMETRIC)) {
                change.asymmetric = McpJson.optionalBoolean(arguments, ASYMMETRIC, false);
            }
            boolean offsetGiven = has(arguments, VISION_OFFSET_X) || has(arguments, VISION_OFFSET_Y);
            if (offsetGiven) {
                boolean asymmetricAfter = change.asymmetric != null
                        ? change.asymmetric
                        : settings.isAsymmetric();
                if (!asymmetricAfter) {
                    throw new IllegalArgumentException("A vision offset needs " + ASYMMETRIC
                            + " on");
                }
                Location current = settings.getVisionOffset()
                        .convertToUnits(LengthUnit.Millimeters);
                change.visionOffset = new Location(LengthUnit.Millimeters,
                        offset(arguments, VISION_OFFSET_X, current.getX()),
                        offset(arguments, VISION_OFFSET_Y, current.getY()), 0, 0);
            }
            return change;
        }

        /** A size check against a zero size fails every alignment. */
        void requireSizeReference(Part owner) {
            if (effectiveCheck == PartSizeCheckMethod.Disabled
                    || (checkPartSizeMethod == null && tolerancePercent == null)) {
                return;
            }
            if (owner.getPackage() == null) {
                throw new IllegalArgumentException("Part " + owner.getId() + " has no package "
                        + "to check its size against");
            }
            Footprint footprint = owner.getPackage().getFootprint();
            if (effectiveCheck == PartSizeCheckMethod.BodySize
                    && (footprint.getBodyWidth() <= 0 || footprint.getBodyHeight() <= 0)) {
                throw new IllegalArgumentException("Package " + owner.getPackage().getId()
                        + " has no body size; set it with set_package_body_size first");
            }
            if (effectiveCheck == PartSizeCheckMethod.PadExtents
                    && footprint.getPads().isEmpty()) {
                throw new IllegalArgumentException("Package " + owner.getPackage().getId()
                        + " has no pads to take the extents of");
            }
        }

        void apply(BottomVisionSettings settings) {
            if (enabled != null) {
                settings.setEnabled(enabled);
            }
            if (preRotateUsage != null) {
                settings.setPreRotateUsage(preRotateUsage);
            }
            if (maxRotation != null) {
                settings.setMaxRotation(maxRotation);
            }
            if (checkPartSizeMethod != null) {
                settings.setCheckPartSizeMethod(checkPartSizeMethod);
            }
            if (tolerancePercent != null) {
                settings.setCheckSizeTolerancePercent(tolerancePercent);
            }
            // Switching asymmetric off clears the offset, so it goes before a new offset.
            if (asymmetric != null) {
                settings.setAsymmetric(asymmetric);
            }
            if (visionOffset != null) {
                settings.setVisionOffset(visionOffset);
            }
        }

        private static boolean has(JsonObject arguments, String name) {
            return arguments.has(name) && !arguments.get(name).isJsonNull();
        }

        private static double offset(JsonObject arguments, String name, double current) {
            if (!has(arguments, name)) {
                return current;
            }
            double value = McpJson.requiredNumber(arguments, name);
            if (Math.abs(value) > SetPackageBodySizeTool.MAX_BODY_MM) {
                throw new IllegalArgumentException(String.format(
                        "%s must be within ±%.0f mm", name, SetPackageBodySizeTool.MAX_BODY_MM));
            }
            return value;
        }

        private static <E extends Enum<E>> E optionalEnum(JsonObject arguments, String name,
                Class<E> type) {
            String value = McpJson.optionalString(arguments, name);
            if (value == null) {
                return null;
            }
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(value)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException(name + " must be one of "
                    + String.join(", ", names(type.getEnumConstants())));
        }
    }
}
