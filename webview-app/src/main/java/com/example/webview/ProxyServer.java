package com.example.webview;

import com.example.webview.logging.AppLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A standalone HTTP proxy built on a raw {@link ServerSocket}.
 *
 * Used to work around JavaFX WebView's inability to connect to certain localhost
 * servers (e.g. pi-web on 127.0.0.1:30141). The proxy rewrites Origin/Referer
 * headers to the target origin and injects a browser-like User-Agent so that
 * server-side origin checks and browser sniffing pass.
 *
 * Streaming responses (SSE/event-stream) are piped incrementally so LLM
 * token streams reach the WebView as they arrive — no buffering, so
 * event-stream connections don't time out.
 *
 * Communication logged:
 *   Client -> Proxy   : incoming HTTP request
 *   Proxy   -> Target : forwarded HTTP request
 *   Target  -> Proxy  : HTTP response
 *   Proxy   -> Client : HTTP response forwarded back
 *
 * Usage:
 *   ProxyServer proxy = new ProxyServer("127.0.0.1", 30141, 0);
 *   proxy.start();
 *   int port = proxy.getListenPort();
 *   proxy.stop();
 */
public class ProxyServer {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private final String targetHost;
    private final int targetPort;
    private final String targetOrigin;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "proxy-" + System.identityHashCode(r));
        t.setDaemon(true);
        return t;
    });

    private ServerSocket serverSocket;
    private int listenPort;
    private volatile boolean running = false;

    private final AtomicInteger connectionCounter = new AtomicInteger(0);

    /** Buffer size for piping response body to client. */
    private final int bufferSize;

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
        this.bufferSize = AppConfig.getBufferSize();
        LOG.info("ProxyServer", "Created: target=" + targetOrigin
            + ", listenPort=" + listenPort + ", bufferSize=" + bufferSize);
    }

    /** Start the proxy server. */
    public void start() throws IOException {
        serverSocket = new ServerSocket(listenPort);
        serverSocket.setReuseAddress(true);
        listenPort = serverSocket.getLocalPort();
        running = true;
        executor.execute(this::acceptLoop);
        LOG.info("ProxyServer", "Started on " + AppConfig.getProxyHost() + ":"
            + listenPort + " -> " + targetOrigin);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                int connId = connectionCounter.incrementAndGet();
                LOG.debug("ProxyServer", "Accepted connection #" + connId + " from "
                    + clientSocket.getInetAddress().getHostAddress());
                executor.execute(() -> handleClient(clientSocket, connId));
            } catch (IOException e) {
                if (running) {
                    LOG.error("ProxyServer", "Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket clientSocket, int connId) {
        String clientAddr = clientSocket.getInetAddress().getHostAddress();
        String method = "UNKNOWN";
        String path = "/";
        try (clientSocket) {
            clientSocket.setSoTimeout(300000);
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            // ---- Client -> Proxy: read request ----
            byte[] reqBuf = readRequest(clientIn);
            String reqStr = new String(reqBuf, "UTF-8");

            // Parse request line
            String[] firstLine = reqStr.split("\r?\n", 2)[0].split(" ", 3);
            method = firstLine[0];
            path = firstLine.length > 1 ? firstLine[1] : "/";

            String targetUrl = targetOrigin + path;

            LOG.comm("Client(" + connId + ")", "Proxy", "REQUEST",
                method + " " + path + " from " + clientAddr);

            // Log request headers at TRACE
            Map<String, String> headers = parseHeaders(reqStr);
            LOG.commTrace("Client(" + connId + ")", "Proxy", "REQUEST HEADERS",
                headers.toString());

            // ---- Parse body if present ----
            byte[] body = new byte[0];
            String contentLengthStr = headers.get("content-length");
            if (contentLengthStr != null) {
                int cl = Integer.parseInt(contentLengthStr);
                if (cl > 0) {
                    body = new byte[cl];
                    int totalRead = 0;
                    while (totalRead < cl) {
                        int read = clientIn.read(body, totalRead, cl - totalRead);
                        if (read < 0) break;
                        totalRead += read;
                    }
                }
            }

            // ---- Proxy -> Target: forward request ----
            LOG.comm("Proxy", "Target(" + targetOrigin + ")", "FORWARD",
                method + " " + path + " (body=" + body.length + " bytes)");

            URL url = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(AppConfig.DEFAULT_CONNECT_TIMEOUT);
            conn.setReadTimeout(AppConfig.DEFAULT_SSE_READ_TIMEOUT);
            conn.setUseCaches(false);

            if (method.equals("POST") || method.equals("PUT") ||
                method.equals("PATCH") || method.equals("DELETE")) {
                conn.setDoOutput(true);
                if (body.length > 0) {
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body);
                    }
                }
            }

            // Forward headers with fixes
            boolean forwardedUa = false;
            boolean forwardedOrigin = false;
            boolean forwardedReferer = false;
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name = entry.getKey();
                String lower = name.toLowerCase();
                if (!isRestrictedRequestHeader(lower)) {
                    String v = entry.getValue();
                    if (lower.equals("origin")) {
                        conn.setRequestProperty("Origin", targetOrigin);
                        forwardedOrigin = true;
                        LOG.commTrace("Proxy", "Target", "HEADER REWRITE",
                            "Origin: " + v + " -> " + targetOrigin);
                    } else if (lower.equals("referer")) {
                        conn.setRequestProperty("Referer", targetOrigin + "/");
                        forwardedReferer = true;
                        LOG.commTrace("Proxy", "Target", "HEADER REWRITE",
                            "Referer: " + v + " -> " + targetOrigin + "/");
                    } else {
                        conn.addRequestProperty(name, v);
                        if (lower.equals("user-agent")) forwardedUa = true;
                    }
                }
            }
            if (!forwardedUa) {
                conn.setRequestProperty("User-Agent", AppConfig.getUserAgent());
                LOG.commTrace("Proxy", "Target", "HEADER INJECT",
                    "User-Agent: " + AppConfig.getUserAgent());
            }
            if (!forwardedOrigin) {
                conn.setRequestProperty("Origin", targetOrigin);
                LOG.commTrace("Proxy", "Target", "HEADER INJECT",
                    "Origin: " + targetOrigin);
            }
            if (!forwardedReferer) {
                conn.setRequestProperty("Referer", targetOrigin + "/");
                LOG.commTrace("Proxy", "Target", "HEADER INJECT",
                    "Referer: " + targetOrigin + "/");
            }

            conn.connect();
            int statusCode = conn.getResponseCode();
            LOG.comm("Target(" + targetOrigin + ")", "Proxy", "RESPONSE",
                statusCode + " " + conn.getResponseMessage() + " for " + method + " " + path);

            // ---- Read response headers ----
            StringBuilder respHeaders = new StringBuilder();
            respHeaders.append("HTTP/1.1 ").append(statusCode).append(" ").append(conn.getResponseMessage()).append("\r\n");
            for (int i = 0; ; i++) {
                String name = conn.getHeaderFieldKey(i);
                String value = conn.getHeaderField(i);
                if (name == null && value == null) break;
                if (name == null) continue;
                if (!isRestrictedResponseHeader(name)) {
                    respHeaders.append(name).append(": ").append(value).append("\r\n");
                    LOG.commTrace("Target(" + targetOrigin + ")", "Proxy", "RESPONSE HEADER",
                        name + ": " + value);
                }
            }

            // ---- Get input stream ----
            InputStream targetIn;
            try {
                targetIn = conn.getInputStream();
            } catch (IOException e) {
                targetIn = conn.getErrorStream();
                if (targetIn == null) {
                    String resp = respHeaders.toString() + "Content-Length: 0\r\n\r\n";
                    clientOut.write(resp.getBytes("UTF-8"));
                    clientOut.flush();
                    LOG.warn("ProxyServer", "No error stream for " + method + " " + path);
                    return;
                }
            }

            // Detect streaming (SSE/event-stream or chunked transfer encoding)
            String contentType = conn.getHeaderField("Content-Type");
            String transferEncoding = conn.getHeaderField("Transfer-Encoding");
            String contentLength = conn.getHeaderField("Content-Length");
            boolean streaming = (contentType != null && contentType.contains("text/event-stream"))
                || (transferEncoding != null && transferEncoding.contains("chunked"));

            LOG.debug("ProxyServer", method + " " + path + " response: ct=" + contentType
                + " te=" + transferEncoding + " cl=" + contentLength + " streaming=" + streaming);

            // ---- Proxy -> Client: forward response ----
            if (streaming) {
                respHeaders.append("\r\n");
                clientOut.write(respHeaders.toString().getBytes("UTF-8"));
                clientOut.flush();
                LOG.comm("Proxy", "Client(" + connId + ")", "STREAM HEADERS",
                    "Sent headers for streaming response (" + method + " " + path + ")");
                pipe(targetIn, clientOut, connId, method, path);
                LOG.comm("Proxy", "Client(" + connId + ")", "STREAM END",
                    "Finished streaming " + method + " " + path);
            } else {
                byte[] respBody = targetIn.readAllBytes();
                respHeaders.append("Content-Length: ").append(respBody.length).append("\r\n\r\n");
                clientOut.write(respHeaders.toString().getBytes("UTF-8"));
                clientOut.write(respBody);
                clientOut.flush();
                LOG.comm("Proxy", "Client(" + connId + ")", "RESPONSE",
                    statusCode + " (" + respBody.length + " bytes) for " + method + " " + path);
                LOG.debug("ProxyServer", method + " " + path + " -> " + statusCode
                    + " (" + respBody.length + " bytes)");
            }
        } catch (Exception e) {
            LOG.error("ProxyServer", method + " " + path + " -> error: " + e.getMessage());
        } finally {
            LOG.debug("ProxyServer", "Connection #" + connId + " (" + clientAddr + ") closed");
        }
    }

    private void pipe(InputStream in, OutputStream out, int connId, String method, String path) throws IOException {
        byte[] buf = new byte[bufferSize];
        int read;
        long totalBytes = 0;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
            totalBytes += read;
            LOG.commTrace("Proxy", "Client(" + connId + ")", "STREAM CHUNK",
                read + " bytes for " + method + " " + path + " (total=" + totalBytes + ")");
        }
        out.flush();
    }

    private byte[] readRequest(InputStream in) throws IOException {
        byte[] buf = new byte[bufferSize];
        int total = 0;
        while (true) {
            int read = in.read(buf, total, buf.length - total);
            if (read < 0) break;
            total += read;
            if (total >= 4) {
                String partial = new String(buf, 0, total, "UTF-8");
                int headerEnd = partial.indexOf("\r\n\r\n");
                if (headerEnd >= 0) {
                    String headersPart = partial.substring(0, headerEnd + 4);
                    String clStr = null;
                    for (String line : headersPart.split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            clStr = line.substring("content-length:".length()).trim();
                            break;
                        }
                    }
                    if (clStr != null) {
                        int contentLength = Integer.parseInt(clStr);
                        int bodyStart = headerEnd + 4;
                        int bodyPresent = total - bodyStart;
                        if (bodyPresent >= contentLength) {
                            byte[] full = new byte[total];
                            System.arraycopy(buf, 0, full, 0, total);
                            return full;
                        }
                    } else {
                        byte[] full = new byte[total];
                        System.arraycopy(buf, 0, full, 0, total);
                        return full;
                    }
                }
                if (total == buf.length) {
                    byte[] newBuf = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, newBuf, 0, buf.length);
                    buf = newBuf;
                }
            }
        }
        byte[] full = new byte[total];
        System.arraycopy(buf, 0, full, 0, total);
        return full;
    }

    private Map<String, String> parseHeaders(String reqStr) {
        Map<String, String> headers = new LinkedHashMap<>();
        String[] parts = reqStr.split("\r\n\r\n", 2);
        String headerPart = parts[0];
        for (String line : headerPart.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.put(name.toLowerCase(), value);
            }
        }
        return headers;
    }

    private static boolean isRestrictedRequestHeader(String lower) {
        return Set.of(
            "host", "connection", "upgrade", "transfer-encoding",
            "content-length", "expect", "keep-alive",
            "proxy-authorization", "proxy-connection",
            "sec-websocket-key", "sec-websocket-extensions",
            "sec-websocket-accept", "sec-websocket-protocol",
            "sec-websocket-version"
        ).contains(lower);
    }

    private static boolean isRestrictedResponseHeader(String name) {
        String lower = name.toLowerCase();
        return Set.of(
            "connection", "transfer-encoding", "content-length",
            "content-security-policy", "content-security-policy-report-only"
        ).contains(lower);
    }

    /** Stop the proxy server. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
        LOG.info("ProxyServer", "Stopped (was listening on " + AppConfig.getProxyHost()
            + ":" + listenPort + ")");
    }

    /** Returns the local port the proxy is listening on. */
    public int getListenPort() {
        return listenPort;
    }

    /** Returns the full target origin URL (e.g. "http://127.0.0.1:30141"). */
    public String getTargetOrigin() {
        return targetOrigin;
    }
}
