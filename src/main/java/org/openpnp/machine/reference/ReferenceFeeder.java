package org.openpnp.machine.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.openpnp.Translations;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.base.AbstractFeeder;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.FeedersPanel;
import org.pmw.tinylog.Logger;

public abstract class ReferenceFeeder extends AbstractFeeder {
    @Element
    protected Location location = new Location(LengthUnit.Millimeters);

    @Attribute(required=false)
    protected FeedOptions feedOptions = FeedOptions.Normal;

    /**
     * Additional feed options which enable feeder implement skipping physical movement.
     * The implementation is on feeder itself as even movement is disabled then still
     * a vision may be requested
     */
    public enum FeedOptions {
        Normal("Normal feed"),
        SkipNext("Skip next feed"),
        Disable("Disable feed");

        private String name;

        FeedOptions(String name) {
            this.name = name;
        }

        public String toString() {
            return name;
        }
    }

    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        Object oldValue = this.location;
        this.location = location;
        firePropertyChange("location", oldValue, location);
    }

    /**
     * @return True if feeder supports FeedOptions
     */
    public boolean supportsFeedOptions() {
        return false;
    }

    @Override
    public Location getJobPreparationLocation()  {
        // the default RefrenceFeeder has no prep. location
        return null;
    }

    @Override
    public void prepareForJob(boolean visit) throws Exception {
        // the default RefrenceFeeder needs no prep.
    }
    public FeedOptions getFeedOptions() {
        return feedOptions;
    }

    public void setFeedOptions(FeedOptions feedOptions) {
        Object oldValue = this.feedOptions;
        this.feedOptions = feedOptions;
        firePropertyChange("feedOptions", oldValue, feedOptions);
    }

    /**
     * Do general sanity for takeBackPart(). Intended to call from feeder implementation.
     */
    @Override
    public void takeBackPart(Nozzle nozzle) throws Exception {
        // first check if we can and want to take back this part (should be always be checked before calling, but to be sure)
        if (nozzle.getPart() == null) {
            throw new UnsupportedOperationException(
                String.format(Translations.getString("ReferenceFeeder.TakePartBack.NoPart.Exception.Message"),   //$NON-NLS-1$
                nozzle.getName())
            );
        }
        if (!nozzle.getPart().equals(getPart())) {
            throw new UnsupportedOperationException(
                String.format(Translations.getString("ReferenceFeeder.TakePartBack.PartMismatch.Exception.Message"),    //$NON-NLS-1$
                getName(), nozzle.getPart().getId(), getPart().getId())
            );
        }
        if (!canTakeBackPart()) {
            throw new UnsupportedOperationException(
                String.format(Translations.getString("ReferenceFeeder.TakePartBack.NoFreeSlot.Exception.Message"),    //$NON-NLS-1$
                getName())
            );
        }
    }

    /**
     * Put part back to pick location and check if part off. Intended to to call from particular takeBackPart() implementation
     * @param nozzle
     * @throws Exception
     */
    protected void putPartBack(Nozzle nozzle) throws Exception {

        // ok, now put the part back on the location of the last pick
        nozzle.moveToPickLocation(this);
        nozzle.place();
        nozzle.moveToSafeZ();
        if (nozzle.isPartOffEnabled(Nozzle.PartOffStep.AfterPlace) && !nozzle.isPartOff()) {
            throw new Exception(
                String.format(Translations.getString("ReferenceFeeder.TakePartBack.Failed.Exception.Message"),    //$NON-NLS-1$
                getName())
            );
        }
    }

    // This vector of bits is a record of whether the outcome of the job processor using this feeder
    // was success or failure, newest first. The job processor records on the machine thread while
    // the feeders table summarises on the EDT, so it is an immutable list replaced atomically.
    private final AtomicReference<List<Boolean>> jobFaults = new AtomicReference<>(List.of());

    public void recordJobSuccess(int windowSize) {
        addJobFault(false,windowSize);
        updateView();
    }

    public void recordJobFault(int faultLimit,int windowSize,Exception e) {
        List<Boolean> faults = addJobFault(true,windowSize);
        //
        long faultCount = faults.stream().filter(f->f).count();
        if(faultCount>0) {
            Logger.info("{} faults {} {}",this,summarise(faults),e);
        }
        if(isEnabled() && faultCount>=faultLimit && faultLimit>0) {
            Logger.info("{} disabled due to fault limit",this);
            setEnabled(false);
        }
        updateView();
    }

    private List<Boolean> addJobFault(boolean fault,int windowSize) {
        int size = Math.max(windowSize, 1);
        return jobFaults.updateAndGet(previous -> {
            List<Boolean> next = new ArrayList<>(size);
            next.add(fault);
            next.addAll(previous.subList(0, Math.min(previous.size(), size - 1)));
            return List.copyOf(next);
        });
    }

    public String summariseJobFaults() {
        return summarise(jobFaults.get());
    }

    private static String summarise(List<Boolean> faults) {
        String r = "";
        boolean anyFaultsFound = false;
        for (boolean f : faults) {
            if (f) {
                anyFaultsFound = true;
                r += "X";
            } else {
                r += "-";
            }
        }
        if (anyFaultsFound) {
            return r;
        } else {
            return "";
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if(enabled) {
            // enabling a feeder clears all faults
            jobFaults.set(List.of());
        }
        updateView();
    }

    private void updateView() {
        SwingUtilities.invokeLater(() -> {
            MainFrame mainFrame = MainFrame.get();
            if (mainFrame!=null) {
                FeedersPanel panel = mainFrame.getFeedersTab();
                if (panel!=null) {
                    panel.refresh(this);
                }
            }
        });
    }
}
