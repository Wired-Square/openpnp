package org.openpnp.machine.photon;

import org.openpnp.spi.Machine;

public class PhotonProperties {
    static final String FEEDER_COMMUNICATION_MAX_RETRY = "PhotonFeeder.FeederCommunicationMaxRetry";
    static final String FEEDER_SLOTS_PROPERTY = "PhotonFeeder.FeederSlots";
    static final String MAX_FEEDER_ADDRESS = "PhotonFeeder.MaxFeederAddress";
    static final String AUTO_SCAN_MODE = "PhotonFeeder.AutoScanMode";

    public enum AutoScanMode {
        Manual,
        ScanOnEnable,
        ScanOnHomed
    }

    final Machine machine;

    // Guards each default-on-first-use against a value set meanwhile on another thread. Static
    // because callers create a PhotonProperties per use.
    private static final Object LOCK = new Object();

    public PhotonProperties(Machine machine) {
        this.machine = machine;
    }

    public int getFeederCommunicationMaxRetry() {
        synchronized (LOCK) {
            Integer maxRetry = (Integer) machine.getProperty(FEEDER_COMMUNICATION_MAX_RETRY);

            if(maxRetry == null) {
                maxRetry = 3;
                setFeederCommunicationMaxRetry(maxRetry);
            }

            return maxRetry;
        }
    }

    public void setFeederCommunicationMaxRetry(int maxRetry) {
        synchronized (LOCK) {
            machine.setProperty(FEEDER_COMMUNICATION_MAX_RETRY, maxRetry);
        }
    }

    public int getMaxFeederAddress() {
        synchronized (LOCK) {
            Integer maxFeederAddress = (Integer) machine.getProperty(MAX_FEEDER_ADDRESS);

            if(maxFeederAddress == null) {
                maxFeederAddress = 50;
                setMaxFeederAddress(maxFeederAddress);
            }

            return maxFeederAddress;
        }
    }

    public void setMaxFeederAddress(int maxFeederAddress) {
        synchronized (LOCK) {
            machine.setProperty(MAX_FEEDER_ADDRESS, maxFeederAddress);
        }
    }

    public AutoScanMode getAutoScanMode() {
        String mode = (String) machine.getProperty(AUTO_SCAN_MODE);

        if (mode == null) {
            return AutoScanMode.Manual;
        }

        try {
            return AutoScanMode.valueOf(mode);
        }
        catch (IllegalArgumentException e) {
            return AutoScanMode.Manual;
        }
    }

    public void setAutoScanMode(AutoScanMode mode) {
        machine.setProperty(AUTO_SCAN_MODE, mode.name());
    }


    public PhotonFeederSlots getFeederSlots() {
        synchronized (LOCK) {
            PhotonFeederSlots feederSlots = (PhotonFeederSlots) machine.getProperty(FEEDER_SLOTS_PROPERTY);

            if(feederSlots == null) {
                feederSlots = new PhotonFeederSlots();
                machine.setProperty(FEEDER_SLOTS_PROPERTY, feederSlots);
            }

            return feederSlots;
        }
    }
}
