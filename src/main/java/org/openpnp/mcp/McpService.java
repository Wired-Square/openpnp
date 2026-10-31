package org.openpnp.mcp;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.openpnp.Main;
import org.openpnp.mcp.tools.AddPipelineStageTool;
import org.openpnp.mcp.tools.ApplyFeederRotationSuggestionTool;
import org.openpnp.mcp.tools.CaptureCameraTool;
import org.openpnp.mcp.tools.CheckJobPicksTool;
import org.openpnp.mcp.tools.DismissFeederRotationSuggestionTool;
import org.openpnp.mcp.tools.FreezePipelineFrameTool;
import org.openpnp.mcp.tools.GetJobTool;
import org.openpnp.mcp.tools.GetPipelineEditorsTool;
import org.openpnp.mcp.tools.GetPipelineTool;
import org.openpnp.mcp.tools.ListFeedersTool;
import org.openpnp.mcp.tools.ListPackagesTool;
import org.openpnp.mcp.tools.ListPartsTool;
import org.openpnp.mcp.tools.MoveCameraToFeederTool;
import org.openpnp.mcp.tools.MovePipelineStageTool;
import org.openpnp.mcp.tools.RemovePipelineStageTool;
import org.openpnp.mcp.tools.ResolvePartSizesTool;
import org.openpnp.mcp.tools.RunPipelineTool;
import org.openpnp.mcp.tools.SaveCameraFrameTool;
import org.openpnp.mcp.tools.SetFeederLocationTool;
import org.openpnp.mcp.tools.SetFeederNoteTool;
import org.openpnp.mcp.tools.SetPackageBodySizeTool;
import org.openpnp.mcp.tools.SetPackagePickOffsetTool;
import org.openpnp.mcp.tools.SetPipelineStagePropertyTool;
import org.openpnp.mcp.tools.SetPhotonSlotRotationTool;
import org.openpnp.mcp.tools.SuggestFeederRotationTool;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.spi.Machine;

import com.google.gson.JsonObject;

/**
 * Owns the MCP server for the running application and restarts it when its settings change.
 * Confined to the EDT.
 */
public final class McpService {
    private static final long TOOL_TIMEOUT_SECONDS = 60;

    private static McpService instance;

    private final McpSettings settings;
    private final List<McpTool> tools;
    private McpServer server;

    private McpService(McpSettings settings, List<McpTool> tools) {
        this.settings = settings;
        this.tools = tools;
    }

    public static void initialise(Supplier<Job> job, BooleanSupplier jobActive,
            MotionConfirmer confirmer) {
        if (instance != null) {
            throw new IllegalStateException("McpService already initialised");
        }
        Supplier<Configuration> configuration = Configuration::get;
        Supplier<Machine> machine = () -> configuration.get().getMachine();
        McpSettings settings = McpSettings.forUser();
        instance = new McpService(settings, List.of(
                new ListFeedersTool(machine),
                new ListPartsTool(configuration),
                new ListPackagesTool(configuration),
                new GetJobTool(job),
                new SuggestFeederRotationTool(machine,
                        () -> settings.isApplySuggestionsDirectly() && settings.isAllowWrite()),
                new DismissFeederRotationSuggestionTool(machine),
                new ApplyFeederRotationSuggestionTool(machine),
                new SetPhotonSlotRotationTool(machine),
                new SetFeederNoteTool(machine),
                new GetPipelineTool(configuration),
                new RunPipelineTool(configuration),
                new CaptureCameraTool(machine),
                new SaveCameraFrameTool(configuration),
                new FreezePipelineFrameTool(configuration),
                new GetPipelineEditorsTool(configuration),
                new SetPipelineStagePropertyTool(configuration),
                new AddPipelineStageTool(configuration),
                new RemovePipelineStageTool(configuration),
                new MovePipelineStageTool(configuration),
                new SetFeederLocationTool(machine),
                new SetPackagePickOffsetTool(configuration),
                new ResolvePartSizesTool(configuration, job),
                new SetPackageBodySizeTool(configuration),
                new MoveCameraToFeederTool(machine, confirmer),
                new CheckJobPicksTool(configuration, job, jobActive, confirmer)));
    }

    public static McpService get() {
        if (instance == null) {
            throw new IllegalStateException("McpService not initialised");
        }
        return instance;
    }

    public McpSettings getSettings() {
        return settings;
    }

    /** Stops any running server and starts a new one if the settings enable it. */
    public void applySettings() throws IOException {
        stop();
        if (!settings.isEnabled()) {
            return;
        }
        McpDispatcher dispatcher = new McpDispatcher(tools, settings::isTierEnabled,
                McpService::invokeOnEdt, Main.getVersion());
        McpServer newServer = new McpServer(dispatcher, settings.getToken());
        newServer.start(settings.getPort());
        server = newServer;
    }

    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    public String getUrl() {
        return String.format("http://127.0.0.1:%d%s", settings.getPort(), McpServer.PATH);
    }

    public String getClaudeCodeCommand() {
        return String.format(
                "claude mcp add --transport http openpnp %s --header \"Authorization: Bearer %s\"",
                getUrl(), settings.getToken());
    }

    static McpToolResult invokeOnEdt(McpTool tool, JsonObject arguments) throws Exception {
        if (!tool.runsOnEdt() || SwingUtilities.isEventDispatchThread()) {
            return tool.call(arguments);
        }
        FutureTask<McpToolResult> task = new FutureTask<>(() -> tool.call(arguments));
        SwingUtilities.invokeLater(task);
        try {
            return task.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            // Cancelling only helps while the task is still queued; the EDT cannot be interrupted.
            boolean cancelled = task.cancel(false);
            return McpToolResult.error(String.format(
                    cancelled
                            ? "%s did not start within %d s because OpenPnP is busy; it was not run"
                            : "%s did not finish within %d s; it is still running and may yet "
                                    + "complete",
                    tool.getName(), TOOL_TIMEOUT_SECONDS));
        }
        catch (InterruptedException e) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw e;
        }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }
}
