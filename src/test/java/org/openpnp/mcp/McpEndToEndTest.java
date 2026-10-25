package org.openpnp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.mcp.tools.ListFeedersTool;
import org.openpnp.model.Configuration;

import com.google.common.io.Files;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A tool call over HTTP, through the EDT, against a loaded configuration. */
public class McpEndToEndTest {
    private static final String CONFIG = "config/BasicJobTest/";
    private static final String TOKEN = "secret";

    private McpServer server;

    @BeforeEach
    public void setUp() throws Exception {
        File directory = new File(Files.createTempDir(), ".openpnp");
        for (String file : new String[] {"machine.xml", "packages.xml", "parts.xml"}) {
            FileUtils.copyURLToFile(ClassLoader.getSystemResource(CONFIG + file),
                    new File(directory, file));
        }
        Configuration.initialize(directory);
        Configuration.get().load();

        McpDispatcher dispatcher = new McpDispatcher(
                List.of(new ListFeedersTool(() -> Configuration.get().getMachine())),
                tier -> true, McpService::invokeOnEdt, "test");
        server = new McpServer(dispatcher, TOKEN);
        server.start(0);
    }

    @AfterEach
    public void tearDown() {
        server.stop();
    }

    @Test
    public void listFeedersOverHttp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getPort() + McpServer.PATH))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,"
                        + "\"method\":\"tools/call\",\"params\":{\"name\":\"list_feeders\"}}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject()
                .getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean(), response.body());
        assertEquals(Configuration.get().getMachine().getFeeders().size(),
                result.getAsJsonObject("structuredContent").getAsJsonArray("feeders").size());
    }
}
