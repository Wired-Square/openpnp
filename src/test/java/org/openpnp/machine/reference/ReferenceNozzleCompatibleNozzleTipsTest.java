package org.openpnp.machine.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Axis;
import org.openpnp.spi.CoordinateAxis;
import org.openpnp.spi.Machine;
import org.openpnp.spi.NozzleTip;

import com.google.common.io.Files;

public class ReferenceNozzleCompatibleNozzleTipsTest {
    private static final int TIP_COUNT = 32;
    private static final int READER_COUNT = 4;
    private static final int LAZY_INIT_TRIALS = 500;
    private static final int WRITER_CHANGES = 20_000;
    private static final long THREAD_TIMEOUT_SECONDS = 30;
    private static final double LEGACY_SAFE_Z_MM = -5.0;

    private final List<NozzleTip> tips = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = new File(Files.createTempDir(), ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        Machine machine = Configuration.get().getMachine();
        for (int i = 0; i < TIP_COUNT; i++) {
            ReferenceNozzleTip tip = new ReferenceNozzleTip();
            machine.addNozzleTip(tip);
            tips.add(tip);
        }
    }

    @Test
    public void concurrentFirstReadsSeeEveryTipWithoutFailing() throws Exception {
        for (int trial = 0; trial < LAZY_INIT_TRIALS; trial++) {
            TestNozzle nozzle = new TestNozzle();
            for (NozzleTip tip : tips) {
                nozzle.compatibleNozzleTipIds().add(tip.getId());
            }
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> readers = new ArrayList<>();
            for (int r = 0; r < READER_COUNT; r++) {
                readers.add(startThread(failure, () -> {
                    start.await();
                    int seen = 0;
                    for (NozzleTip tip : nozzle.getCompatibleNozzleTips()) {
                        assertNotNull(tip);
                        seen++;
                    }
                    assertEquals(TIP_COUNT, seen);
                }));
            }
            start.countDown();
            joinAll(readers);
            assertNull(failure.get(), () -> "Reader failed: " + failure.get());
        }
    }

    @Test
    public void iterationIsSafeWhileTipsAreAddedAndRemoved() throws Exception {
        TestNozzle nozzle = new TestNozzle();
        for (NozzleTip tip : tips) {
            nozzle.addCompatibleNozzleTip(tip);
        }
        NozzleTip toggled = tips.get(0);
        Set<NozzleTip> allTips = Set.copyOf(tips);
        AtomicBoolean writing = new AtomicBoolean(true);
        CountDownLatch readersRunning = new CountDownLatch(READER_COUNT);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < READER_COUNT; r++) {
            readers.add(startThread(failure, () -> {
                do {
                    int seen = 0;
                    for (NozzleTip tip : nozzle.getCompatibleNozzleTips()) {
                        assertTrue(allTips.contains(tip));
                        seen++;
                    }
                    assertTrue(seen == TIP_COUNT || seen == TIP_COUNT - 1, "Saw " + seen + " tips");
                    readersRunning.countDown();
                } while (writing.get());
            }));
        }
        try {
            assertTrue(readersRunning.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            for (int i = 0; i < WRITER_CHANGES && failure.get() == null; i++) {
                nozzle.removeCompatibleNozzleTip(toggled);
                nozzle.addCompatibleNozzleTip(toggled);
            }
        }
        finally {
            writing.set(false);
            joinAll(readers);
        }
        assertNull(failure.get(), () -> "Reader failed: " + failure.get());
        assertEquals(allTips, nozzle.getCompatibleNozzleTips());
        assertEquals(TIP_COUNT, nozzle.compatibleNozzleTipIds().size());
    }

    // Deprecated because it exercises the deprecated Safe Z migration.
    @Deprecated
    @Test
    public void migrateSafeZReportsFailureInsteadOfLeavingAxisUnmapped() {
        TestNozzle nozzle = new TestNozzle();
        ReferenceControllerAxis axisZ = new ReferenceControllerAxis();
        axisZ.setType(Axis.Type.Z);
        nozzle.setAxisZ(axisZ);
        Length legacySafeZ = new Length(LEGACY_SAFE_Z_MM, LengthUnit.Millimeters);
        nozzle.safeZ = legacySafeZ;
        nozzle.failRawZ = true;

        Exception thrown = assertThrows(Exception.class, nozzle::migrateSafeZ);

        assertTrue(thrown.getMessage().contains(nozzle.getName()), thrown::getMessage);
        assertEquals(TestNozzle.RAW_Z_FAILURE, thrown.getCause().getMessage());
        assertSame(legacySafeZ, nozzle.safeZ, "Legacy Safe Z must be kept when migration fails");
        assertFalse(axisZ.isSafeZoneLowEnabled());
        assertFalse(axisZ.isSafeZoneHighEnabled());
    }

    @Deprecated
    @Test
    public void migrateSafeZMapsSafeZoneOnSuccess() throws Exception {
        TestNozzle nozzle = new TestNozzle();
        ReferenceControllerAxis axisZ = new ReferenceControllerAxis();
        axisZ.setType(Axis.Type.Z);
        nozzle.setAxisZ(axisZ);

        nozzle.migrateSafeZ();

        assertTrue(axisZ.isSafeZoneLowEnabled());
        assertTrue(axisZ.isSafeZoneHighEnabled());
        assertNull(nozzle.safeZ);
    }

    private static Thread startThread(AtomicReference<Throwable> failure, ThrowingRunnable body) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            }
            catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(THREAD_TIMEOUT_SECONDS));
            assertFalse(thread.isAlive(), "Thread did not finish");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static class TestNozzle extends ReferenceNozzle {
        static final String RAW_Z_FAILURE = "simulated transform failure";

        private boolean failRawZ;

        List<String> compatibleNozzleTipIds() {
            return compatibleNozzleTipIds;
        }

        @Override
        protected Length headMountableToRawZ(CoordinateAxis axisZ, Length z) throws Exception {
            if (failRawZ) {
                throw new Exception(RAW_Z_FAILURE);
            }
            return super.headMountableToRawZ(axisZ, z);
        }
    }
}
