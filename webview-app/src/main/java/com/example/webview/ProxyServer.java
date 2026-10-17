package com.example.webview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A standalone HTTP proxy built on {@link java.net.http.HttpClient}.
 *
 * This is used to work around JavaFX WebView's inability to connect to
 * certain localhost servers (e.g. pi-web on 127.0.0.1:30141). The proxy
 * rewrites Origin/Referer headers to the target origin and injects a
 * browser-like User-Agent so that server-side origin checks and browser
 * sniffing pass.
 *
 * Responses are buffered in memory (suitable for HTML/JSON/CSS/JS and
 * finite LLM responses). The proxy uses HttpClient.sendAsync() with .get()
 * to avoid blocking the HttpServer's request thread while keeping the
 * exchange open for the entire request/response cycle.
 *
 * Usage:
 *   ProxyServer proxy = new ProxyServer("127.0.0.1", 30141, 0); // port 0 = random
 *   proxy.start();
 *   int port = proxy.getListenPort();
 *   // Connect WebView to http://127.0.0.1:port instead of target directly
 *   proxy.stop();
 */
public class ProxyServer {

    private final String targetHost;
    private final int targetPort;
    private final String targetOrigin;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build();

    private HttpServer server;
    private int listenPort;

    /**
     * Create a proxy that forwards to the given target.
     *
     * @param targetHost target host (e.g. "127.0.0.1")
     * @param targetPort target port (e.g. 30141)
     * @param listenPort local port to bind (0 = random available port)
     */
    public ProxyServer(String targetHost, int targetPort, int listenPort) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.listenPort = listenPort;
        this.targetOrigin = "http://" + targetHost + ":" + targetPort;
    }

    /** Start the proxy server. */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", listenPort), 0);
        server.createContext("/", new ProxyHandler());
        server.setExecutor(null);
        server.start();
        this.listenPort = server.getAddress().getPort();
    }

    /** Stop the proxy server. */
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Returns the local port the proxy is listening on. */
    public int getListenPort() {
        return listenPort;
    }

    /** Returns the full target origin URL (e.g. "http://127.0.0.1:30141"). */
    public String getTargetOrigin() {
        return targetOrigin;
    }

    /**
     * Proxy handler: forwards every request to the target host:port and
     * sends the response back. Responses are buffered in memory.
     */
    private class ProxyHandler implements HttpHandler {

        private static final AtomicInteger COUNTER = new AtomicInteger();

        private static final Set<String> RESTRICTED_REQUEST_HEADERS = Set.of(
            "host", "connection", "upgrade", "transfer-encoding",
            "content-length", "expect", "keep-alive",
            "proxy-authorization", "proxy-connection",
            "sec-websocket-key", "sec-websocket-extensions",
            "sec-websocket-accept", "sec-websocket-protocol",
            "sec-websocket-version"
        );

        private static final Set<String> RESTRICTED_RESPONSE_HEADERS = Set.of(
            "connection", "transfer-encoding"
        );

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String reqUri = exchange.getRequestURI().toString();
            String targetUrl = targetOrigin + reqUri;
            String method = exchange.getRequestMethod();
            int id = COUNTER.incrementAndGet();

            try {
                // Read request body synchronously for methods that can have one
                byte[] body = new byte[0];
                if (method.equals("POST") || method.equals("PUT") ||
                    method.equals("PATCH") || method.equals("DELETE")) {
                    try (InputStream is = exchange.getRequestBody()) {
                        body = is.readAllBytes();
                    }
                }

                // Build the outgoing request
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(java.time.Duration.ofSeconds(300));

                switch (method) {
                    case "POST" -> reqBuilder.method("POST",
                        body.length > 0 ? HttpRequest.BodyPublishers.ofByteArray(body)
                            : HttpRequest.BodyPublishers.noBody());
                    case "PUT" -> reqBuilder.method("PUT",
                        body.length > 0 ? HttpRequest.BodyPublishers.ofByteArray(body)
                            : HttpRequest.BodyPublishers.noBody());
                    case "DELETE" -> reqBuilder.method("DELETE",
                        body.length > 0 ? HttpRequest.BodyPublishers.ofByteArray(body)
                            : HttpRequest.BodyPublishers.noBody());
                    case "PATCH" -> reqBuilder.method("PATCH",
                        body.length > 0 ? HttpRequest.BodyPublishers.ofByteArray(body)
                            : HttpRequest.BodyPublishers.noBody());
                    case "HEAD" -> reqBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                    default -> reqBuilder.GET();
                }

                // Forward relevant headers (skip restricted ones)
                boolean[] forwardedUa = { false };
                boolean[] forwardedOrigin = { false };
                boolean[] forwardedReferer = { false };
                exchange.getRequestHeaders().forEach((name, values) -> {
                    String lower = name.toLowerCase();
                    if (!RESTRICTED_REQUEST_HEADERS.contains(lower)) {
                        for (String v : values) {
                            if (lower.equals("origin")) {
                                reqBuilder.header("Origin", targetOrigin);
                                forwardedOrigin[0] = true;
                            } else if (lower.equals("referer")) {
                                reqBuilder.header("Referer", targetOrigin + "/");
                                forwardedReferer[0] = true;
                            } else {
                                reqBuilder.header(name, v);
                                if (lower.equals("user-agent")) forwardedUa[0] = true;
                            }
                        }
                    }
                });
                if (!forwardedUa[0]) {
                    reqBuilder.header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                }
                if (!forwardedOrigin[0]) {
                    reqBuilder.header("Origin", targetOrigin);
                }
                if (!forwardedReferer[0]) {
                    reqBuilder.header("Referer", targetOrigin + "/");
                }

                HttpRequest request = reqBuilder.build();

                // Send async and BLOCK until response is ready, so the exchange stays open
                HttpResponse<byte[]> response = httpClient.sendAsync(request,
                    HttpResponse.BodyHandlers.ofByteArray()).get();

                // Forward response status and headers
                var respHeaders = exchange.getResponseHeaders();
                response.headers().map().forEach((name, values) -> {
                    if (!RESTRICTED_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                        for (String v : values) {
                            respHeaders.add(name, v);
                        }
                    }
                });

                byte[] respBody = response.body();
                exchange.sendResponseHeaders(response.statusCode(), respBody.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBody);
                }
                System.out.printf("[proxy %d] %s %s -> %d%n", id,
                    method, reqUri, response.statusCode());
                // Log response headers for debugging streaming endpoints
                if (reqUri.contains("events") || reqUri.contains("stream")) {
                    System.out.printf("[proxy %d] response headers: %s%n", id,
                        response.headers());
                }
            } catch (Exception e) {
                System.err.printf("[proxy %d] %s %s -> error: %s%n", id,
                    method, reqUri, e.getMessage());
                try {
                    exchange.sendResponseHeaders(502, 0);
                    exchange.getResponseBody().close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
