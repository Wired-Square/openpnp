package org.openpnp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class McpServerTest {
    private static final String TOKEN = "secret";
    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}";

    private final HttpClient client = HttpClient.newHttpClient();
    private McpServer server;
    private URI uri;

    @BeforeEach
    public void setUp() throws Exception {
        McpDispatcher dispatcher =
                new McpDispatcher(List.of(), tier -> true, McpTool::call, "test");
        server = new McpServer(dispatcher, TOKEN);
        server.start(0);
        uri = URI.create("http://127.0.0.1:" + server.getPort() + McpServer.PATH);
    }

    @AfterEach
    public void tearDown() {
        server.stop();
    }

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void anAuthorisedRequestGetsAJsonResponse() throws Exception {
        HttpResponse<String> response =
                send(post(PING).header("Authorization", "Bearer " + TOKEN));

        assertEquals(200, response.statusCode());
        assertEquals("application/json",
                response.headers().firstValue("Content-Type").orElse(null));
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(7, body.get("id").getAsInt());
        assertTrue(body.has("result"));
    }

    @Test
    public void aNotificationIsAccepted() throws Exception {
        HttpResponse<String> response = send(
                post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                        .header("Authorization", "Bearer " + TOKEN));

        assertEquals(202, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    public void aMissingTokenIsUnauthorised() throws Exception {
        HttpResponse<String> response = send(post(PING));

        assertEquals(401, response.statusCode());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    public void aWrongTokenIsUnauthorised() throws Exception {
        assertEquals(401, send(post(PING).header("Authorization", "Bearer wrong")).statusCode());
    }

    @Test
    public void aForeignOriginIsForbidden() throws Exception {
        HttpResponse<String> response = send(post(PING)
                .header("Authorization", "Bearer " + TOKEN)
                .header("Origin", "https://evil.example"));

        assertEquals(403, response.statusCode());
    }

    @Test
    public void getIsNotAllowed() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri).GET()
                .header("Authorization", "Bearer " + TOKEN));

        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElse(null));
    }

    @Test
    public void anOversizedBodyIsRejected() throws Exception {
        String body = "{\"pad\":\"" + "x".repeat(1024 * 1024) + "\"}";

        assertEquals(413, send(post(body).header("Authorization", "Bearer " + TOKEN))
                .statusCode());
    }

    @Test
    public void localHostsAreAccepted() {
        assertTrue(McpServer.isLocalHost("127.0.0.1:8765"));
        assertTrue(McpServer.isLocalHost("localhost"));
        assertTrue(McpServer.isLocalHost("[::1]:8765"));
        assertFalse(McpServer.isLocalHost("rebound.evil.example:8765"));
        assertFalse(McpServer.isLocalHost(null));
    }

    @Test
    public void localOriginsAreAccepted() {
        assertTrue(McpServer.isLocalOrigin(null));
        assertTrue(McpServer.isLocalOrigin("http://localhost:6274"));
        assertTrue(McpServer.isLocalOrigin("http://127.0.0.1"));
        assertTrue(McpServer.isLocalOrigin("http://[::1]:8080"));
        assertFalse(McpServer.isLocalOrigin("http://localhost.evil.example"));
        assertFalse(McpServer.isLocalOrigin("null"));
        assertFalse(McpServer.isLocalOrigin("not a uri"));
    }

    @Test
    public void stopReleasesTheServer() {
        server.stop();

        assertFalse(server.isRunning());
        assertEquals(-1, server.getPort());
    }
}
