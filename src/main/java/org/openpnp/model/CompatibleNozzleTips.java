package org.openpnp.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.openpnp.spi.NozzleTip;

/**
 * The nozzle tips a nozzle or package is compatible with, resolved lazily from its persisted IDs.
 * The set is an immutable snapshot that is replaced, never mutated, so machine and camera threads
 * can iterate it while configuration load or the GUI changes it.
 */
public final class CompatibleNozzleTips {
    // A supplier because the owner's ID list field is not final and could be reassigned.
    private final Supplier<List<String>> ids;
    private final Object lock = new Object();
    private volatile Set<NozzleTip> tips;

    public CompatibleNozzleTips(Supplier<List<String>> ids) {
        this.ids = ids;
    }

    public Set<NozzleTip> get() {
        Set<NozzleTip> current = tips;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (tips == null) {
                Set<NozzleTip> resolved = new HashSet<>();
                for (String nozzleTipId : ids.get()) {
                    NozzleTip nt = Configuration.get().getMachine().getNozzleTip(nozzleTipId);
                    if (nt != null) {
                        resolved.add(nt);
                    }
                }
                tips = Collections.unmodifiableSet(resolved);
            }
            return tips;
        }
    }

    /**
     * Saved IDs that resolve to no nozzle tip are kept, so an edit never drops them from the
     * configuration.
     *
     * @return the new snapshot, for the owner's property change event
     */
    public Set<NozzleTip> add(NozzleTip nt) {
        String id = Objects.requireNonNull(nt).getId();
        return replace(set -> set.add(nt), idList -> {
            if (!idList.contains(id)) {
                idList.add(id);
            }
        });
    }

    /** @return the new snapshot, for the owner's property change event */
    public Set<NozzleTip> remove(NozzleTip nt) {
        String id = Objects.requireNonNull(nt).getId();
        return replace(set -> set.remove(nt), idList -> idList.removeIf(id::equals));
    }

    private Set<NozzleTip> replace(Consumer<Set<NozzleTip>> changeTips,
            Consumer<List<String>> changeIds) {
        synchronized (lock) {
            Set<NozzleTip> next = new HashSet<>(get());
            changeTips.accept(next);
            changeIds.accept(ids.get());
            tips = Collections.unmodifiableSet(next);
            return tips;
        }
    }
}
