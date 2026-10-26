package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.spi.Machine;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.PipelineHolder;

import com.google.gson.JsonObject;

public final class SetPipelineStagePropertyTool implements McpTool {
    private final Supplier<Machine> machine;

    public SetPipelineStagePropertyTool(Supplier<Machine> machine) {
        this.machine = machine;
    }

    @Override
    public String getName() {
        return "set_pipeline_stage_property";
    }

    @Override
    public String getDescription() {
        return "Saves one property of a stage in a feeder's vision pipeline, as editing it in "
                + "the pipeline editor would. Try values with run_pipeline overrides first. "
                + "\"enabled\" switches the stage on or off.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .string(FeederLookup.FEEDER_ID, "The feeder id from list_feeders")
                .string("stage", "The stage name from get_pipeline")
                .string("property", "The property name from get_pipeline")
                .any("value", "The new value: a number, boolean or string (enum name)")
                .required(FeederLookup.FEEDER_ID)
                .required("stage")
                .required("property")
                .required("value")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.WRITE;
    }

    @Override
    public McpToolResult call(JsonObject arguments) throws Exception {
        PipelineHolder holder = PipelineSupport.requireHolder(machine.get(), arguments);
        // A feed processes the live pipeline on the machine thread.
        if (machine.get().isBusy()) {
            throw new IllegalArgumentException("The machine is busy; try again when it is idle");
        }
        CvPipeline pipeline = holder.getPipeline();
        PipelineSupport.requireNoMachineActions(pipeline);
        CvStage stage = PipelineSupport.requireStage(pipeline,
                McpJson.requiredString(arguments, "stage"));
        PipelineSupport.setProperty(stage, McpJson.requiredString(arguments, "property"),
                arguments.get("value"));
        JsonObject result = new JsonObject();
        result.add("stages", PipelineSupport.describeStages(pipeline));
        return McpToolResult.json(result);
    }
}
