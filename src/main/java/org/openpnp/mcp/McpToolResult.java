package org.openpnp.mcp;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class McpToolResult {
    /**
     * Images are scaled down to this longest side: enough to judge a threshold or a contour, and
     * it keeps a result with several stage images to a reasonable size.
     */
    static final int MAX_IMAGE_SIDE = 1024;

    private final JsonObject structuredContent;
    private final String text;
    private final boolean error;
    private final List<JsonObject> images = new ArrayList<>();

    private McpToolResult(JsonObject structuredContent, String text, boolean error) {
        this.structuredContent = structuredContent;
        this.text = text;
        this.error = error;
    }

    public static McpToolResult json(JsonObject structuredContent) {
        return new McpToolResult(structuredContent, structuredContent.toString(), false);
    }

    public static McpToolResult error(String message) {
        return new McpToolResult(null, message, true);
    }

    /**
     * Adds an image after the JSON, preceded by its caption.
     */
    public McpToolResult withImage(String caption, BufferedImage image) {
        JsonObject captionContent = new JsonObject();
        captionContent.addProperty("type", "text");
        captionContent.addProperty("text", caption);
        images.add(captionContent);

        JsonObject imageContent = new JsonObject();
        imageContent.addProperty("type", "image");
        imageContent.addProperty("data", encodePng(scaleDown(image)));
        imageContent.addProperty("mimeType", "image/png");
        images.add(imageContent);
        return this;
    }

    public boolean isError() {
        return error;
    }

    public JsonObject getStructuredContent() {
        return structuredContent;
    }

    public JsonObject toJson() {
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(textContent);
        for (JsonObject image : images) {
            content.add(image);
        }

        JsonObject result = new JsonObject();
        result.add("content", content);
        if (structuredContent != null) {
            result.add("structuredContent", structuredContent);
        }
        result.addProperty("isError", error);
        return result;
    }

    static BufferedImage scaleDown(BufferedImage image) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest <= MAX_IMAGE_SIDE) {
            return image;
        }
        double scale = (double) MAX_IMAGE_SIDE / longest;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        }
        finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static String encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            return Base64.getEncoder().encodeToString(png.toByteArray());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
