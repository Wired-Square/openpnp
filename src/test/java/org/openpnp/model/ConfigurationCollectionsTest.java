package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.io.Files;

public class ConfigurationCollectionsTest {
    private static final int CHANGES = 20_000;
    private static final long THREAD_TIMEOUT_SECONDS = 30;

    private Configuration configuration;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp")); //$NON-NLS-1$
        configuration = Configuration.get();
        configuration.load();
    }

    @Test
    public void readingPartsWhileAnotherThreadAddsThemIsSafe() throws Exception {
        AtomicBoolean adding = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                while (adding.get()) {
                    configuration.getParts();
                    configuration.getPackages();
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

        for (int i = 0; i < CHANGES; i++) {
            Part part = new Part("CONCURRENT-" + i); //$NON-NLS-1$
            configuration.addPart(part);
            configuration.removePart(part);
        }
        adding.set(false);

        assertTrue(done.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(failure.get());
    }

    @Test
    public void changeEventsCarryACopyAndArriveOnTheEdt() throws Exception {
        List<Object> values = new ArrayList<>();
        AtomicBoolean offEdt = new AtomicBoolean();
        configuration.addPropertyChangeListener("parts", e -> { //$NON-NLS-1$
            values.add(e.getNewValue());
            if (!SwingUtilities.isEventDispatchThread()) {
                offEdt.set(true);
            }
        });
        Part first = new Part("FIRST"); //$NON-NLS-1$

        configuration.addPart(first);
        SwingUtilities.invokeAndWait(() -> {});
        configuration.addPart(new Part("SECOND")); //$NON-NLS-1$
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(2, values.size());
        Map<?, ?> firstEvent = (Map<?, ?>) values.get(0);
        assertTrue(firstEvent.containsKey("FIRST")); //$NON-NLS-1$
        assertTrue(!firstEvent.containsKey("SECOND")); //$NON-NLS-1$
        assertTrue(!offEdt.get());
    }
}
