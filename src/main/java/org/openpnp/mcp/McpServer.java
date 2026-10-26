package org.openpnp.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.pmw.tinylog.Logger;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The MCP Streamable HTTP transport, answering every request with plain JSON (no SSE stream).
 * Bound to the loopback interface and guarded by a bearer token, with browser origins other
 * than localhost, and Host headers naming another host, rejected against DNS rebinding.
 */
public final class McpServer {
    public static final String PATH = "/mcp";

    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    /** Room for a move waiting on the operator plus ordinary requests. */
    private static final int HANDLER_THREADS = 4;
    private static final int STOP_DELAY_SECONDS = 0;
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    /** IPv4 explicitly: the advertised URL is 127.0.0.1 even when Java prefers IPv6. */
    private static final byte[] LOOPBACK_IPV4 = {127, 0, 0, 1};

    private final McpDispatcher dispatcher;
    private final byte[] expectedAuthorization;
    private HttpServer httpServer;
    private ExecutorService executor;

    public McpServer(McpDispatcher dispatcher, String token) {
        this.dispatcher = dispatcher;
        this.expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param port the port to listen on, or 0 for any free port.
     */
    public synchronized void start(int port) throws IOException {
        if (httpServer != null) {
            throw new IllegalStateException("MCP server already started");
        }
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(LOOPBACK_IPV4), port), 0);
        server.createContext(PATH, this::handle);
        executor = Executors.newFixedThreadPool(HANDLER_THREADS,
                Thread.ofPlatform().name("MCP-", 0).daemon().factory());
        server.setExecutor(executor);
        server.start();
        httpServer = server;
        Logger.info("MCP server listening on http://{}:{}{}",
                server.getAddress().getHostString(), getPort(), PATH);
    }

    public synchronized void stop() {
        if (httpServer == null) {
            return;
        }
        httpServer.stop(STOP_DELAY_SECONDS);
        executor.shutdownNow();
        httpServer = null;
        executor = null;
        Logger.info("MCP server stopped");
    }

    public synchronized boolean isRunning() {
        return httpServer != null;
    }

    public synchronized int getPort() {
        return httpServer == null ? -1 : httpServer.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!isLocalHost(exchange.getRequestHeaders().getFirst("Host"))
                    || !isLocalOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
                send(exchange, 403, null);
                return;
            }
            if (!isAuthorised(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                send(exchange, 401, null);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Allow", "POST");
                send(exchange, 405, null);
                return;
            }
            if (exceedsLimit(exchange.getRequestHeaders().getFirst("Content-Length"))) {
                send(exchange, 413, null);
                return;
            }
            Optional<String> body = readBody(exchange.getRequestBody());
            if (body.isEmpty()) {
                send(exchange, 413, null);
                return;
            }
            Optional<JsonObject> response = dispatcher.handle(body.get());
            if (response.isEmpty()) {
                send(exchange, 202, null);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            send(exchange, 200, response.get().toString());
        }
        catch (IOException | RuntimeException e) {
            Logger.warn(e, "MCP request failed");
            throw e;
        }
    }

    private boolean isAuthorised(String authorization) {
        return authorization != null && MessageDigest.isEqual(expectedAuthorization,
                authorization.getBytes(StandardCharsets.UTF_8));
    }

    /** A rebound DNS name still names the attacker's host here, though it resolves to us. */
    static boolean isLocalHost(String host) {
        if (host == null) {
            return false;
        }
        try {
            String name = new URI("http://" + host).getHost();
            return name != null && LOCAL_HOSTS.contains(name.toLowerCase());
        }
        catch (URISyntaxException e) {
            return false;
        }
    }

    static boolean isLocalOrigin(String origin) {
        if (origin == null) {
            return true;
        }
        try {
            String host = new URI(origin).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase());
        }
        catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean exceedsLimit(String contentLength) {
        if (contentLength == null) {
            return false;
        }
        try {
            return Long.parseLong(contentLength.trim()) > MAX_REQUEST_BYTES;
        }
        catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * @return the body, or empty when it is larger than {@link #MAX_REQUEST_BYTES}.
     */
    private static Optional<String> readBody(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) {
            return Optional.empty();
        }
        return Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
