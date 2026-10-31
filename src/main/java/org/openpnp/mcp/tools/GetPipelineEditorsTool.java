package org.openpnp.mcp.tools;

import java.awt.Dialog;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.AbstractVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.PartSettingsHolder;
import org.openpnp.spi.Feeder;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.PipelineHolder;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class GetPipelineEditorsTool implements McpTool {
    private final Supplier<Configuration> configuration;

    public GetPipelineEditorsTool(Supplier<Configuration> configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return "get_pipeline_editors";
    }

    @Override
    public String getDescription() {
        return "Lists the pipeline editors open in OpenPnP: the window title, whose pipeline it "
                + "is (vision settings with where they are used, or a feeder), whether the image "
                + "is frozen, the size of the last captured frame, and the selected stage. Check "
                + "this before tuning, so changes go to the pipeline the operator is looking at "
                + "and not to settings other parts share.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema().build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        JsonArray editors = new JsonArray();
        for (CvPipelineEditor editor : CvPipelineEditor.getShownEditors()) {
            CvPipeline pipeline = editor.getPipeline();
            JsonObject json = new JsonObject();
            Window window = SwingUtilities.getWindowAncestor(editor);
            if (window instanceof Dialog) {
                json.addProperty("title", ((Dialog) window).getTitle());
            }
            json.add("owner", describeOwner(pipeline));
            json.addProperty("frozen", editor.isFrozen());
            BufferedImage frame = pipeline.getLastCapturedImage();
            if (frame != null) {
                json.addProperty("frameWidthPx", frame.getWidth());
                json.addProperty("frameHeightPx", frame.getHeight());
            }
            CvStage selected = editor.getSelectedStage();
            json.addProperty("selectedStage", selected != null ? selected.getName() : null);
            editors.add(json);
        }
        JsonObject result = new JsonObject();
        result.add("editors", editors);
        return McpToolResult.json(result);
    }

    private JsonObject describeOwner(CvPipeline pipeline) {
        Configuration configuration = this.configuration.get();
        JsonObject json = new JsonObject();
        for (AbstractVisionSettings settings : configuration.getVisionSettings()) {
            if (settings.getPipeline() != pipeline) {
                continue;
            }
            json.addProperty("kind", "visionSettings");
            json.addProperty("visionSettingsId", settings.getId());
            json.addProperty("name", settings.getName());
            json.addProperty("type", settings.getClass().getSimpleName());
            JsonArray usedIn = new JsonArray();
            for (PartSettingsHolder holder : settings.getUsedBottomVisionIn()) {
                usedIn.add(holder.getClass().getSimpleName() + " " + holder.getId());
            }
            for (PartSettingsHolder holder : settings.getUsedFiducialVisionIn()) {
                usedIn.add(holder.getClass().getSimpleName() + " " + holder.getId());
            }
            json.add("usedIn", usedIn);
            return json;
        }
        for (Feeder feeder : configuration.getMachine().getFeeders()) {
            if (feeder instanceof PipelineHolder
                    && ((PipelineHolder) feeder).getPipeline() == pipeline) {
                json.addProperty("kind", "feeder");
                json.addProperty("feederId", feeder.getId());
                json.addProperty("name", feeder.getName());
                return json;
            }
        }
        json.addProperty("kind", "unknown");
        return json;
    }
}
