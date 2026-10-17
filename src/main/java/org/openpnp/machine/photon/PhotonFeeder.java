package org.openpnp.machine.photon;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.swing.Action;

import org.openpnp.ConfigurationListener;
import org.openpnp.gui.support.PickOffsetCorrectionPropertySheet;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.photon.exceptions.FeedFailureException;
import org.openpnp.machine.photon.exceptions.FeederHasNoLocationOffsetException;
import org.openpnp.machine.photon.exceptions.NoSlotAddressException;
import org.openpnp.machine.photon.exceptions.UnconfiguredSlotException;
import org.openpnp.machine.photon.protocol.ErrorTypes;
import org.openpnp.machine.photon.protocol.PhotonBus;
import org.openpnp.machine.photon.protocol.PhotonBusInterface;
import org.openpnp.machine.photon.protocol.commands.GetFeederAddress;
import org.openpnp.machine.photon.protocol.commands.GetFeederId;
import org.openpnp.machine.photon.protocol.commands.IdentifyFeeder;
import org.openpnp.machine.photon.protocol.commands.InitializeFeeder;
import org.openpnp.machine.photon.protocol.commands.MoveFeedForward;
import org.openpnp.machine.photon.protocol.commands.MoveFeedStatus;
import org.openpnp.machine.photon.protocol.commands.VendorOptions;
import org.openpnp.machine.photon.sheets.FeederPropertySheet;
import org.openpnp.machine.photon.sheets.GlobalConfigPropertySheet;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Length;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.PickOffsetCorrection;
import org.openpnp.model.Solutions;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PickOffsetCorrectableFeeder;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.util.MovableUtils;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

public class PhotonFeeder extends ReferenceFeeder implements PickOffsetCorrectableFeeder {
    // Fixed width so names sort in slot order whatever the configured maximum address; the bus
    // addresses 1-254. The "Slot: " decoration is deliberately not translated: setName strips it.
    private static final String SLOT_ADDRESS_FORMAT = "%03d";
    private static final Pattern NAME_SLOT_PREFIX = Pattern.compile("^Slot: \\w+; ");
    private static final Pattern LEGACY_NAME_SLOT_SUFFIX = Pattern.compile(" \\(Slot: \\w+\\)$");

    public static final String ACTUATOR_DATA_NAME = "PhotonFeederData";
    PhotonProperties photonProperties;

    @Attribute(required = false)
    protected String hardwareId;

    protected Integer slotAddress = null;

    protected String firmwareVersion = null;
    
    @Attribute(required = false)
    protected int partPitch = 4;

    protected boolean initialized = false;

    @Element(required = false)
    private Location offset;

    private static final int FEED_TICKS_PER_MM = 10;
    private static final Length FEED_TICK = new Length(1.0 / FEED_TICKS_PER_MM, LengthUnit.Millimeters);
    // MoveFeedForward sends the distance as a single unsigned byte.
    private static final int MIN_FEED_TICKS = 1;
    private static final int MAX_FEED_TICKS = 255;
    private static final double MAX_NUDGE_FRACTION_OF_PITCH = 0.25;
    // LumenPnP: the rear rail starts after this slot and feeds in the opposite machine direction.
    private static final int LAST_FRONT_RAIL_SLOT_ADDRESS = 25;

    @Element(required = false)
    private PickOffsetCorrection.Nudging pickOffsetCorrection = new PickOffsetCorrection.Nudging();

    // Operator override of the feed direction, in the slot frame so it follows the feeder to any
    // slot. Null means use the rail default.
    @Element(required = false)
    private Location feedDirectionInSlotFrame = null;

    private static PhotonBusInterface photonBus;

    // Configuration reloads create a new Machine, so track which one has the listener.
    private static Machine listenerMachine = null;

    @Attribute(required = false)
    protected boolean moveWhileFeeding = true;

    public PhotonFeeder() {
        Configuration.get().addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationLoaded(Configuration configuration) {
                photonProperties = new PhotonProperties(configuration.getMachine());

                // Ensure actuators are added to the machine when it has PhotonFeeders
                getDataActuator();
                populatePhotonBus();
                registerMachineListener(configuration);
            }
        });
    }

    private static synchronized void registerMachineListener(Configuration configuration) {
        Machine machine = configuration.getMachine();
        if (machine == listenerMachine) {
            return;
        }
        listenerMachine = machine;

        machine.addListener(new MachineListener.Adapter() {
            @Override
            public void machineEnabled(Machine machine) {
                PhotonProperties props = new PhotonProperties(machine);
                if (props.getAutoScanMode() == PhotonProperties.AutoScanMode.ScanOnEnable) {
                    runAutoScan(props);
                }
            }

            @Override
            public void machineHomed(Machine machine, boolean isHomed) {
                if (!isHomed) {
                    for (Feeder feeder : machine.getFeeders()) {
                        if (feeder instanceof PhotonFeeder) {
                            ((PhotonFeeder) feeder).pickOffsetCorrection.reset();
                        }
                    }
                    return;
                }
                PhotonProperties props = new PhotonProperties(machine);
                if (props.getAutoScanMode() == PhotonProperties.AutoScanMode.ScanOnHomed) {
                    runAutoScan(props);
                }
            }
        });
    }

    private static final int STATUS_UPDATE_INTERVAL = 10;

    private static void runAutoScan(PhotonProperties props) {
        try {
            int maxAddress = props.getMaxFeederAddress();
            org.openpnp.gui.MainFrame.get().setStatus("Scanning Photon feeders...");
            findAllFeeders((address, state) -> {
                if (address % STATUS_UPDATE_INTERVAL == 0 || address == maxAddress) {
                    org.openpnp.gui.MainFrame.get().setStatus(
                            String.format("Scanning Photon feeders... %d/%d", address, maxAddress));
                }
            });
            org.openpnp.gui.MainFrame.get().setStatus("Photon feeder scan complete.");
            javax.swing.SwingUtilities.invokeLater(() -> {
                org.openpnp.gui.MainFrame.get().getFeedersTab().updateView();
                org.openpnp.gui.MainFrame.get().getJobTab().getJobPlacementsPanel().refresh();
            });
        }
        catch (Exception e) {
            Logger.error("Auto-scan of Photon feeders failed: " + e.getMessage());
            org.openpnp.gui.MainFrame.get().setStatus("Photon feeder scan failed.");
        }
    }

    public static void setBus(PhotonBusInterface bus) {
        photonBus = bus;
    }

    public static PhotonBusInterface getBus() {
        populatePhotonBus();
        return photonBus;
    }

    private static void populatePhotonBus() {
        if (photonBus != null) {
            return;
        }

        photonBus = new PhotonBus(0, getDataActuator());
    }

    private Location getPickLocationWithoutCorrection() throws Exception {
        verifyFeederLocationIsFullyConfigured();

        return offset.offsetWithRotationFrom(getSlot().getLocation());
    }

    @Override
    public Location getPickLocation() throws Exception {
        return getPickLocationWithoutCorrection().add(pickOffsetCorrection.getOffset());
    }

    private void verifyFeederLocationIsFullyConfigured() throws NoSlotAddressException,
            UnconfiguredSlotException, FeederHasNoLocationOffsetException {
        if (slotAddress == null) {
            throw new NoSlotAddressException(
                    String.format("Photon Feeder with address %s has no address. Is it inserted?", hardwareId)
            );
        }

        if (getSlot().getLocation() == null) {
            throw new UnconfiguredSlotException(
                    String.format("The slot at address %s has no location configured.", slotAddress)
            );
        }

        if (offset == null) {
            throw new FeederHasNoLocationOffsetException(
                    String.format("Photon Feeder with address %s has no location offset.", hardwareId)
            );
        }
    }

    public void setOffset(Location offsets) {
        Object oldValue = this.offset;
        this.offset = offsets;
        pickOffsetCorrection.reset();
        firePropertyChange("offset", oldValue, offsets);
    }

    public Location getOffset() {
        return offset;
    }

    @Override
    public PickOffsetCorrection getPickOffsetCorrection() {
        return pickOffsetCorrection;
    }

    @Override
    public void bakePickOffsetCorrectionIntoPosition() throws Exception {
        Location correctionOffset = pickOffsetCorrection.getOffset();
        Location slotLocation;

        verifyFeederLocationIsFullyConfigured();
        slotLocation = getSlot().getLocation();

        // pickOffsetCorrection tracks machine space coordinates for the nozzle.
        // Convert that to feeder coordinates.
        Location foldedIn = correctionOffset.convertToUnits(offset.getUnits())
                .rotateXy(-slotLocation.getRotation());

        setOffset(offset.add(foldedIn));
        pickOffsetCorrection.reset();
    }

    @Override
    public Location getFeedDirection() {
        Integer address = slotAddress;
        if (address == null) {
            return null;
        }
        PhotonFeederSlots.Slot slot = photonProperties.getFeederSlots().getSlot(address);
        if (slot == null || slot.getLocation() == null) {
            return null;
        }
        if (feedDirectionInSlotFrame == null) {
            return new Location(LengthUnit.Millimeters, 0,
                    address > LAST_FRONT_RAIL_SLOT_ADDRESS ? -1 : 1, 0, 0);
        }
        return feedDirectionInSlotFrame.rotateXy(slot.getLocation().getRotation());
    }

    @Override
    public void setFeedDirection(Location machineFrameDirection) {
        Location oldValue = getFeedDirection();
        boolean wasOverridden = isFeedDirectionOverridden();
        if (machineFrameDirection == null) {
            feedDirectionInSlotFrame = null;
        }
        else {
            PhotonFeederSlots.Slot slot = getSlot();
            if (slot == null || slot.getLocation() == null) {
                throw new IllegalStateException("The feed direction needs a configured slot.");
            }
            feedDirectionInSlotFrame = machineFrameDirection.rotateXy(-slot.getLocation().getRotation());
        }
        firePropertyChange("feedDirection", oldValue, getFeedDirection());
        // Fired separately because an override can equal the rail default, which suppresses the
        // feedDirection event.
        firePropertyChange("feedDirectionOverridden", wasOverridden, isFeedDirectionOverridden());
    }

    @Override
    public boolean isFeedDirectionOverridden() {
        return feedDirectionInSlotFrame != null;
    }

    public boolean getMoveWhileFeeding() {
        return moveWhileFeeding;
    }

    public void setMoveWhileFeeding(boolean moveWhileFeeding) {
        this.moveWhileFeeding = moveWhileFeeding;
    }

    @Override
    public void findIssues(Solutions solutions) {
        super.findIssues(solutions);

        if (hardwareId == null) {
            return;
        }

        if (slotAddress != null && getSlot().getLocation() == null) {
            solutions.add(new Solutions.PlainIssue(
                    this,
                    "Feeder slot has no configured location",
                    "Select the feeder in the Feeders tab and make sure the slot has a set location",
                    Solutions.Severity.Error,
                    "https://github.com/openpnp/openpnp/wiki/Photon-Feeder#slots-and-feeder-locations"
            ));
        }

        if (offset == null) {
            solutions.add(new Solutions.PlainIssue(
                    this,
                    "Feeder has no configured offset",
                    "Select the feeder in the Feeders tab and make sure the feeder has an offset location from the slot",
                    Solutions.Severity.Error,
                    "https://github.com/openpnp/openpnp/wiki/Photon-Feeder#slots-and-feeder-locations"
            ));
        }
    }

    @Override
    public void prepareForJob(boolean visit) throws Exception {
        for (int i = 0; i <= photonProperties.getFeederCommunicationMaxRetry(); i++) {
            findSlotAddressIfNeeded();

            initializeIfNeeded();

            if (initialized) {
                verifyFeederLocationIsFullyConfigured();

                super.prepareForJob(visit);
                return;
            }
        }

        throw new Exception("Failed to find and initialize the feeder");
    }

    public void findSlotAddress() throws Exception {
        findSlotAddress(true);
    }

    private void findSlotAddress(boolean force) throws Exception {
        if (slotAddress != null && !force) {
            return;
        }

        GetFeederAddress getFeederAddress = new GetFeederAddress(hardwareId);
        GetFeederAddress.Response response = getFeederAddress.send(photonBus);

        if (response == null) {
            setSlotAddress(null);
            return;
        }
        setSlotAddress(response.fromAddress);
    }

    private void findSlotAddressIfNeeded() throws Exception {
        findSlotAddress(false);
    }

    public void identifyFeeder() throws Exception {
        for (int i = 0; i <= photonProperties.getFeederCommunicationMaxRetry(); i++) {
            findSlotAddressIfNeeded();
            initializeIfNeeded();

            if (!initialized) {
                continue;
            }

            if (slotAddress == null) {
                throw new NoSlotAddressException(
                        String.format("Photon Feeder with address %s has no slot address. Is it inserted?", hardwareId));
            }

            IdentifyFeeder identifyFeeder = new IdentifyFeeder(hardwareId);
            IdentifyFeeder.Response response = identifyFeeder.send(photonBus);

            if (response == null) {
                continue; // Timeout. retry after delay.
            }

            if (response.error == ErrorTypes.NONE) {
                return;
            } else {
                Logger.error("{}: Could not identify feeder: {}", getSlotAddress(), response.error.name());
                return;
            }
        }
    }

    public void queryFirmwareVersion() throws Exception {
        findSlotAddressIfNeeded();
        initializeIfNeeded();

        if (!initialized) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        boolean complete = false;

        // Loop through each byte of the version string and query it
        for (int i = 0; i <= 0x0F; i++) {
            for (int j = 0; j <= photonProperties.getFeederCommunicationMaxRetry(); j++) {
                VendorOptions vendorOptions = new VendorOptions(getSlotAddress(), new int[] { i });
                VendorOptions.Response response = vendorOptions.send(photonBus);

                if (response == null) {
                    continue; // Timeout. retry after delay.
                }

                if (response.error == ErrorTypes.NONE) {
                    if (response.data != null && response.data.length > 0) {
                        for (int k = 0; k < response.data.length; k++) {
                            if (response.data[k] == 0) {
                                complete = true;
                                break;
                            }
                            builder.append((char)(response.data[k] & 0xFF));
                        }
                    } else {
                        complete = true;
                    }
                    break;
                } else {
                    Logger.error("{}: Could not send version command to feeder: {}", getSlotAddress(), response.error.name());
                    return;
                }
            }
            if (complete) {
                break;
            }
        }
        setFirmwareVersion(builder.toString());
    }
    
    private void setFirmwareVersion(String firmwareVersion) {
        String oldValue = this.firmwareVersion;
        this.firmwareVersion = firmwareVersion;
        firePropertyChange("firmwareVersion", oldValue, firmwareVersion);
    }
    
    public String getFirmwareVersion() {
        return this.firmwareVersion;
    }
    
    public void initializeIfNeeded() throws Exception {
        if (initialized || slotAddress == null) {
            return;
        }

        InitializeFeeder initializeFeeder = new InitializeFeeder(slotAddress, hardwareId);
        InitializeFeeder.Response response = initializeFeeder.send(photonBus);

        if (response == null) {
            slotAddress = null;
        } else if (response.error == ErrorTypes.WRONG_FEEDER_UUID) {
            PhotonFeeder otherFeeder = findByHardwareId(response.uuid);

            // If we don't know about that feeder, let's go ahead and create it
            if (otherFeeder == null) {
                otherFeeder = new PhotonFeeder();
                otherFeeder.setHardwareId(response.uuid);
                Configuration.get().getMachine().addFeeder(otherFeeder);
            }

            // This other feeder is in the slot we thought we were
            otherFeeder.setSlotAddress(response.fromAddress);
            otherFeeder.applyDiscoveryDefaults();
        } else {
            initialized = true;
        }
    }

    static Actuator getDataActuator() {
        Machine machine = Configuration.get().getMachine();

        Actuator actuator = machine.getActuatorByName(ACTUATOR_DATA_NAME);

        if (actuator == null) {
            actuator = createDefaultActuator(machine);
        }

        return actuator;
    }

    private static Actuator createDefaultActuator(Machine machine) {
        Actuator actuator;
        actuator = new ReferenceActuator();
        actuator.setName(ACTUATOR_DATA_NAME);

        for (Driver driver : machine.getDrivers()) {
            if(! (driver instanceof GcodeDriver)) {
                continue;
            }
            GcodeDriver gcodeDriver = (GcodeDriver) driver;
            gcodeDriver.setCommand(actuator, GcodeDriver.CommandType.ACTUATOR_READ_COMMAND, "M485 {Value}");
            gcodeDriver.setCommand(actuator, GcodeDriver.CommandType.ACTUATOR_READ_REGEX, "rs485-reply: (?<Value>.*)");
            break;  // Only set this on 1 GCodeDriver
        }

        try {
            machine.addActuator(actuator);
        } catch (Exception exception) {
            exception.printStackTrace(); // TODO Probably need to log this, figure out why it can happen first
        }
        return actuator;
    }

    private void feed(Nozzle nozzle, int distanceTicks) throws Exception {
        for (int i = 0; i <= photonProperties.getFeederCommunicationMaxRetry(); i++) {
            findSlotAddressIfNeeded();
            initializeIfNeeded();

            if (!initialized) {
                continue;
            }

            verifyFeederLocationIsFullyConfigured();

            MoveFeedForward moveFeedForward = new MoveFeedForward(slotAddress, distanceTicks);
            MoveFeedForward.Response moveFeedForwardResponse = moveFeedForward.send(photonBus);

            if (moveFeedForwardResponse == null) {
                slotAddress = null;
                initialized = false;
                throw new FeedFailureException("Feed command timed out");
            } else if (moveFeedForwardResponse.error == ErrorTypes.UNINITIALIZED_FEEDER) {
                slotAddress = null;
                initialized = false;
                continue;  // We'll initialize it on a retry
            }

            // The feeder gives us expectedTimeToFeed, but it is way too conservative.
            // Use expectedTimeToFeed to bound how long we will wait,
            // but use polling to check the status of the feed.
            Duration expectedFeedDuration = Duration.ofMillis(moveFeedForwardResponse.expectedTimeToFeed);
            long endTimeNanos = System.nanoTime() + expectedFeedDuration.toNanos() * 3;
            for (int j = 0; j <= photonProperties.getFeederCommunicationMaxRetry() || System.nanoTime() <= endTimeNanos; j++) {
                Thread.sleep(50); // MAGIC: this feels like a good number, there is no particular reason it is this way.

                if (j == 0 && nozzle != null && Configuration.get().getMachine().isHomed() && getMoveWhileFeeding()) {
                    MovableUtils.moveToLocationAtSafeZ(nozzle, getPickLocation().deriveLengths(null, null, nozzle.getEffectiveSafeZ(), null));
                }

                MoveFeedStatus moveFeedStatus = new MoveFeedStatus(slotAddress);
                MoveFeedStatus.Response moveFeedStatusResponse = moveFeedStatus.send(photonBus);

                if (moveFeedStatusResponse == null) {
                    continue; // Timeout. retry after delay.
                }

                if (moveFeedStatusResponse.error == ErrorTypes.NONE) {
                    return;
                } else if (moveFeedStatusResponse.error == ErrorTypes.COULD_NOT_REACH) {
                    throw new FeedFailureException("Feeder could not reach its destination.");
                }
            }

            throw new FeedFailureException("Feeder timed out when we requested a feed status update.");
        }

        throw new FeedFailureException("Failed to feed for an unknown reason. Is the feeder inserted?");
    }

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        // Settle even when this feed is skipped: a part put back in its pocket is re-picked
        // straight away and should use what its first pick taught.
        pickOffsetCorrection.settle();

        switch (getFeedOptions()) {
        case Normal:
            break;
        case SkipNext:
            setFeedOptions(FeedOptions.Normal);
            return;
        case Disable:
            return;
        }

        int pitchTicks = getPartPitch() * FEED_TICKS_PER_MM;
        if (pitchTicks < MIN_FEED_TICKS || pitchTicks > MAX_FEED_TICKS) {
            throw new FeedFailureException(String.format(
                    "Part pitch %d mm is outside the %d to %d mm a Photon feeder can feed.",
                    getPartPitch(), (MIN_FEED_TICKS + FEED_TICKS_PER_MM - 1) / FEED_TICKS_PER_MM,
                    MAX_FEED_TICKS / FEED_TICKS_PER_MM));
        }

        Location feedDirection = getFeedDirection();
        if (feedDirection == null && pickOffsetCorrection.isNudgingEnabled()) {
            Logger.debug("{}: Feed direction unknown until the slot is found, not nudging this feed",
                    getSlotAddress());
        }

        int maxNudgeTicks = (int) (pitchTicks * MAX_NUDGE_FRACTION_OF_PITCH);
        int partPitchNudgeTicks = pickOffsetCorrection.nudge(FEED_TICK, feedDirection,
                Math.max(-maxNudgeTicks, MIN_FEED_TICKS - pitchTicks),
                Math.min(maxNudgeTicks, MAX_FEED_TICKS - pitchTicks));
        if (partPitchNudgeTicks != 0) {
            Logger.debug("{}: Nudging tape by {} ticks", getSlotAddress(), partPitchNudgeTicks);
        }

        try {
            feed(nozzle, pitchTicks + partPitchNudgeTicks);
        } catch (Exception e) {
            pickOffsetCorrection.revertNudge();
            throw e;
        }
    }

    public void feedOneMm() throws Exception {
        feed(null, FEED_TICKS_PER_MM);
        pickOffsetCorrection.reset();
    }

    @Override
    public String getPropertySheetHolderTitle() {
        String classSimpleName = getClass().getSimpleName();
        if (hardwareId == null) {
            return String.format("Unconfigured %s", classSimpleName);
        } else {
            return String.format("%s %s", classSimpleName, getName());
        }
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        List<PropertySheet> sheets = new ArrayList<>();

        if (hardwareId != null) {
            sheets.add(new FeederPropertySheet(this));
            sheets.add(new PickOffsetCorrectionPropertySheet(this));
        }

        sheets.add(new GlobalConfigPropertySheet());

        return sheets.toArray(new PropertySheet[0]);
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return new PropertySheetHolder[0];
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return new Action[0];
    }

    @Override
    public Wizard getConfigurationWizard() {
        return null;
    }

    @Override
    public String getName() {
        if (hardwareId == null) {
            return String.format("Unconfigured %s", getClass().getSimpleName());
        }

        StringBuilder result = new StringBuilder();
        result.append("Slot: ");

        if (slotAddress == null) {
            result.append("None");
        } else {
            result.append(formatSlot(slotAddress));
        }

        result.append("; ");

        result.append(name);

        return result.toString();
    }

    private static String formatSlot(int slot) {
        return String.format(SLOT_ADDRESS_FORMAT, slot);
    }

    @Override
    public void setName(String name) {
        // Strip both the current prefix and the pre-slot-ordering suffix, so names from older
        // configurations, or with both, settle on the bare name.
        String bareName = NAME_SLOT_PREFIX.matcher(name).replaceFirst("");
        bareName = LEGACY_NAME_SLOT_SUFFIX.matcher(bareName).replaceFirst("");
        super.setName(bareName.trim());
    }

    /**
     * The PhotonFeeder assumes you have a physical slot that is numbered 1 - 254. That
     * value is also used in the protocol as the address of the feeder once the feeder
     * is initialized.
     *
     * @return The slot address of this feeder or null if it doesn't have one.
     */
    public Integer getSlotAddress() {
        return slotAddress;
    }

    public PhotonFeederSlots.Slot getSlot() {
        // The machine thread can clear slotAddress while the EDT reads it.
        Integer address = slotAddress;
        if (address == null) {
            return null;
        }

        return photonProperties.getFeederSlots().getSlot(address);
    }

    public void setSlotAddress(Integer slotAddress) {
        PhotonFeederSlots.Slot oldSlot = getSlot();
        Integer oldValue = this.slotAddress;
        String oldName = this.getName();

        if (slotAddress != null) {
            // Find any other photon feeders and if they have this slot address, set their address to null
            PhotonFeeder otherFeeder = findBySlotAddress(slotAddress);
            if (otherFeeder != null) {
                otherFeeder.slotAddress = null;
                otherFeeder.initialized = false;
            }
        }

        this.slotAddress = slotAddress;

        pickOffsetCorrection.reset();

        firePropertyChange("slotAddress", oldValue, slotAddress);
        firePropertyChange("slot", oldSlot, getSlot());
        firePropertyChange("name", oldName, getName());
    }

    /**
     * Fills in unset values on a newly discovered feeder. partId is a required XML attribute, so
     * machine.xml can't be saved while it's null.
     */
    void applyDiscoveryDefaults() {
        if (partId == null) {
            setPart(getDefaultPart());
        }

        if (slotAddress != null) {
            PhotonFeederSlots.Slot slot = getSlot();
            if (slot != null && slot.getLocation() == null) {
                slot.setLocation(new Location(LengthUnit.Millimeters));
            }
        }
    }

    /**
     * Prefers a fiducial so the placeholder isn't mistaken for real stock.
     */
    private static Part getDefaultPart() {
        Configuration configuration = Configuration.get();

        Part fiducialHome = configuration.getPart("FIDUCIAL-HOME");
        if (fiducialHome != null) {
            return fiducialHome;
        }

        for (Part part : configuration.getParts()) {
            Package pkg = part.getPackage();
            String pkgId = pkg == null ? null : pkg.getId();
            if ((pkgId != null && pkgId.toUpperCase().contains("FIDUCIAL"))
                    || part.getId().toUpperCase().contains("FIDUCIAL")) {
                return part;
            }
        }

        List<Part> parts = configuration.getParts();
        return parts.isEmpty() ? null : parts.get(0);
    }

    public String getHardwareId() {
        return hardwareId;
    }

    public void setHardwareId(String hardwareId) {
        String oldValue = this.hardwareId;
        this.hardwareId = hardwareId;

        if (getClass().getSimpleName().equals(name)) {
            name = hardwareId;
        }

        pickOffsetCorrection.reset();

        firePropertyChange("hardwareId", oldValue, hardwareId);
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() &&
                hardwareId != null &&
                partId != null &&
                slotAddress != null &&
                offset != null &&
                getSlot().getLocation() != null;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setPartPitch(int partPitch) {
        this.partPitch = partPitch;
        pickOffsetCorrection.reset();
    }

    public int getPartPitch() {
        return partPitch;
    }

    public static PhotonFeeder findByHardwareId(String hardwareId) {
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (!(feeder instanceof PhotonFeeder)) {
                continue;
            }

            PhotonFeeder photonFeeder = (PhotonFeeder) feeder;

            // Are we explicitly asking for feeders with null hardware ID?
            if (photonFeeder.hardwareId == null && hardwareId == null) {
                return photonFeeder;
            }

            if (photonFeeder.hardwareId != null && photonFeeder.hardwareId.equals(hardwareId)) {
                return photonFeeder;
            }
        }

        return null;
    }

    public static PhotonFeeder findBySlotAddress(int slotAddress) {
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (!(feeder instanceof PhotonFeeder)) {
                continue;
            }

            PhotonFeeder photonFeeder = (PhotonFeeder) feeder;

            if (photonFeeder.slotAddress != null && photonFeeder.slotAddress.equals(slotAddress)) {
                return photonFeeder;
            }
        }

        return null;
    }

    public enum FeederSearchState {
        UNKNOWN,
        SEARCHING,
        FOUND,
        MISSING
    }

    public interface FeederSearchProgressConsumer {
        void accept(int feederAddress, FeederSearchState feederSearchState);
    }

    public static void findAllFeeders(FeederSearchProgressConsumer progressUpdate) throws Exception {
        Logger.info("Searching for Photon Feeders");
        Machine machine = Configuration.get().getMachine();
        PhotonProperties photonProperties = new PhotonProperties(machine);
        int maxFeederAddress = photonProperties.getMaxFeederAddress();
        Logger.debug("Max Photon feeder address: " + maxFeederAddress);

        List<PhotonFeeder> feedersToAdd = new ArrayList<>();

        for (int address = 1; address <= maxFeederAddress; address++) {
            Logger.debug("Querying Photon feeder address: " + address);

            if (progressUpdate != null) {
                progressUpdate.accept(address, FeederSearchState.SEARCHING);
            }

            GetFeederId getFeederId = new GetFeederId(address);
            GetFeederId.Response response = getFeederId.send(photonBus);

            if (progressUpdate != null) {
                progressUpdate.accept(address, response == null ? FeederSearchState.MISSING : FeederSearchState.FOUND);
            }

            if (response == null) {
                PhotonFeeder otherFeeder = findBySlotAddress(address);
                if (otherFeeder != null) {
                    otherFeeder.slotAddress = null;
                    otherFeeder.initialized = false;
                }
            } else {
                PhotonFeeder otherFeeder = findByHardwareId(response.uuid);
                if (otherFeeder == null) {
                    // Try to find an existing feeder without a hardware id before making a new one
                    otherFeeder = findByHardwareId(null);
                    if (otherFeeder == null) {
                        otherFeeder = new PhotonFeeder();
                        feedersToAdd.add(otherFeeder);
                    }
                }

                otherFeeder.setHardwareId(response.uuid);
                otherFeeder.setSlotAddress(address);
                otherFeeder.applyDiscoveryDefaults();

                Logger.trace("Found feeder with hardware uuid " + otherFeeder.getHardwareId() + " at address " + otherFeeder.getSlotAddress());
                
                otherFeeder.queryFirmwareVersion();
            }
        }

        for (PhotonFeeder feeder : feedersToAdd) {
            Configuration.get().getMachine().addFeeder(feeder);
        }
    }

    @Override
    public boolean canTakeBackPart() {
        return getFeedOptions() == FeedOptions.Normal;
    }

    @Override
    public void takeBackPart(Nozzle nozzle) throws Exception {
        super.takeBackPart(nozzle);
        putPartBack(nozzle);
        setFeedOptions(FeedOptions.SkipNext);
    }

    @Override
    public boolean supportsFeedOptions() {
        return true;
    }

    @Override
    public void deferredBottomVisionResult(Location pickError) {
        Location pickLocation;
        try {
            pickLocation = getPickLocationWithoutCorrection();
        } catch (Exception e) {
            Logger.error("{}: Could not get pick location without error for bottom vision callback: {}", getSlotAddress(), e);
            return;
        }

        // Rotate so the integrated offset is stored independently of how the slot happens to be oriented.
        pickOffsetCorrection.recordVision(pickError.rotateXy(pickLocation.getRotation()));
    }
}
