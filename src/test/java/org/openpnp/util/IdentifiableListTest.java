package org.openpnp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Identifiable;

public class IdentifiableListTest {
    private static final int CHANGES = 50_000;
    private static final long THREAD_TIMEOUT_SECONDS = 30;

    private static final class Item implements Identifiable {
        private final String id;

        Item(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    @Test
    public void findsByIdAndCreatesTheNextFreeId() {
        IdentifiableList<Item> list = new IdentifiableList<>();
        Item first = new Item("N1"); //$NON-NLS-1$
        list.add(first);

        assertSame(first, list.get("N1")); //$NON-NLS-1$
        assertNull(list.get("N2")); //$NON-NLS-1$
        assertEquals("N2", list.createId("N")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void iteratorsDoNotRemove() {
        IdentifiableList<Item> list = new IdentifiableList<>();
        list.add(new Item("A")); //$NON-NLS-1$
        Iterator<Item> iterator = list.iterator();
        iterator.next();

        assertThrows(UnsupportedOperationException.class, iterator::remove);
    }

    @Test
    public void iteratingWhileAnotherThreadChangesTheListIsSafe() throws Exception {
        IdentifiableList<Item> list = new IdentifiableList<>();
        AtomicBoolean changing = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread machineThread = new Thread(() -> {
            try {
                while (changing.get()) {
                    for (Item item : list) {
                        item.getId();
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
        machineThread.start();

        for (int i = 0; i < CHANGES; i++) {
            Item item = new Item("I" + i); //$NON-NLS-1$
            list.add(item);
            list.remove(item);
        }
        changing.set(false);

        assertTrue(done.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull(failure.get());
    }
}
