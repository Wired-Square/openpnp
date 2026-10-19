package org.openpnp.util;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

/**
 * The files, text and images of a diagnostics report, written to one zip that the user can attach
 * to an issue. Nothing is sent anywhere.
 */
public final class DiagnosticsBundle {
    // Exactly one of file, bytes and image is set.
    private static final class Entry {
        final String name;
        final File file;
        final byte[] bytes;
        final BufferedImage image;

        Entry(String name, File file, byte[] bytes, BufferedImage image) {
            this.name = name;
            this.file = file;
            this.bytes = bytes;
            this.image = image;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> names = new HashSet<>();

    /** Files that do not exist, such as a log not written yet, are left out. */
    public void addFile(String name, File file) {
        if (file != null && file.isFile()) {
            entries.add(new Entry(uniqueName(name), file, null, null));
        }
    }

    public void addText(String name, String text) {
        addBytes(name, text.getBytes(StandardCharsets.UTF_8));
    }

    public void addBytes(String name, byte[] bytes) {
        entries.add(new Entry(uniqueName(name), null, bytes, null));
    }

    /** Encoded when the zip is written, so a large capture is not encoded on the caller's thread. */
    public void addPng(String name, BufferedImage image) {
        entries.add(new Entry(uniqueName(name), null, null, image));
    }

    // A zip cannot hold two entries with one name, e.g. boards from different folders.
    private String uniqueName(String name) {
        int folderEnd = name.lastIndexOf('/') + 1;
        String unique = name;
        for (int copy = 2; !names.add(unique); copy++) {
            unique = name.substring(0, folderEnd) + copy + "-" + name.substring(folderEnd); //$NON-NLS-1$
        }
        return unique;
    }

    public int size() {
        return entries.size();
    }

    /**
     * Writes the zip, reporting the number of entries written after each one. An interrupt stops
     * the write and deletes the partial zip.
     */
    public void write(File zip, IntConsumer progress) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            int written = 0;
            for (Entry entry : entries) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Diagnostics cancelled"); //$NON-NLS-1$
                }
                out.putNextEntry(new ZipEntry(entry.name));
                writeContent(entry, out);
                out.closeEntry();
                progress.accept(++written);
            }
        }
        catch (IOException | RuntimeException e) {
            Files.deleteIfExists(zip.toPath());
            throw e;
        }
    }

    private static void writeContent(Entry entry, OutputStream out) throws IOException {
        if (entry.file != null) {
            Files.copy(entry.file.toPath(), out);
        }
        else if (entry.image != null) {
            ImageIO.write(entry.image, "PNG", out); //$NON-NLS-1$
        }
        else {
            out.write(entry.bytes);
        }
    }
}
