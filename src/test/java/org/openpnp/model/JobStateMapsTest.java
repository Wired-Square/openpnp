package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.io.Files;

public class JobStateMapsTest {
    private static final String PLACEMENT_ID = "R1"; //$NON-NLS-1$
    private static final int TOGGLES = 200_000;
    private static final long THREAD_TIMEOUT_SECONDS = 30;
    private static final String LEGACY_MAPS = "<openpnp-job version=\"2.0\">" //$NON-NLS-1$
            + "<placed-status-map class=\"java.util.HashMap\">" //$NON-NLS-1$
            + "<entry><string>a</string><boolean>true</boolean></entry></placed-status-map>" //$NON-NLS-1$
            + "<enabled-state-map class=\"java.util.HashMap\"/>" //$NON-NLS-1$
            + "</openpnp-job>"; //$NON-NLS-1$

    private Job job;
    private BoardLocation boardLocation;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp")); //$NON-NLS-1$
        Configuration.get().load();
        job = new Job();
        boardLocation = new BoardLocation(new Board());
        job.addBoardOrPanelLocation(boardLocation);
    }

    @Test
    public void mapsLoadedFromJobsSavedAsHashMapBecomeConcurrent() throws Exception {
        Job loaded = Configuration.createSerializer().read(Job.class, LEGACY_MAPS);

        assertTrue(loaded.placedStatusMap instanceof ConcurrentHashMap);
        assertTrue(loaded.enabledStateMap instanceof ConcurrentHashMap);
        assertTrue(loaded.checkFiducialsStateMap instanceof ConcurrentHashMap);
        assertTrue(loaded.errorHandlingStateMap instanceof ConcurrentHashMap);
        assertEquals(Boolean.TRUE, loaded.placedStatusMap.get("a")); //$NON-NLS-1$
    }

    @Test
    public void savedJobsReloadWithTheirState() throws Exception {
        job.storePlacedStatus(boardLocation, PLACEMENT_ID, true);
        StringWriter xml = new StringWriter();
        Configuration.createSerializer().write(job, xml);

        Job reloaded = Configuration.createSerializer().read(Job.class, xml.toString());

        assertEquals(job.placedStatusMap, reloaded.placedStatusMap);
    }

    @Test
    public void storingANullErrorHandlingClearsTheStoredState() {
        Placement placement = new Placement(PLACEMENT_ID);
        job.storeErrorHandlingState(boardLocation, placement, Placement.ErrorHandling.Defer);

        job.storeErrorHandlingState(boardLocation, placement, null);

        assertNull(job.errorHandlingStateMap.get(
                boardLocation.getUniqueId() + PlacementsHolderLocation.ID_DELIMITTER + PLACEMENT_ID));
    }

    @Test
    public void readingPlacedStatusWhileAnotherThreadChangesItIsSafe() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                while (running.get()) {
                    job.retrievePlacedStatus(boardLocation, PLACEMENT_ID);
                    job.retrieveEnabledState(boardLocation, null);
                }
            }
            catch (Throwable t) {
                failure.set(t);
            }
            finally {
                done.countDown();
            }
        });
        reader.start();

        for (int i = 0; i < TOGGLES; i++) {
            job.storePlacedStatus(boardLocation, PLACEMENT_ID, true);
            job.removePlacedStatus(boardLocation, PLACEMENT_ID);
            job.storeEnabledState(boardLocation, null, false);
            job.removeEnabledState(boardLocation, null);
        }
        running.set(false);

        assertTrue(done.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertFalse(job.retrievePlacedStatus(boardLocation, PLACEMENT_ID));
    }
}
