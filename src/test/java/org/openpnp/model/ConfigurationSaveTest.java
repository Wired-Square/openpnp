package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.common.io.Files;

public class ConfigurationSaveTest {
    private static final int ENTRIES = 2_000;
    private static final int SAVES = 10;
    private static final long THREAD_TIMEOUT_SECONDS = 30;
    private static final String KEY = "script-key"; //$NON-NLS-1$

    @Test
    public void savingWhileAScriptChangesItsStateIsSafeAndReloads() throws Exception {
        File directory = new File(Files.createTempDir(), ".openpnp"); //$NON-NLS-1$
        Configuration.initialize(directory);
        Configuration configuration = Configuration.get();
        configuration.load();
        for (int i = 0; i < ENTRIES; i++) {
            configuration.scriptState.put(KEY + i, Integer.toString(i));
        }
        AtomicBoolean saving = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread script = new Thread(() -> {
            try {
                for (int i = 0; saving.get(); i++) {
                    String key = "transient-" + (i % ENTRIES); //$NON-NLS-1$
                    configuration.scriptState.put(key, "x"); //$NON-NLS-1$
                    configuration.scriptState.remove(key);
                }
            }
            catch (Throwable t) {
                failure.set(t);
            }
            finally {
                done.countDown();
            }
        });
        script.start();

        try {
            for (int i = 0; i < SAVES; i++) {
                configuration.save();
            }
        }
        finally {
            saving.set(false);
        }

        assertTrue(done.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(failure.get());
        Configuration.initialize(directory);
        Configuration.get().load();
        assertEquals("7", Configuration.get().scriptState.get(KEY + 7)); //$NON-NLS-1$
    }
}
