/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference.feeder;

import java.util.ArrayList;
import java.util.List;

import javax.swing.Action;

import org.apache.commons.io.IOUtils;
import org.opencv.core.RotatedRect;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.feeder.wizards.ReferenceLoosePartFeederConfigurationWizard;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.PipelineHolder;
import org.simpleframework.xml.Element;

public class ReferenceLoosePartFeeder extends ReferenceFeeder implements PipelineHolder {
    /** Detections after the first, each from straight above the part found last. */
    private static final int CENTRING_PASSES = 2;
    /**
     * Without a part size, neighbouring search frames are at most half a frame apart, so a part up
     * to half a frame across lies wholly inside at least one of them.
     */
    private static final double DEFAULT_FRAME_STEP_FRACTION = 0.5;
    /**
     * A search that finds nothing is repeated once before the feeder reports itself empty, so one
     * bad frame doesn't disable it.
     */
    private static final int SEARCH_PASSES = 2;
    private static final double STEP_TOLERANCE = 1e-9;

    @Element(required = false)
    private CvPipeline pipeline = createDefaultPipeline();

    // Volatile: set on the EDT by the wizard, read on the machine thread by feed().
    /** Along machine X; zero searches one camera frame at the location. */
    @Element(required = false)
    private volatile Length searchWidth = new Length(0, LengthUnit.Millimeters);

    // Volatile: set on the EDT by the wizard, read on the machine thread by feed().
    /** Along machine Y. */
    @Element(required = false)
    private volatile Length searchHeight = new Length(0, LengthUnit.Millimeters);

    // Volatile: set on the EDT by the wizard, read on the machine thread by feed().
    /**
     * The largest X or Y extent of a part as laid out; frames overlap by at least this much. Zero
     * spaces frames half a frame apart.
     */
    @Element(required = false)
    private volatile Length partSize = new Length(0, LengthUnit.Millimeters);

    private Location pickLocation;

    // Machine thread only.
    /** The search frame that last held a part, searched first next time. */
    private int lastFoundFrame;

    @Override
    public Location getPickLocation() throws Exception {
        return pickLocation == null ? location : pickLocation;
    }

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        Camera camera = nozzle.getHead()
                              .getDefaultCamera();
        try (CvPipeline pipeline = getPipeline()) {
            pickLocation = findPart(pipeline, camera, nozzle);
            for (int i = 0; i < CENTRING_PASSES; i++) {
                camera.moveTo(pickLocation.derive(null, null, null, 0.0));
                Location centred = locatePart(pipeline, camera, nozzle);
                if (centred == null) {
                    throw new Exception("Feeder " + getName() + ": lost the part while centring on it.");
                }
                pickLocation = centred;
            }
            camera.moveTo(pickLocation.derive(null, null, null, 0.0));
            MainFrame.get()
                     .getCameraViews()
                     .getCameraView(camera)
                     .showFilteredImage(OpenCvUtils.toBufferedImage(pipeline.getWorkingImage()),
                             1000);
        }
    }

    @Override
    public boolean canApplyPickRotation() {
        return true;
    }

    /**
     * Vision finds each part's angle and adds the location rotation to it, so the location rotation
     * is the pick rotation of a part found square: the convention for how parts are laid out.
     */
    @Override
    public void applyPickRotation(double rotation) {
        setLocation(location.derive(null, null, null, rotation));
    }

    @Override
    public boolean isPartHeightAbovePickLocation() {
        return true;
    }

    /**
     * Looks through the search frames, starting with the one that held a part last time.
     *
     * @throws FeederEmptyException if no frame holds a part.
     */
    private Location findPart(CvPipeline pipeline, Camera camera, Nozzle nozzle) throws Exception {
        List<Location> frames = getSearchFrames(camera);
        for (int pass = 0; pass < SEARCH_PASSES; pass++) {
            for (int i = 0; i < frames.size(); i++) {
                int frame = (lastFoundFrame + i) % frames.size();
                MovableUtils.moveToLocationAtSafeZ(camera, frames.get(frame));
                Location found = locatePart(pipeline, camera, nozzle);
                if (found != null) {
                    lastFoundFrame = frame;
                    return found;
                }
            }
        }
        throw new FeederEmptyException("Feeder " + getName() + ": no parts found"
                + (frames.size() > 1 ? " in its search area." : "."));
    }

    /**
     * The camera positions that cover the search area, centred on the location, in rows along X.
     */
    public List<Location> getSearchFrames(Camera camera) {
        Location unitsPerPixel = camera.getUnitsPerPixel(location.getLengthZ())
                .convertToUnits(location.getUnits());
        double frameWidth = camera.getWidth() * Math.abs(unitsPerPixel.getX());
        double frameHeight = camera.getHeight() * Math.abs(unitsPerPixel.getY());
        double part = partSize.convertToUnits(location.getUnits()).getValue();
        List<Location> frames = new ArrayList<>();
        for (double dy : frameOffsets(searchHeight.convertToUnits(location.getUnits()).getValue(),
                frameHeight, part)) {
            for (double dx : frameOffsets(
                    searchWidth.convertToUnits(location.getUnits()).getValue(), frameWidth,
                    part)) {
                frames.add(location.add(new Location(location.getUnits(), dx, dy, 0, 0)));
            }
        }
        return frames;
    }

    /**
     * Offsets from the centre of evenly spaced frames that span the search length, overlapping by
     * at least the part size so every part lies wholly inside some frame.
     */
    private static List<Double> frameOffsets(double search, double frame, double part) {
        List<Double> offsets = new ArrayList<>();
        if (search <= frame) {
            offsets.add(0.0);
            return offsets;
        }
        if (part >= frame) {
            throw new IllegalStateException(String.format(
                    "A part of %.2f must fit in a camera frame of %.2f to be found", part, frame));
        }
        double maxStep = part > 0 ? frame - part : frame * DEFAULT_FRAME_STEP_FRACTION;
        double span = search - frame;
        // The tolerance stops rounding error in an exact division adding a frame.
        int steps = (int) Math.ceil(span / maxStep - STEP_TOLERANCE);
        for (int i = 0; i <= steps; i++) {
            offsets.add(-span / 2 + span * i / steps);
        }
        return offsets;
    }

    /**
     * @return the part closest to the camera centre, or null when the frame holds none.
     */
    private Location locatePart(CvPipeline pipeline, Camera camera, Nozzle nozzle)
            throws Exception {
        preparePipeline(pipeline, camera, nozzle);
        pipeline.process();
        List<RotatedRect> results = pipeline.getExpectedResult(VisionUtils.PIPELINE_RESULTS_NAME)
                .getExpectedListModel(RotatedRect.class, null);
        if (results.isEmpty()) {
            return null;
        }

        // Find the closest result
        results.sort((a, b) -> {
            Double da = VisionUtils.getPixelLocation(camera, a.center.x, a.center.y)
                                   .getLinearDistanceTo(camera.getLocation());
            Double db = VisionUtils.getPixelLocation(camera, b.center.x, b.center.y)
                                   .getLinearDistanceTo(camera.getLocation());
            return da.compareTo(db);
        });
        RotatedRect result = results.get(0);
        // Get the result's Location
        Location location = VisionUtils.getPixelLocation(camera, result.center.x, result.center.y);
        // Update the location's rotation with the result's angle
        location = location.derive(null, null, null, result.angle + this.location.getRotation());
        // Update the location with the correct Z, which is the configured Location's Z.
        double z = this.location.convertToUnits(location.getUnits()).getZ(); 
        location = location.derive(null, null, z, null);
        return location;
    }

    public Length getSearchWidth() {
        return searchWidth;
    }

    public void setSearchWidth(Length searchWidth) {
        Object oldValue = this.searchWidth;
        this.searchWidth = searchWidth;
        firePropertyChange("searchWidth", oldValue, searchWidth);
    }

    public Length getPartSize() {
        return partSize;
    }

    public void setPartSize(Length partSize) {
        Object oldValue = this.partSize;
        this.partSize = partSize;
        firePropertyChange("partSize", oldValue, partSize);
    }

    public Length getSearchHeight() {
        return searchHeight;
    }

    public void setSearchHeight(Length searchHeight) {
        Object oldValue = this.searchHeight;
        this.searchHeight = searchHeight;
        firePropertyChange("searchHeight", oldValue, searchHeight);
    }

    /**
     * Returns if the feeder can take back a part.
     * Makes the assumption, that after each feed a pick followed,
     * so the area around the pickLocation is now empty.
     */
    @Override
    public boolean canTakeBackPart() {
        return (pickLocation != null);
    }

    @Override
    public void takeBackPart(Nozzle nozzle) throws Exception {
        super.takeBackPart(nozzle);
        putPartBack(nozzle);
        // set pickLocation to null, avoid putting a second part on the same location
        pickLocation = null;
    }
    
    @Override
    public CvPipeline getPipeline() {
        return pipeline;
    }

    /** Prepares as a feed with the head's default nozzle would. */
    @Override
    public void preparePipeline(CvPipeline pipeline, Camera camera) throws Exception {
        Head head = camera.getHead() != null
                ? camera.getHead()
                : Configuration.get().getMachine().getDefaultHead();
        preparePipeline(pipeline, camera, head.getDefaultNozzle());
    }

    private void preparePipeline(CvPipeline pipeline, Camera camera, Nozzle nozzle) {
        pipeline.setProperty("camera", camera);
        pipeline.setProperty("nozzle", nozzle);
        pipeline.setProperty("feeder", this);
    }

    public void resetPipeline() {
        pipeline = createDefaultPipeline();
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new ReferenceLoosePartFeederConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName() + " " + getName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        return new PropertySheet[] {new PropertySheetWizardAdapter(getConfigurationWizard())};
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return null;
    }

    public static CvPipeline createDefaultPipeline() {
        try {
            String xml = IOUtils.toString(ReferenceLoosePartFeeder.class.getResource(
                    "ReferenceLoosePartFeeder-DefaultPipeline.xml"));
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }
}
