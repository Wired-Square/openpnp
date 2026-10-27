import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.spi.JobProcessor.JobProcessorException;
import org.openpnp.spi.Machine;
import org.openpnp.spi.PnpJobProcessor;

/**
 * After a placement error in Alert mode the job can retry the placement, by stepping again, or
 * skip it and continue.
 */
public class ReferenceJobProcessorSkipTests {
    private static final String FAILING_PLACEMENT = "R1"; //$NON-NLS-1$
    private static final String GOOD_PLACEMENT = "R2"; //$NON-NLS-1$
    /** The rank window lets ranks below the current rank + 10 through. */
    private static final int HELD_BACK_RANK = 10;

    private Job job;
    private ReferenceJobProcessorRetryTests.TestFeeder emptyFeeder;
    private PnpJobProcessor jobProcessor;

    @Test
    public void skippingAFailedPlacementContinuesWithTheRest() throws Exception {
        start(false);
        runUntilError();
        assertEquals(FAILING_PLACEMENT, jobProcessor.getFailedPlacement().getId());

        jobProcessor.skipFailedPlacement();
        assertNull(jobProcessor.getFailedPlacement());
        runToEnd();

        assertPlaced(false, true);
    }

    @Test
    public void skippingMidCycleLeavesThePlacementOnTheOtherNozzle() throws Exception {
        start(true);
        runUntilError();

        jobProcessor.skipFailedPlacement();
        runToEnd();

        assertPlaced(false, true);
    }

    @Test
    public void aSkippedPlacementStillHoldsBackHigherRanks() throws Exception {
        start(false);
        job.getBoardLocations().get(0).getBoard().getPlacements().get(GOOD_PLACEMENT)
                .setRank(HELD_BACK_RANK);
        runUntilError();

        jobProcessor.skipFailedPlacement();
        runToEnd();

        assertPlaced(false, false);
    }

    @Test
    public void steppingAgainRetriesTheFailedPlacement() throws Exception {
        start(false);
        runUntilError();
        emptyFeeder.setEnabled(true);
        emptyFeeder.setPartCount(emptyFeeder.feedCount + 1);

        runToEnd();

        assertPlaced(true, true);
    }

    @Test
    public void abortingClearsTheFailedPlacement() throws Exception {
        start(false);
        runUntilError();

        jobProcessor.abort();

        assertNull(jobProcessor.getFailedPlacement());
    }

    @Test
    public void skippingWithNoFailedPlacementIsRefused() throws Exception {
        start(false);

        assertThrows(IllegalStateException.class, () -> jobProcessor.skipFailedPlacement());
    }

    private void start(boolean twoNozzles) throws Exception {
        Configuration.initialize();
        ReferenceJobProcessorRetryTests.MachineBuilder machineBuilder =
                new ReferenceJobProcessorRetryTests.MachineBuilder()
                        .head("H1") //$NON-NLS-1$
                        .nozzleTip("NT1") //$NON-NLS-1$
                        .nozzle("N1", "NT1"); //$NON-NLS-1$ //$NON-NLS-2$
        if (twoNozzles) {
            machineBuilder.nozzle("N2", "NT1"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Machine machine = machineBuilder
                .topCamera("TOP") //$NON-NLS-1$
                .bottomCamera("BOTTOM") //$NON-NLS-1$
                .build();
        job = new ReferenceJobProcessorRetryTests.JobBuilder()
                .board("B1", 10, 10, 10, -10) //$NON-NLS-1$
                .packag("R0402", "NT1") //$NON-NLS-1$ //$NON-NLS-2$
                .part("R0402-1k", "R0402") //$NON-NLS-1$ //$NON-NLS-2$
                .part("R0402-2k", "R0402") //$NON-NLS-1$ //$NON-NLS-2$
                .feeder("F1", "R0402-1k", 100, 20, -5, 0) //$NON-NLS-1$ //$NON-NLS-2$
                .feeder("F2", "R0402-2k", 110, 20, -5, 0) //$NON-NLS-1$ //$NON-NLS-2$
                .placement(FAILING_PLACEMENT, "R0402-1k", 10, 10, 0) //$NON-NLS-1$
                .placement(GOOD_PLACEMENT, "R0402-2k", 20, 20, 0) //$NON-NLS-1$
                .build();
        emptyFeeder = (ReferenceJobProcessorRetryTests.TestFeeder) machine.getFeederByName("F1"); //$NON-NLS-1$
        emptyFeeder.setPartCount(0);
        emptyFeeder.setFeedRetryCount(0);
        ((ReferenceJobProcessorRetryTests.TestFeeder) machine.getFeederByName("F2")).setPartCount(1); //$NON-NLS-1$

        machine.setEnabled(true);
        machine.home();
        jobProcessor = machine.getPnpJobProcessor();
        jobProcessor.initialize(job);
    }

    private void runUntilError() {
        assertThrows(JobProcessorException.class, this::runToEnd);
    }

    private void runToEnd() throws JobProcessorException {
        while (jobProcessor.next()) {
            // spin
        }
    }

    private void assertPlaced(boolean failingPlaced, boolean goodPlaced) {
        BoardLocation boardLocation = job.getBoardLocations().get(0);
        assertEquals(failingPlaced, job.retrievePlacedStatus(boardLocation, FAILING_PLACEMENT));
        assertEquals(goodPlaced, job.retrievePlacedStatus(boardLocation, GOOD_PLACEMENT));
    }
}
