package org.openpnp.mcp.tools;

import java.awt.image.BufferedImage;
import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.PipelineHolder;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;

import com.google.gson.JsonObject;

public final class FreezePipelineFrameTool implements McpTool {
    private static final String IMAGE_FILE = "imageFile";

    private final Supplier<Configuration> configuration;

    public FreezePipelineFrameTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "freeze_pipeline_frame";
    }

    @Override
    public String getDescription() {
        return "Makes a frame saved in the snapshots folder the pipeline's last captured frame, "
                + "and freezes a pipeline editor open on the pipeline on it, as its Freeze "
                + "button would, so the operator can watch set_pipeline_stage_property changes "
                + "on that frame. Nothing moves; the configuration doesn't change, but later "
                + "lastCapture runs use this frame.";
    }

    @Override
    public JsonObject getInputSchema() {
        return PipelineSupport.targetSchema(new McpSchema())
                .string(IMAGE_FILE, "A file name in the snapshots folder")
                .required(IMAGE_FILE)
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.WRITE;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        Configuration configuration = this.configuration.get();
        PipelineHolder holder = PipelineSupport.requireHolder(configuration, arguments);
        // A feed or an alignment processes the live pipeline on the machine thread.
        if (configuration.getMachine().isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        String imageFile = McpJson.requiredString(arguments, IMAGE_FILE);
        BufferedImage image = Snapshots.read(configuration, imageFile);
        Camera camera = holder instanceof BottomVisionTarget
                ? ((BottomVisionTarget) holder).getCamera()
                : configuration.getMachine().getDefaultHead().getDefaultCamera();
        if (image.getWidth() != camera.getWidth() || image.getHeight() != camera.getHeight()) {
            throw new IllegalArgumentException(String.format(
                    "%s is %d × %d px but camera %s captures %d × %d px", imageFile,
                    image.getWidth(), image.getHeight(), camera.getName(), camera.getWidth(),
                    camera.getHeight()));
        }
        CvPipeline pipeline = holder.getPipeline();
        pipeline.setLastCapturedImage(image);
        JsonObject result = new JsonObject();
        result.addProperty("shownInEditor", CvPipelineEditor.showFrozenFrame(pipeline));
        return McpToolResult.json(result);
    }
}
