package org.openpnp.mcp.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.openpnp.model.Configuration;

/** Camera frames saved in the configuration directory's snapshots folder, as CameraView saves them. */
final class Snapshots {
    static final String PNG_SUFFIX = ".png";
    private static final String FOLDER = "snapshots";

    private Snapshots() {
    }

    static File directory(Configuration configuration) {
        return new File(configuration.getConfigurationDirectory(), FOLDER);
    }

    /**
     * Resolves a bare file name in the snapshots folder. Paths are refused so the assistant can't
     * read or write anywhere else.
     */
    static File resolve(Configuration configuration, String name) {
        if (name.isBlank() || name.startsWith(".") || !name.equals(new File(name).getName())) {
            throw new IllegalArgumentException(
                    "Name a file in the " + FOLDER + " folder, without a path: " + name);
        }
        return new File(directory(configuration), name);
    }

    /** Writes a new PNG; an existing file is never overwritten. */
    static File write(Configuration configuration, String name, BufferedImage image)
            throws IOException {
        if (!name.toLowerCase(Locale.ROOT).endsWith(PNG_SUFFIX)) {
            throw new IllegalArgumentException(name + " must end in " + PNG_SUFFIX);
        }
        File file = resolve(configuration, name);
        if (file.exists()) {
            throw new IllegalArgumentException(name + " already exists");
        }
        File directory = file.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Can't create " + directory);
        }
        if (!ImageIO.write(image, "png", file)) {
            throw new IOException("No PNG writer for this image");
        }
        return file;
    }

    static BufferedImage read(Configuration configuration, String name) throws IOException {
        File file = resolve(configuration, name);
        if (!file.isFile()) {
            throw new IllegalArgumentException("No snapshot " + name);
        }
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IllegalArgumentException(name + " is not an image");
        }
        return image;
    }
}
