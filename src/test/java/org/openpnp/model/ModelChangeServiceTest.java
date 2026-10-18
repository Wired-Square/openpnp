package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceTubeFeeder;
import org.openpnp.spi.Feeder;

import com.google.common.io.Files;

public class ModelChangeServiceTest {
    private static final String HEIGHT = "height"; //$NON-NLS-1$
    private static final int MAX_DELIVERIES = 10;

    private final Queue<Runnable> deliveries = new ArrayDeque<>();
    private final ModelChangeService service = new ModelChangeService(deliveries::add);
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
    }

    private ModelChangeService.Subscription subscribeToPartHeight(Runnable listener) {
        return service.subscribe(Map.of(Part.class, Set.of(HEIGHT)), listener);
    }

    // Bounded so a delivery that reschedules itself fails the test instead of hanging it.
    private void runDeliveries() {
        for (int i = 0; i < MAX_DELIVERIES && !deliveries.isEmpty(); i++) {
            deliveries.remove().run();
        }
    }

    @Test
    public void aBurstOfMatchingChangesIsDeliveredOnce() {
        subscribeToPartHeight(calls::incrementAndGet);
        Part part = new Part("burst"); //$NON-NLS-1$

        service.modelChanged(part, HEIGHT);
        service.modelChanged(part, HEIGHT);
        service.modelChanged(new Part("other"), HEIGHT); //$NON-NLS-1$

        assertEquals(1, deliveries.size());
        runDeliveries();
        assertEquals(1, calls.get());
    }

    @Test
    public void onlyTheSubscribedSourceTypesAndPropertiesAreDelivered() {
        subscribeToPartHeight(calls::incrementAndGet);

        service.modelChanged(new Part("name"), "name"); //$NON-NLS-1$ //$NON-NLS-2$
        service.modelChanged(new Package("height"), HEIGHT); //$NON-NLS-1$

        assertTrue(deliveries.isEmpty());
    }

    @Test
    public void sourceTypesMatchSubclassesAndInterfaces() {
        service.subscribe(Map.of(Feeder.class, Set.of("enabled")), calls::incrementAndGet); //$NON-NLS-1$

        service.modelChanged(new ReferenceTubeFeeder(),
                "enabled"); //$NON-NLS-1$
        runDeliveries();

        assertEquals(1, calls.get());
    }

    @Test
    public void changesRaisedWhileARecomputeRunsDoNotScheduleAnother() {
        Part part = new Part("recompute"); //$NON-NLS-1$
        subscribeToPartHeight(() -> {
            calls.incrementAndGet();
            service.ignoreChangesDuring(() -> service.modelChanged(part, HEIGHT));
        });

        service.modelChanged(part, HEIGHT);
        runDeliveries();

        assertEquals(1, calls.get());
        assertTrue(deliveries.isEmpty());
    }

    @Test
    public void aChangeAListenerMakesOutsideItsRecomputeIsDelivered() {
        Part part = new Part("listener"); //$NON-NLS-1$
        subscribeToPartHeight(() -> {
            if (calls.incrementAndGet() == 1) {
                service.modelChanged(part, HEIGHT);
            }
        });

        service.modelChanged(part, HEIGHT);
        runDeliveries();

        assertEquals(2, calls.get());
    }

    @Test
    public void aChangeAfterADeliverySchedulesTheNextOne() {
        subscribeToPartHeight(calls::incrementAndGet);
        Part part = new Part("again"); //$NON-NLS-1$

        service.modelChanged(part, HEIGHT);
        runDeliveries();
        service.modelChanged(part, HEIGHT);
        runDeliveries();

        assertEquals(2, calls.get());
    }

    @Test
    public void aCancelledSubscriptionIsNotDelivered() {
        ModelChangeService.Subscription subscription = subscribeToPartHeight(calls::incrementAndGet);

        service.modelChanged(new Part("cancelled"), HEIGHT); //$NON-NLS-1$
        subscription.cancel();
        runDeliveries();

        assertEquals(0, calls.get());
    }

    @Test
    public void aFailingListenerDoesNotStopTheOthers() {
        subscribeToPartHeight(() -> {
            throw new IllegalStateException("listener failure"); //$NON-NLS-1$
        });
        subscribeToPartHeight(calls::incrementAndGet);

        service.modelChanged(new Part("failing"), HEIGHT); //$NON-NLS-1$
        runDeliveries();

        assertEquals(1, calls.get());
    }

    @Test
    public void modelObjectsReportChangesToTheConfigurationsServiceOnTheEdt() throws Exception {
        AtomicInteger edtCalls = new AtomicInteger();
        Configuration.get().getModelChangeService().subscribe(Map.of(Part.class, Set.of(HEIGHT)),
                () -> {
                    if (SwingUtilities.isEventDispatchThread()) {
                        edtCalls.incrementAndGet();
                    }
                });
        Part part = new Part("edt"); //$NON-NLS-1$
        Length height = new Length(1, LengthUnit.Millimeters);

        part.setHeight(height);
        SwingUtilities.invokeAndWait(() -> {});
        part.setHeight(height);
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(1, edtCalls.get());
    }

    @Test
    public void objectsOfAPreviousConfigurationAreHarmless() throws Exception {
        Part oldPart = new Part("old"); //$NON-NLS-1$
        ModelChangeService oldService = Configuration.get().getModelChangeService();
        AtomicInteger oldCalls = new AtomicInteger();
        oldService.subscribe(Map.of(Part.class, Set.of(HEIGHT)), oldCalls::incrementAndGet);
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();

        oldPart.setHeight(new Length(2, LengthUnit.Millimeters));
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(0, oldCalls.get());
    }
}
