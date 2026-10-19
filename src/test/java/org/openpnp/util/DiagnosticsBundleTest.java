package org.openpnp.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

public class DiagnosticsBundleTest {
    private static final int IMAGE_SIZE = 4;

    private final File directory = com.google.common.io.Files.createTempDir();

    private File file(String name, String content) throws Exception {
        File file = new File(directory, name);
        Files.writeString(file.toPath(), content);
        return file;
    }

    private static Map<String, byte[]> read(File zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    @Test
    public void writesFilesTextAndImagesAndSkipsMissingFiles() throws Exception {
        DiagnosticsBundle bundle = new DiagnosticsBundle();
        bundle.addText("description.txt", "It broke"); //$NON-NLS-1$ //$NON-NLS-2$
        bundle.addFile("machine.xml", file("machine.xml", "<machine/>")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        bundle.addFile("OpenPnP.log", new File(directory, "missing.log")); //$NON-NLS-1$ //$NON-NLS-2$
        bundle.addPng("screenshot.png", //$NON-NLS-1$
                new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB));
        File zip = new File(directory, "out.zip"); //$NON-NLS-1$
        List<Integer> progress = new ArrayList<>();

        bundle.write(zip, progress::add);

        Map<String, byte[]> entries = read(zip);
        assertEquals(List.of("description.txt", "machine.xml", "screenshot.png"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                new ArrayList<>(entries.keySet()));
        assertArrayEquals("It broke".getBytes(StandardCharsets.UTF_8), //$NON-NLS-1$
                entries.get("description.txt")); //$NON-NLS-1$
        assertArrayEquals("<machine/>".getBytes(StandardCharsets.UTF_8), //$NON-NLS-1$
                entries.get("machine.xml")); //$NON-NLS-1$
        assertEquals(IMAGE_SIZE, ImageIO.read(new ByteArrayInputStream(
                entries.get("screenshot.png"))).getWidth()); //$NON-NLS-1$
        assertEquals(List.of(1, 2, 3), progress);
    }

    @Test
    public void entriesWithTheSameNameStayInTheirFolder() throws Exception {
        DiagnosticsBundle bundle = new DiagnosticsBundle();
        bundle.addText("job/board.xml", "first"); //$NON-NLS-1$ //$NON-NLS-2$
        bundle.addText("job/board.xml", "second"); //$NON-NLS-1$ //$NON-NLS-2$
        File zip = new File(directory, "dup.zip"); //$NON-NLS-1$

        bundle.write(zip, written -> {});

        assertEquals(List.of("job/board.xml", "job/2-board.xml"), //$NON-NLS-1$ //$NON-NLS-2$
                new ArrayList<>(read(zip).keySet()));
    }

    @Test
    public void cancellingDeletesThePartialZip() throws Exception {
        DiagnosticsBundle bundle = new DiagnosticsBundle();
        bundle.addText("a.txt", "a"); //$NON-NLS-1$ //$NON-NLS-2$
        bundle.addText("b.txt", "b"); //$NON-NLS-1$ //$NON-NLS-2$
        File zip = new File(directory, "cancelled.zip"); //$NON-NLS-1$

        try {
            assertThrows(InterruptedIOException.class,
                    () -> bundle.write(zip, written -> Thread.currentThread().interrupt()));
        }
        finally {
            Thread.interrupted();
        }

        assertFalse(zip.exists());
    }

    @Test
    public void aFailureWhileWritingDeletesThePartialZip() throws Exception {
        DiagnosticsBundle bundle = new DiagnosticsBundle();
        bundle.addText("a.txt", "a"); //$NON-NLS-1$ //$NON-NLS-2$
        File zip = new File(directory, "failed.zip"); //$NON-NLS-1$

        assertThrows(IllegalStateException.class, () -> bundle.write(zip, written -> {
            throw new IllegalStateException("progress failed"); //$NON-NLS-1$
        }));

        assertFalse(zip.exists());
    }

    @Test
    public void reportsHowManyEntriesItHolds() {
        DiagnosticsBundle bundle = new DiagnosticsBundle();
        bundle.addText("a.txt", "a"); //$NON-NLS-1$ //$NON-NLS-2$
        bundle.addFile("none", null); //$NON-NLS-1$

        assertEquals(1, bundle.size());
    }
}
