package org.openpnp.machine.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceTubeFeeder;
import org.openpnp.model.Configuration;

import com.google.common.io.Files;

public class ReferenceFeederJobFaultsTest {
    private static final int WINDOW = 4;
    private static final int NO_LIMIT = 0;
    private static final int RECORDS = 50_000;
    private static final long THREAD_TIMEOUT_SECONDS = 30;

    private ReferenceFeeder feeder;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp")); //$NON-NLS-1$
        Configuration.get().load();
        feeder = new ReferenceTubeFeeder();
        feeder.setEnabled(true);
    }

    @Test
    public void theWindowKeepsTheNewestOutcomesFirst() {
        feeder.recordJobFault(NO_LIMIT, WINDOW, null);
        for (int i = 0; i < WINDOW - 1; i++) {
            feeder.recordJobSuccess(WINDOW);
        }
        assertEquals("---X", feeder.summariseJobFaults()); //$NON-NLS-1$

        feeder.recordJobSuccess(WINDOW);
        assertEquals("", feeder.summariseJobFaults()); //$NON-NLS-1$
    }

    @Test
    public void reachingTheFaultLimitDisablesAndReEnablingClears() {
        feeder.recordJobFault(2, WINDOW, null);
        assertTrue(feeder.isEnabled());
        feeder.recordJobFault(2, WINDOW, null);
        assertFalse(feeder.isEnabled());

        feeder.setEnabled(true);

        assertEquals("", feeder.summariseJobFaults()); //$NON-NLS-1$
    }

    @Test
    public void summarisingWhileTheJobRecordsIsSafe() throws Exception {
        AtomicBoolean recording = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread edt = new Thread(() -> {
            try {
                while (recording.get()) {
                    feeder.summariseJobFaults();
                }
            }
            catch (Throwable t) {
                failure.set(t);
            }
            finally {
                done.countDown();
            }
        });
        edt.start();

        for (int i = 0; i < RECORDS; i++) {
            feeder.recordJobFault(NO_LIMIT, WINDOW, null);
            feeder.recordJobSuccess(WINDOW);
        }
        recording.set(false);

        assertTrue(done.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(failure.get());
    }
}
