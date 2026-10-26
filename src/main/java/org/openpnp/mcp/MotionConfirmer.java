package org.openpnp.mcp;

/**
 * Asks the operator to allow one machine move. The assistant can't see the machine, so every
 * move needs someone who can to confirm the workspace is clear.
 */
@FunctionalInterface
public interface MotionConfirmer {
    /**
     * @param description what will move, where to and how, in the operator's terms.
     * @return true only if the operator allowed it. Waits until the operator chooses.
     */
    boolean confirm(String description) throws Exception;
}
