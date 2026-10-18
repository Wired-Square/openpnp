package org.openpnp.spi.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.IndexedPropertyChangeEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceTubeFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

import com.google.common.io.Files;

public class AbstractMachineFeedersTest {
    private static final int FEEDERS_ADDED_CONCURRENTLY = 2_000;
    private static final long THREAD_TIMEOUT_SECONDS = 30;

    private Machine machine;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        machine = Configuration.get().getMachine();
    }

    @Test
    public void aListAlreadyReadIsUnchangedByLaterAddsAndRemoves() throws Exception {
        Feeder first = new ReferenceTubeFeeder();
        machine.addFeeder(first);
        List<Feeder> before = machine.getFeeders();
        int sizeBefore = before.size();

        machine.addFeeder(new ReferenceTubeFeeder());
        machine.removeFeeder(first);

        assertEquals(sizeBefore, before.size());
        assertTrue(before.contains(first));
        assertEquals(sizeBefore, machine.getFeeders().size());
        assertNull(machine.getFeeder(first.getId()));
    }

    @Test
    public void addAndRemoveReportTheIndexOfTheChangedFeeder() throws Exception {
        List<Integer> indices = new ArrayList<>();
        ((AbstractMachine) machine).addPropertyChangeListener("feeders",
                e -> indices.add(((IndexedPropertyChangeEvent) e).getIndex()));
        int index = machine.getFeeders().size();
        Feeder feeder = new ReferenceTubeFeeder();

        machine.addFeeder(feeder);
        machine.removeFeeder(feeder);
        machine.removeFeeder(feeder);

        assertEquals(List.of(index, index), indices);
    }

    @Test
    public void iteratingWhileAnotherThreadAddsFeedersIsSafe() throws Exception {
        AtomicBoolean adding = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                while (adding.get()) {
                    for (Feeder feeder : machine.getFeeders()) {
                        feeder.getId();
                    }
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

        for (int i = 0; i < FEEDERS_ADDED_CONCURRENTLY; i++) {
            machine.addFeeder(new ReferenceTubeFeeder());
        }
        adding.set(false);

        assertTrue(done.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(failure.get());
    }
}
