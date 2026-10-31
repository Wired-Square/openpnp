package org.openpnp.spi;

import org.openpnp.model.Location;

/**
 * A feeder that counts its parts. Its pick location is the part the last feed presented, which a
 * pick normally took; this is the part the next feed will present.
 */
public interface NextPickLocatable {
    /**
     * @return where the next feed will present a part, without feeding, or null when no part is
     *         left.
     */
    Location getNextPickLocation() throws Exception;
}
