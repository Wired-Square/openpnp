package org.openpnp.spi;

/**
 * Implemented by objects that report a hardware identifier, such as a feeder's serial number, so
 * the GUI can offer it without knowing the concrete type.
 */
public interface HardwareIdentifiable {
    /**
     * @return the hardware identifier, or null if none is known yet
     */
    String getHardwareId();
}
