package org.openpnp.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import javax.swing.SwingUtilities;

import org.pmw.tinylog.Logger;

/**
 * Tells subscribers that model properties they depend on changed, without each subscriber adding
 * listeners to every model object. Every event an {@link AbstractModelObject} fires is reported
 * here, from any thread. A subscriber names the source types and property names it reads, and
 * runs once per burst of matching events, later, on the delivery executor (the EDT by default).
 */
public final class ModelChangeService {
    /** A listener and the source types and property names it depends on. */
    public final class Subscription {
        private final Map<Class<?>, Set<String>> propertyNamesBySourceType;
        private final Runnable listener;
        private volatile boolean cancelled;

        private Subscription(Map<? extends Class<?>, Set<String>> propertyNamesBySourceType,
                Runnable listener) {
            this.propertyNamesBySourceType = Map.copyOf(propertyNamesBySourceType);
            this.listener = listener;
        }

        private boolean matches(Object source, String propertyName) {
            for (Map.Entry<Class<?>, Set<String>> entry : propertyNamesBySourceType.entrySet()) {
                if (entry.getKey().isInstance(source) && entry.getValue().contains(propertyName)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * A delivery already running on another thread may still run the listener once; cancel on
         * the delivery thread to rule that out.
         */
        public void cancel() {
            cancelled = true;
            subscriptions.remove(this);
            synchronized (lock) {
                pending.remove(this);
            }
        }
    }

    private final Executor deliveryExecutor;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Boolean> ignoringChanges = ThreadLocal.withInitial(() -> false);

    private final Object lock = new Object();
    // Guarded by lock.
    private final Set<Subscription> pending = new LinkedHashSet<>();
    private boolean deliveryScheduled;

    public ModelChangeService() {
        this(SwingUtilities::invokeLater);
    }

    public ModelChangeService(Executor deliveryExecutor) {
        this.deliveryExecutor = deliveryExecutor;
    }

    /**
     * @param propertyNamesBySourceType for each source type (a class or interface the source is an
     *        instance of), the property names whose changes the listener depends on
     */
    public Subscription subscribe(Map<? extends Class<?>, Set<String>> propertyNamesBySourceType,
            Runnable listener) {
        Subscription subscription = new Subscription(propertyNamesBySourceType, listener);
        subscriptions.add(subscription);
        return subscription;
    }

    /**
     * Runs the action with any matching change it raises on this thread logged and dropped, so a
     * recompute that reads the model can never schedule itself again. A subscriber wraps its
     * recompute in this; changes its other listener code makes are delivered as usual.
     */
    public void ignoreChangesDuring(Runnable action) {
        boolean wasIgnoring = ignoringChanges.get();
        ignoringChanges.set(true);
        try {
            action.run();
        }
        finally {
            ignoringChanges.set(wasIgnoring);
        }
    }

    void modelChanged(Object source, String propertyName) {
        boolean scheduleDelivery = false;
        for (Subscription subscription : subscriptions) {
            if (!subscription.matches(source, propertyName)) {
                continue;
            }
            if (ignoringChanges.get()) {
                Logger.warn("Ignored {} change of {} raised while a model change recompute ran",
                        propertyName, source.getClass().getSimpleName());
                return;
            }
            synchronized (lock) {
                pending.add(subscription);
                if (!deliveryScheduled) {
                    deliveryScheduled = true;
                    scheduleDelivery = true;
                }
            }
        }
        if (scheduleDelivery) {
            deliveryExecutor.execute(this::deliver);
        }
    }

    private void deliver() {
        List<Subscription> due;
        synchronized (lock) {
            due = new ArrayList<>(pending);
            pending.clear();
            deliveryScheduled = false;
        }
        for (Subscription subscription : due) {
            if (subscription.cancelled) {
                continue;
            }
            try {
                subscription.listener.run();
            }
            catch (RuntimeException e) {
                Logger.error(e, "Model change listener failed");
            }
        }
    }
}
