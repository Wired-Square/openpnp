package org.openpnp.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

public class McpServiceTest {
    private static McpTool recordingTool(boolean runsOnEdt, AtomicReference<Thread> thread) {
        return new McpTool() {
            @Override
            public String getName() {
                return "record";
            }

            @Override
            public String getDescription() {
                return "Records its thread";
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
            public boolean runsOnEdt() {
                return runsOnEdt;
            }

            @Override
            public McpToolResult call(JsonObject arguments) {
                thread.set(Thread.currentThread());
                return McpToolResult.json(new JsonObject());
            }
        };
    }

    @Test
    public void edtToolsRunOnTheEdt() throws Exception {
        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicReference<Thread> edt = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> edt.set(Thread.currentThread()));

        McpService.invokeOnEdt(recordingTool(true, thread), new JsonObject());

        assertSame(edt.get(), thread.get());
    }

    @Test
    public void otherToolsRunOnTheCallingThread() throws Exception {
        AtomicReference<Thread> thread = new AtomicReference<>();

        McpService.invokeOnEdt(recordingTool(false, thread), new JsonObject());

        assertSame(Thread.currentThread(), thread.get());
        assertFalse(SwingUtilities.isEventDispatchThread());
    }
}
