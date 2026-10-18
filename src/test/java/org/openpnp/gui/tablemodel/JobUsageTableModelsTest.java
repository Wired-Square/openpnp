package org.openpnp.gui.tablemodel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.JobUsageService.Usage;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Feeder;

import com.google.common.io.Files;

public class JobUsageTableModelsTest {
    private static final String CONFIG = "config/BasicJobTest/"; //$NON-NLS-1$

    private Configuration configuration;
    private Part part;
    private Feeder feeder;
    private Job job;

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
        part = configuration.getPart("R-0805-10K"); //$NON-NLS-1$
        feeder = configuration.getMachine().getFeeder("F1"); //$NON-NLS-1$

        Board board = new Board();
        Placement placement = new Placement("R1"); //$NON-NLS-1$
        placement.setPart(part);
        placement.setSide(Side.Top);
        board.addPlacement(placement);
        BoardLocation boardLocation = new BoardLocation(board);
        boardLocation.setGlobalSide(Side.Top);
        job = new Job();
        job.addBoardOrPanelLocation(boardLocation);
    }

    private static AtomicInteger countUpdates(AbstractTableModel model) {
        AtomicInteger updates = new AtomicInteger();
        model.addTableModelListener(e -> updates.incrementAndGet());
        return updates;
    }

    private void loadJobAndFlushEdt() throws Exception {
        configuration.getJobUsageService().setJob(job);
        SwingUtilities.invokeAndWait(() -> {});
    }

    @Test
    public void feederRowsShowTheirUsageAndRefreshWhenItChanges() throws Exception {
        FeedersTableModel model = new FeedersTableModel(configuration);
        model.refresh();
        int row = model.indexOf(feeder);
        assertSame(Usage.NOT_IN_USE, model.getValueAt(row, FeedersTableModel.USAGE_COLUMN));
        AtomicInteger updates = countUpdates(model);

        loadJobAndFlushEdt();

        assertTrue(updates.get() > 0);
        assertTrue(((Usage) model.getValueAt(row, FeedersTableModel.USAGE_COLUMN)).isInUse());
    }

    @Test
    public void partRowsShowTheirUsageAndRefreshWhenItChanges() throws Exception {
        PartsTableModel model = new PartsTableModel(configuration.getJobUsageService());
        int row = model.indexOf(part);
        AtomicInteger updates = countUpdates(model);

        loadJobAndFlushEdt();

        assertTrue(updates.get() > 0);
        Usage usage = (Usage) model.getValueAt(row, PartsTableModel.USAGE_COLUMN);
        assertTrue(usage.isInUse());
        assertTrue(usage.getIssues().isEmpty());
    }

    @Test
    public void theUsageColumnIsNotEditable() {
        PartsTableModel parts = new PartsTableModel(configuration.getJobUsageService());
        FeedersTableModel feeders = new FeedersTableModel(configuration);
        feeders.refresh();

        assertFalse(parts.isCellEditable(0, PartsTableModel.USAGE_COLUMN));
        assertFalse(feeders.isCellEditable(0, FeedersTableModel.USAGE_COLUMN));
    }
}
