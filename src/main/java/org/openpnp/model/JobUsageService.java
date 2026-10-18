package org.openpnp.model;

import static java.util.Map.entry;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.openpnp.events.JobLoadedEvent;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;

import com.google.common.eventbus.Subscribe;

/**
 * Which parts and feeders the open job needs, and the setup issues that would stop it placing
 * them. A part is in use when an enabled placement of type Placement on the side facing up, under
 * enabled board and panel locations, needs it; placed status is ignored. A feeder is in use when it
 * holds a part in use. With no job open nothing is in use.
 */
public final class JobUsageService {
    public enum Issue {
        PART_HEIGHT_UNKNOWN,
        NO_COMPATIBLE_NOZZLE_TIP,
        /** Parts only. */
        NO_ENABLED_FEEDER,
        /** Feeders only; a disabled feeder holding a part in use is still in use. */
        FEEDER_DISABLED
    }

    public static final class Usage {
        public static final Usage NOT_IN_USE = new Usage(false, Collections.emptySet());

        private final boolean inUse;
        private final Set<Issue> issues;

        private Usage(boolean inUse, Set<Issue> issues) {
            this.inUse = inUse;
            this.issues = issues;
        }

        public static Usage inUse(Set<Issue> issues) {
            Set<Issue> copy = EnumSet.noneOf(Issue.class);
            copy.addAll(issues);
            return new Usage(true, Collections.unmodifiableSet(copy));
        }

        public boolean isInUse() {
            return inUse;
        }

        /** Empty unless in use. */
        public Set<Issue> getIssues() {
            return issues;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Usage && ((Usage) o).inUse == inUse
                    && ((Usage) o).issues.equals(issues);
        }

        @Override
        public int hashCode() {
            return Objects.hash(inUse, issues);
        }

        @Override
        public String toString() {
            return inUse ? "in use " + issues : "not in use"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Immutable; the parts and feeders absent from it are not in use. */
    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());

        private final Map<Part, Usage> parts;
        private final Map<Feeder, Usage> feeders;

        private Snapshot(Map<Part, Usage> parts, Map<Feeder, Usage> feeders) {
            this.parts = Map.copyOf(parts);
            this.feeders = Map.copyOf(feeders);
        }

        public Usage getUsage(Part part) {
            return parts.getOrDefault(part, Usage.NOT_IN_USE);
        }

        public Usage getUsage(Feeder feeder) {
            return feeders.getOrDefault(feeder, Usage.NOT_IN_USE);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Snapshot && ((Snapshot) o).parts.equals(parts)
                    && ((Snapshot) o).feeders.equals(feeders);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parts, feeders);
        }
    }

    // Everything compute() reads. Feeders report derived part and enabled changes themselves.
    private static final Map<Class<?>, Set<String>> USAGE_INPUTS = Map.ofEntries(
            entry(Job.class, Set.of("rootPanelLocation")), //$NON-NLS-1$
            entry(PlacementsHolderLocation.class,
                    Set.of("locallyEnabled", "side", "placementsHolder")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            entry(Panel.class, Set.of("children", "child")), //$NON-NLS-1$ //$NON-NLS-2$
            entry(PlacementsHolder.class, Set.of("placements", "placement")), //$NON-NLS-1$ //$NON-NLS-2$
            entry(Placement.class, Set.of("part", "type", "enabled", "side")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            entry(Part.class, Set.of("height", "package")), //$NON-NLS-1$ //$NON-NLS-2$
            entry(Package.class, Set.of("compatibleNozzleTips")), //$NON-NLS-1$
            entry(Nozzle.class, Set.of("compatibleNozzleTips")), //$NON-NLS-1$
            entry(Head.class, Set.of("nozzles")), //$NON-NLS-1$
            entry(Machine.class, Set.of("feeders")), //$NON-NLS-1$
            entry(Feeder.class, Set.of("enabled", "part"))); //$NON-NLS-1$ //$NON-NLS-2$

    private final Configuration configuration;
    private final ModelChangeService modelChangeService;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Job job;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public JobUsageService(Configuration configuration, ModelChangeService modelChangeService) {
        this.configuration = configuration;
        this.modelChangeService = modelChangeService;
        modelChangeService.subscribe(USAGE_INPUTS, this::recompute);
    }

    @Subscribe
    public void jobLoaded(JobLoadedEvent event) {
        setJob(event.job);
    }

    /** Recomputes on the calling thread, so the snapshot is current when this returns. */
    public void setJob(Job job) {
        this.job = job;
        recompute();
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    /**
     * The listener runs when the snapshot changes, on the thread that recomputed it: the EDT after
     * a model change, the caller of {@link #setJob(Job)} after a job loads.
     */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void recompute() {
        boolean[] changed = {false};
        modelChangeService.ignoreChangesDuring(() -> changed[0] = computeAndPublish());
        if (changed[0]) {
            for (Runnable listener : listeners) {
                listener.run();
            }
        }
    }

    // Synchronized so whichever recompute publishes last read the latest job.
    private synchronized boolean computeAndPublish() {
        Snapshot next = compute(job, configuration.getMachine());
        if (next.equals(snapshot)) {
            return false;
        }
        snapshot = next;
        return true;
    }

    static Snapshot compute(Job job, Machine machine) {
        if (job == null) {
            return Snapshot.EMPTY;
        }
        Set<Part> partsInUse = new HashSet<>();
        Job.forEachActivePlacement(job.getRootPanelLocation(), (boardLocation, placement) -> {
            if (placement.getPart() != null) {
                partsInUse.add(placement.getPart());
            }
        });
        if (partsInUse.isEmpty()) {
            return Snapshot.EMPTY;
        }

        Set<NozzleTip> loadableNozzleTips = new HashSet<>();
        List<Feeder> feeders = Collections.emptyList();
        if (machine != null) {
            for (Head head : machine.getHeads()) {
                for (Nozzle nozzle : head.getNozzles()) {
                    loadableNozzleTips.addAll(nozzle.getCompatibleNozzleTips());
                }
            }
            feeders = machine.getFeeders();
        }

        Map<Part, Set<Issue>> partIssues = new HashMap<>();
        for (Part part : partsInUse) {
            partIssues.put(part, partIssues(part, loadableNozzleTips));
        }

        Set<Part> partsInEnabledFeeders = new HashSet<>();
        Map<Feeder, Usage> feederUsage = new HashMap<>();
        for (Feeder feeder : feeders) {
            Part part = feeder.getPart();
            if (!partsInUse.contains(part)) {
                continue;
            }
            Set<Issue> issues = EnumSet.noneOf(Issue.class);
            issues.addAll(partIssues.get(part));
            if (feeder.isEnabled()) {
                partsInEnabledFeeders.add(part);
            }
            else {
                issues.add(Issue.FEEDER_DISABLED);
            }
            feederUsage.put(feeder, Usage.inUse(issues));
        }

        Map<Part, Usage> partUsage = new HashMap<>();
        for (Map.Entry<Part, Set<Issue>> entry : partIssues.entrySet()) {
            Set<Issue> issues = entry.getValue();
            if (!partsInEnabledFeeders.contains(entry.getKey())) {
                issues.add(Issue.NO_ENABLED_FEEDER);
            }
            partUsage.put(entry.getKey(), Usage.inUse(issues));
        }
        return new Snapshot(partUsage, feederUsage);
    }

    private static Set<Issue> partIssues(Part part, Set<NozzleTip> loadableNozzleTips) {
        Set<Issue> issues = EnumSet.noneOf(Issue.class);
        if (part.isPartHeightUnknown()) {
            issues.add(Issue.PART_HEIGHT_UNKNOWN);
        }
        Package pkg = part.getPackage();
        if (pkg == null || Collections.disjoint(pkg.getCompatibleNozzleTips(), loadableNozzleTips)) {
            issues.add(Issue.NO_COMPATIBLE_NOZZLE_TIP);
        }
        return issues;
    }
}
