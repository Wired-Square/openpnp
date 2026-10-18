package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.spi.NozzleTip;

import com.google.common.io.Files;

public class CompatibleNozzleTipsTest {
    private static final int TIP_COUNT = 32;
    private static final int READER_COUNT = 4;
    private static final int LAZY_INIT_TRIALS = 500;
    private static final int WRITER_CHANGES = 20_000;
    private static final long THREAD_TIMEOUT_SECONDS = 30;

    private final List<NozzleTip> tips = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        for (int i = 0; i < TIP_COUNT; i++) {
            ReferenceNozzleTip tip = new ReferenceNozzleTip();
            Configuration.get().getMachine().addNozzleTip(tip);
            tips.add(tip);
        }
    }

    @Test
    public void resolvesThePersistedIdsAndSkipsUnknownOnes() {
        List<String> ids = new ArrayList<>(List.of(tips.get(0).getId(), "no-such-tip")); //$NON-NLS-1$

        Set<NozzleTip> resolved = new CompatibleNozzleTips(() -> ids).get();

        assertEquals(Set.of(tips.get(0)), resolved);
    }

    @Test
    public void readsTheIdListTheOwnerCurrentlyHolds() {
        AtomicReference<List<String>> ownerIds = new AtomicReference<>(new ArrayList<>());
        CompatibleNozzleTips compatible = new CompatibleNozzleTips(ownerIds::get);
        ownerIds.set(new ArrayList<>(List.of(tips.get(1).getId())));

        assertEquals(Set.of(tips.get(1)), compatible.get());
    }

    @Test
    public void addAndRemoveKeepThePersistedIdsInStep() {
        List<String> ids = new ArrayList<>();
        CompatibleNozzleTips compatible = new CompatibleNozzleTips(() -> ids);

        Set<NozzleTip> afterAdd = compatible.add(tips.get(0));
        compatible.add(tips.get(1));
        Set<NozzleTip> afterRemove = compatible.remove(tips.get(0));

        assertEquals(Set.of(tips.get(0)), afterAdd);
        assertEquals(Set.of(tips.get(1)), afterRemove);
        assertEquals(List.of(tips.get(1).getId()), ids);
        assertFalse(afterRemove.contains(tips.get(0)));
    }

    @Test
    public void editsKeepSavedIdsThatDoNotResolve() {
        String unknownId = "no-such-tip"; //$NON-NLS-1$
        List<String> ids = new ArrayList<>(List.of(unknownId, tips.get(0).getId()));
        CompatibleNozzleTips compatible = new CompatibleNozzleTips(() -> ids);

        compatible.add(tips.get(1));
        compatible.remove(tips.get(0));

        assertEquals(List.of(unknownId, tips.get(1).getId()), ids);
        assertEquals(Set.of(tips.get(1)), compatible.get());
    }

    @Test
    public void removeDropsEveryCopyOfTheId() {
        String id = tips.get(0).getId();
        List<String> ids = new ArrayList<>(List.of(id, id));
        CompatibleNozzleTips compatible = new CompatibleNozzleTips(() -> ids);

        compatible.remove(tips.get(0));

        assertTrue(ids.isEmpty());
        assertTrue(compatible.get().isEmpty());
    }

    @Test
    public void nullTipIsRejectedWithoutTouchingTheIds() {
        List<String> ids = new ArrayList<>(List.of(tips.get(0).getId()));
        CompatibleNozzleTips compatible = new CompatibleNozzleTips(() -> ids);

        assertThrows(NullPointerException.class, () -> compatible.add(null));

        assertEquals(List.of(tips.get(0).getId()), ids);
        assertEquals(Set.of(tips.get(0)), compatible.get());
    }

    @Test
    public void packageChangeEventCarriesTheNewTips() {
        Package pkg = new Package("events"); //$NON-NLS-1$
        List<Object> published = new ArrayList<>();
        pkg.addPropertyChangeListener("compatibleNozzleTips", e -> published.add(e.getNewValue())); //$NON-NLS-1$

        pkg.addCompatibleNozzleTip(tips.get(0));
        pkg.removeCompatibleNozzleTip(tips.get(0));

        assertEquals(List.of(Set.of(tips.get(0)), Set.of()), published);
    }

    @Test
    public void concurrentFirstReadsSeeEveryTipWithoutFailing() throws Exception {
        List<String> allIds = new ArrayList<>();
        for (NozzleTip tip : tips) {
            allIds.add(tip.getId());
        }
        for (int trial = 0; trial < LAZY_INIT_TRIALS; trial++) {
            CompatibleNozzleTips compatible = new CompatibleNozzleTips(() -> allIds);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> readers = new ArrayList<>();
            for (int r = 0; r < READER_COUNT; r++) {
                readers.add(startThread(failure, () -> {
                    start.await();
                    int seen = 0;
                    for (NozzleTip tip : compatible.get()) {
                        assertTrue(tips.contains(tip));
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
    public void packageIterationIsSafeWhileTipsAreAddedAndRemoved() throws Exception {
        Package pkg = new Package("concurrency"); //$NON-NLS-1$
        for (NozzleTip tip : tips) {
            pkg.addCompatibleNozzleTip(tip);
        }
        NozzleTip toggled = tips.get(0);
        AtomicBoolean writing = new AtomicBoolean(true);
        CountDownLatch readersRunning = new CountDownLatch(READER_COUNT);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < READER_COUNT; r++) {
            readers.add(startThread(failure, () -> {
                do {
                    int seen = 0;
                    for (NozzleTip tip : pkg.getCompatibleNozzleTips()) {
                        assertTrue(tips.contains(tip));
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
                pkg.removeCompatibleNozzleTip(toggled);
                pkg.addCompatibleNozzleTip(toggled);
            }
        }
        finally {
            writing.set(false);
            joinAll(readers);
        }
        assertNull(failure.get(), () -> "Reader failed: " + failure.get());
        assertEquals(Set.copyOf(tips), pkg.getCompatibleNozzleTips());
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
}
