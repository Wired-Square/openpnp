package org.openpnp.spi.base;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.spi.MachineListener;

import com.google.common.io.Files;

public class AbstractMachineListenersTest {
    private static final int LISTENER_CHANGES = 20_000;
    private static final long THREAD_TIMEOUT_SECONDS = 30;

    private AbstractMachine machine;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp")); //$NON-NLS-1$
        Configuration.get().load();
        machine = (AbstractMachine) Configuration.get().getMachine();
    }

    @Test
    public void firingWhileAnotherThreadAddsAndRemovesListenersIsSafe() throws Exception {
        AtomicBoolean changing = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread machineThread = new Thread(() -> {
            try {
                while (changing.get()) {
                    machine.fireMachineHeadActivity(null);
                }
            }
            catch (Throwable t) {
                failure.set(t);
            }
            finally {
                done.countDown();
            }
        });
        machineThread.start();

        for (int i = 0; i < LISTENER_CHANGES; i++) {
            MachineListener listener = new MachineListener.Adapter();
            machine.addListener(listener);
            machine.removeListener(listener);
        }
        changing.set(false);

        assertTrue(done.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(failure.get());
    }
}
