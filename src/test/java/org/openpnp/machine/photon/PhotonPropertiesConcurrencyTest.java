package org.openpnp.machine.photon;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;

import com.google.common.io.Files;

public class PhotonPropertiesConcurrencyTest {
    private static final int TRIALS = 500;
    private static final long THREAD_TIMEOUT_SECONDS = 30;

    private Machine machine;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp")); //$NON-NLS-1$
        Configuration.get().load();
        machine = Configuration.get().getMachine();
    }

    @Test
    public void twoThreadsUsingSlotsForTheFirstTimeShareOneSet() throws Exception {
        for (int trial = 0; trial < TRIALS; trial++) {
            machine.setProperty(PhotonProperties.FEEDER_SLOTS_PROPERTY, null);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<PhotonFeederSlots> other = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    other.set(new PhotonProperties(machine).getFeederSlots());
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            thread.start();

            start.countDown();
            PhotonFeederSlots mine = new PhotonProperties(machine).getFeederSlots();
            thread.join(TimeUnit.SECONDS.toMillis(THREAD_TIMEOUT_SECONDS));

            assertSame(mine, other.get());
            assertSame(mine, machine.getProperty(PhotonProperties.FEEDER_SLOTS_PROPERTY));
        }
    }

    @Test
    public void settingAPropertyToNullRemovesIt() {
        machine.setProperty(PhotonProperties.MAX_FEEDER_ADDRESS, 1);

        machine.setProperty(PhotonProperties.MAX_FEEDER_ADDRESS, null);

        assertNull(machine.getProperty(PhotonProperties.MAX_FEEDER_ADDRESS));
        assertTrue(new PhotonProperties(machine).getMaxFeederAddress() > 0);
    }
}
