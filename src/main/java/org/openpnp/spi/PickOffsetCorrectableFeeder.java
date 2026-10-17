package org.openpnp.spi;

import org.apache.commons.lang3.NotImplementedException;
import org.openpnp.model.Location;
import org.openpnp.model.PickOffsetCorrection;

/**
 * Implemented by feeders that carry a reusable {@link PickOffsetCorrection}
 * for PickOffsetCorrection's shared UI code.
 */
public interface PickOffsetCorrectableFeeder {
    PickOffsetCorrection getPickOffsetCorrection();

    /**
     * Folds the currently learned correction offset into the feeder's stored pick position and resets
     * the correction, leaving the actual pick point unchanged. Lets an operator make a converged
     * correction permanent instead of keeping it as a runtime overlay.
     */
    default void bakePickOffsetCorrectionIntoPosition() throws Exception {
        throw new NotImplementedException();
    }

    /**
     * @return unit vector in millimetres, machine frame, that a positive feed advances the part, or null if
     *         the feeder can't currently tell (for example no slot assigned). Only used for nudging.
     */
    default Location getFeedDirection() {
        return null;
    }

    /**
     * @param machineFrameDirection unit vector in millimetres, machine frame, to use instead of the
     *        feeder's default, or null to return to the default
     */
    default void setFeedDirection(Location machineFrameDirection) {
        throw new NotImplementedException();
    }

    /**
     * @return true if {@link #getFeedDirection()} is an operator override rather than the feeder's
     *         default
     */
    default boolean isFeedDirectionOverridden() {
        return false;
    }
}
