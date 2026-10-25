package org.openpnp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.mcp.McpTool.Tier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class McpDispatcherTest {
    private Set<Tier> enabledTiers;
    private McpDispatcher dispatcher;

    @BeforeEach
    public void setUp() {
        enabledTiers = EnumSet.of(Tier.READ, Tier.SUGGEST);
        dispatcher = new McpDispatcher(
                List.of(new EchoTool("echo", Tier.READ), new EchoTool("change", Tier.WRITE)),
                enabledTiers::contains, McpTool::call, "test");
    }

    private JsonObject request(String json) {
        Optional<JsonObject> response = dispatcher.handle(json);
        assertTrue(response.isPresent(), "Expected a response to " + json);
        return response.get();
    }

    private static int errorCode(JsonObject response) {
        return response.getAsJsonObject("error").get("code").getAsInt();
    }

    @Test
    public void initializeEchoesASupportedProtocolVersion() {
        JsonObject result = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\"}}").getAsJsonObject("result");

        assertEquals("2025-03-26", result.get("protocolVersion").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));
        assertEquals("openpnp", result.getAsJsonObject("serverInfo").get("name").getAsString());
    }

    @Test
    public void initializeAnswersAnUnknownProtocolVersionWithTheLatest() {
        JsonObject result = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"1999-01-01\"}}").getAsJsonObject("result");

        assertEquals(McpDispatcher.SUPPORTED_PROTOCOL_VERSIONS.get(0),
                result.get("protocolVersion").getAsString());
    }

    @Test
    public void initializeToleratesANonStringProtocolVersion() {
        JsonObject result = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":20250618}}").getAsJsonObject("result");

        assertEquals(McpDispatcher.SUPPORTED_PROTOCOL_VERSIONS.get(0),
                result.get("protocolVersion").getAsString());
    }

    @Test
    public void clientResponsesGetNoResponse() {
        assertFalse(dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")
                .isPresent());
    }

    @Test
    public void responsesKeepTheRequestId() {
        JsonObject response = request("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"ping\"}");

        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertEquals("abc", response.get("id").getAsString());
        assertEquals(0, response.getAsJsonObject("result").size());
    }

    @Test
    public void notificationsGetNoResponse() {
        assertFalse(dispatcher
                .handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .isPresent());
    }

    @Test
    public void malformedJsonIsAParseError() {
        JsonObject response = request("{not json");

        assertEquals(McpDispatcher.PARSE_ERROR, errorCode(response));
        assertTrue(response.get("id").isJsonNull());
    }

    @Test
    public void batchesAreRejected() {
        assertEquals(McpDispatcher.INVALID_REQUEST,
                errorCode(request("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]")));
    }

    @Test
    public void unknownMethodsAreNotFound() {
        assertEquals(McpDispatcher.METHOD_NOT_FOUND,
                errorCode(request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}")));
    }

    @Test
    public void toolsListDescribesEveryToolWithItsSchema() {
        JsonArray tools = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .getAsJsonObject("result").getAsJsonArray("tools");

        assertEquals(2, tools.size());
        JsonObject echo = tools.get(0).getAsJsonObject();
        assertEquals("echo", echo.get("name").getAsString());
        assertEquals("object", echo.getAsJsonObject("inputSchema").get("type").getAsString());
        assertTrue(echo.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
        assertFalse(tools.get(1).getAsJsonObject().getAsJsonObject("annotations")
                .get("readOnlyHint").getAsBoolean());
    }

    @Test
    public void toolsCallReturnsTextAndStructuredContent() {
        JsonObject result = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"value\":\"hi\"}}}")
                .getAsJsonObject("result");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("hi", result.getAsJsonObject("structuredContent").get("value").getAsString());
        JsonObject text = result.getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("text", text.get("type").getAsString());
        assertEquals("{\"value\":\"hi\"}", text.get("text").getAsString());
    }

    @Test
    public void toolsCallWithoutArgumentsPassesAnEmptyObject() {
        JsonObject result = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\"}}").getAsJsonObject("result");

        assertFalse(result.get("isError").getAsBoolean());
    }

    @Test
    public void unknownToolsAreInvalidParams() {
        assertEquals(McpDispatcher.INVALID_PARAMS,
                errorCode(request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"nope\"}}")));
    }

    @Test
    public void aDisabledTierIsReportedAsAToolError() {
        JsonObject result = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"change\"}}").getAsJsonObject("result");

        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(text(result).contains("WRITE tools are disabled"), text(result));
    }

    @Test
    public void anEnabledTierRuns() {
        enabledTiers.add(Tier.WRITE);

        JsonObject result = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"change\"}}").getAsJsonObject("result");

        assertFalse(result.get("isError").getAsBoolean());
    }

    @Test
    public void invalidArgumentsAreReportedToTheAssistant() {
        JsonObject result = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"value\":3}}}")
                .getAsJsonObject("result");

        assertTrue(result.get("isError").getAsBoolean());
        assertEquals("value must be a string", text(result));
    }

    @Test
    public void toolFailuresAreReportedAsToolErrors() {
        JsonObject result = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"fail\":true}}}")
                .getAsJsonObject("result");

        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(text(result).startsWith("echo failed: java.lang.IllegalStateException"),
                text(result));
    }

    private static String text(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static final class EchoTool implements McpTool {
        private final String name;
        private final Tier tier;

        EchoTool(String name, Tier tier) {
            this.name = name;
            this.tier = tier;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Echoes value";
        }

        @Override
        public JsonObject getInputSchema() {
            return new McpSchema().string("value", "Value to echo").build();
        }

        @Override
        public Tier getTier() {
            return tier;
        }

        @Override
        public McpToolResult call(JsonObject arguments) {
            if (McpJson.optionalBoolean(arguments, "fail", false)) {
                throw new IllegalStateException("boom");
            }
            JsonObject result = new JsonObject();
            String value = McpJson.optionalString(arguments, "value");
            if (value != null) {
                result.addProperty("value", value);
            }
            return McpToolResult.json(result);
        }
    }
}
