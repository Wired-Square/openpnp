package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.events.JobLoadedEvent;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.JobUsageService.Issue;
import org.openpnp.model.JobUsageService.Usage;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.NozzleTip;

import com.google.common.io.Files;

public class JobUsageServiceTest {
    private static final String CONFIG = "config/BasicJobTest/"; //$NON-NLS-1$
    private static final String PART_ID = "R-0805-10K"; //$NON-NLS-1$
    private static final String FEEDER_ID = "F1"; //$NON-NLS-1$

    private Configuration configuration;
    private JobUsageService service;
    private Machine machine;
    private Part part;
    private Feeder feeder;
    private Job job;
    private BoardLocation boardLocation;
    private Placement placement;

    @BeforeEach
    public void setUp() throws Exception {
        File directory = new File(Files.createTempDir(), ".openpnp"); //$NON-NLS-1$
        for (String file : new String[] {"machine.xml", "packages.xml", "parts.xml"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            FileUtils.copyURLToFile(ClassLoader.getSystemResource(CONFIG + file),
                    new File(directory, file));
        }
        Configuration.initialize(directory);
        configuration = Configuration.get();
        configuration.load();
        service = configuration.getJobUsageService();
        machine = configuration.getMachine();
        part = configuration.getPart(PART_ID);
        feeder = machine.getFeeder(FEEDER_ID);

        Board board = new Board();
        placement = new Placement("R1"); //$NON-NLS-1$
        placement.setPart(part);
        placement.setSide(Side.Top);
        board.addPlacement(placement);
        boardLocation = new BoardLocation(board);
        boardLocation.setGlobalSide(Side.Top);
        job = new Job();
        job.addBoardOrPanelLocation(boardLocation);
    }

    private Usage partUsage() {
        return service.getSnapshot().getUsage(part);
    }

    private Usage feederUsage() {
        return service.getSnapshot().getUsage(feeder);
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
    }

    @Test
    public void nothingIsInUseWithoutAJob() {
        assertSame(Usage.NOT_IN_USE, partUsage());
        assertSame(Usage.NOT_IN_USE, feederUsage());
    }

    @Test
    public void loadingAJobComputesTheSnapshotSynchronously() {
        configuration.getBus().post(new JobLoadedEvent(job));

        assertTrue(partUsage().isInUse());
        assertEquals(Set.of(), partUsage().getIssues());
        assertTrue(feederUsage().isInUse());
        assertEquals(Set.of(), feederUsage().getIssues());
    }

    @Test
    public void placedStatusIsIgnored() {
        job.storePlacedStatus(boardLocation, placement.getId(), true);

        service.setJob(job);

        assertTrue(partUsage().isInUse());
    }

    @Test
    public void placementsTheJobWouldNotPlaceAreNotInUse() {
        placement.setEnabled(false);
        service.setJob(job);
        assertFalse(partUsage().isInUse());

        placement.setEnabled(true);
        placement.setType(Placement.Type.Fiducial);
        service.setJob(job);
        assertFalse(partUsage().isInUse());

        placement.setType(Placement.Type.Placement);
        placement.setSide(Side.Bottom);
        service.setJob(job);
        assertFalse(partUsage().isInUse());
        assertFalse(feederUsage().isInUse());
    }

    @Test
    public void disabledBoardLocationsAreNotInUse() {
        boardLocation.setLocallyEnabled(false);

        service.setJob(job);

        assertFalse(partUsage().isInUse());
    }

    @Test
    public void panelsAreTraversedAndTheirStateApplies() {
        PanelLocation panelLocation = new PanelLocation(new Panel());
        panelLocation.addChild(boardLocation);
        job = new Job();
        job.addBoardOrPanelLocation(panelLocation);

        service.setJob(job);
        assertTrue(partUsage().isInUse());

        panelLocation.setGlobalSide(Side.Bottom);
        service.setJob(job);
        assertFalse(partUsage().isInUse());

        panelLocation.setGlobalSide(Side.Top);
        panelLocation.setLocallyEnabled(false);
        service.setJob(job);
        assertFalse(partUsage().isInUse());
    }

    @Test
    public void aPartWithNoHeightIsReportedOnThePartAndItsFeeders() {
        part.setHeight(null);

        service.setJob(job);

        assertEquals(Set.of(Issue.PART_HEIGHT_UNKNOWN), partUsage().getIssues());
        assertEquals(Set.of(Issue.PART_HEIGHT_UNKNOWN), feederUsage().getIssues());
    }

    @Test
    public void aPackageNoNozzleCanLoadATipForIsReported() {
        for (NozzleTip tip : Set.copyOf(part.getPackage().getCompatibleNozzleTips())) {
            part.getPackage().removeCompatibleNozzleTip(tip);
        }

        service.setJob(job);

        assertEquals(Set.of(Issue.NO_COMPATIBLE_NOZZLE_TIP), partUsage().getIssues());
    }

    @Test
    public void aDisabledFeederIsStillInUseAndLeavesThePartWithoutAFeeder() {
        feeder.setEnabled(false);

        service.setJob(job);

        assertEquals(Set.of(Issue.NO_ENABLED_FEEDER), partUsage().getIssues());
        assertTrue(feederUsage().isInUse());
        assertEquals(Set.of(Issue.FEEDER_DISABLED), feederUsage().getIssues());
    }

    @Test
    public void aModelChangeRecomputesOnTheEdtAndNotifiesOnlyOnChange() throws Exception {
        service.setJob(job);
        AtomicInteger notifications = new AtomicInteger();
        AtomicInteger offEdtNotifications = new AtomicInteger();
        service.addListener(() -> {
            notifications.incrementAndGet();
            if (!SwingUtilities.isEventDispatchThread()) {
                offEdtNotifications.incrementAndGet();
            }
        });

        part.setName("renamed"); //$NON-NLS-1$
        flushEdt();
        assertEquals(0, notifications.get());

        part.setHeight(null);
        flushEdt();
        assertEquals(1, notifications.get());
        assertEquals(Set.of(Issue.PART_HEIGHT_UNKNOWN), partUsage().getIssues());

        feeder.setEnabled(false);
        machine.removeFeeder(feeder);
        flushEdt();
        assertEquals(2, notifications.get());
        assertFalse(feederUsage().isInUse());
        assertEquals(0, offEdtNotifications.get());
    }

    @Test
    public void onlyUsageInputsScheduleARecompute() {
        Queue<Runnable> deliveries = new ArrayDeque<>();
        ModelChangeService changes = new ModelChangeService(deliveries::add);
        new JobUsageService(configuration, changes);

        changes.modelChanged(new PickOffsetCorrection.Nudging(), "enabled"); //$NON-NLS-1$
        changes.modelChanged(feeder, "name"); //$NON-NLS-1$
        assertTrue(deliveries.isEmpty());

        changes.modelChanged(feeder, "enabled"); //$NON-NLS-1$
        assertEquals(1, deliveries.size());
    }
}
