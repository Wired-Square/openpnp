package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.common.io.Files;

public class ConfigurationSerializeJobTest {
    // Diagnostics serialises the open job; it must stay the user's job file, unsaved.
    @Test
    public void serialisingAJobLeavesItsFileAndDirtyState() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp")); //$NON-NLS-1$
        Configuration.get().load();
        Job job = new Job();
        job.addBoardOrPanelLocation(new BoardLocation(new Board()));
        File jobFile = new File(Files.createTempDir(), "mine.job.xml"); //$NON-NLS-1$
        job.setFile(jobFile);
        job.setDirty(true);

        byte[] xml = Configuration.get().serializeJob(job);

        assertSame(jobFile, job.getFile());
        assertTrue(job.isDirty());
        assertTrue(new String(xml, StandardCharsets.UTF_8).contains("<openpnp-job")); //$NON-NLS-1$
    }
}
