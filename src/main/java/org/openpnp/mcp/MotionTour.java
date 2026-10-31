package org.openpnp.mcp;

/**
 * Progress of a tour of moves the operator allowed as a whole, which they can pause or stop
 * between moves. A move under way always finishes; the machine's own Stop interrupts one.
 */
public interface MotionTour extends AutoCloseable {
    /** For a confirmer with nobody to show progress to, such as a test. */
    MotionTour UNWATCHED = new MotionTour() {
        @Override
        public boolean beforeStep(int index, int count, String step) {
            return true;
        }

        @Override
        public void close() {
        }
    };

    /**
     * Shows the step about to run and waits while the operator has paused the tour.
     *
     * @param index from 0
     * @return false once the operator has stopped the tour; no further step may run.
     */
    boolean beforeStep(int index, int count, String step) throws InterruptedException;

    @Override
    void close();
}
