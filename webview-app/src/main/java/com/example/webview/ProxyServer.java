package com.example.webview;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A standalone HTTP proxy built on a raw {@link ServerSocket}.
 *
 * This is used to work around JavaFX WebView's inability to connect to
 * certain localhost servers (e.g. pi-web on 127.0.0.1:30141). The proxy
 * rewrites Origin/Referer headers to the target origin and injects a
 * browser-like User-Agent so that server-side origin checks and browser
 * sniffing pass.
 *
 * Streaming responses (SSE/event-stream) are piped incrementally so LLM
 * token streams reach the WebView as they arrive — no buffering, so
 * event-stream connections don't time out.
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

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "proxy-" + System.identityHashCode(r));
        t.setDaemon(true);
        return t;
    });

    private ServerSocket serverSocket;
    private int listenPort;
    private volatile boolean running = false;

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
        serverSocket = new ServerSocket(listenPort);
        serverSocket.setReuseAddress(true);
        listenPort = serverSocket.getLocalPort();
        running = true;
        executor.execute(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[proxy] accepted connection from " + clientSocket.getInetAddress());
                executor.execute(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running) {
                    System.err.println("Proxy accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        String method = "UNKNOWN";
        String path = "/";
        System.out.println("[proxy] handleClient started");
        try (clientSocket) {
            clientSocket.setSoTimeout(300000);
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            // Read the HTTP request
            byte[] reqBuf = readRequest(clientIn);
            String reqStr = new String(reqBuf, "UTF-8");

            // Parse request line
            String[] firstLine = reqStr.split("\r?\n", 2)[0].split(" ", 3);
            method = firstLine[0];
            path = firstLine[1];

            // Build target URL
            String targetUrl = targetOrigin + path;

            // Parse headers
            Map<String, String> headers = parseHeaders(reqStr);

            // Read body if present
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

            // Forward to target
            URL url = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(300000);
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

            // Forward headers
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
                    } else if (lower.equals("referer")) {
                        conn.setRequestProperty("Referer", targetOrigin + "/");
                        forwardedReferer = true;
                    } else {
                        conn.addRequestProperty(name, v);
                        if (lower.equals("user-agent")) forwardedUa = true;
                    }
                }
            }
            if (!forwardedUa) {
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            }
            if (!forwardedOrigin) {
                conn.setRequestProperty("Origin", targetOrigin);
            }
            if (!forwardedReferer) {
                conn.setRequestProperty("Referer", targetOrigin + "/");
            }

            conn.connect();
            int statusCode = conn.getResponseCode();
            System.out.printf("[proxy] %s %s -> %d%n", method, path, statusCode);
            System.out.printf("[proxy]   request headers: %s%n", headers);

            // Read response headers
            StringBuilder respHeaders = new StringBuilder();
            respHeaders.append("HTTP/1.1 ").append(statusCode).append(" ").append(conn.getResponseMessage()).append("\r\n");
            for (int i = 0; ; i++) {
                String name = conn.getHeaderFieldKey(i);
                String value = conn.getHeaderField(i);
                if (name == null && value == null) break;
                if (name == null) continue;
                if (!isRestrictedResponseHeader(name)) {
                    respHeaders.append(name).append(": ").append(value).append("\r\n");
                }
            }

            // Get input stream
            InputStream targetIn;
            try {
                targetIn = conn.getInputStream();
            } catch (IOException e) {
                targetIn = conn.getErrorStream();
                if (targetIn == null) {
                    String resp = respHeaders.toString() + "Content-Length: 0\r\n\r\n";
                    clientOut.write(resp.getBytes("UTF-8"));
                    clientOut.flush();
                    return;
                }
            }

            // Check if streaming (SSE/event-stream or chunked transfer encoding)
            String contentType = conn.getHeaderField("Content-Type");
            String transferEncoding = conn.getHeaderField("Transfer-Encoding");
            String contentLength = conn.getHeaderField("Content-Length");
            boolean streaming = contentType != null && contentType.contains("text/event-stream")
                || transferEncoding != null && transferEncoding.contains("chunked");

            System.out.printf("[proxy] %s %s response: ct=%s te=%s cl=%s streaming=%b%n",
                method, path, contentType, transferEncoding, contentLength, streaming);

            if (streaming) {
                // Stream incrementally: headers + blank line, then body
                respHeaders.append("\r\n");
                clientOut.write(respHeaders.toString().getBytes("UTF-8"));
                clientOut.flush();
                System.out.printf("[proxy] %s %s sent headers, streaming body...%n", method, path);
                pipe(targetIn, clientOut);
            } else {
                // Buffer response (read with socket timeout)
                byte[] respBody = targetIn.readAllBytes();
                respHeaders.append("Content-Length: ").append(respBody.length).append("\r\n\r\n");
                clientOut.write(respHeaders.toString().getBytes("UTF-8"));
                clientOut.write(respBody);
                clientOut.flush();
                System.out.printf("[proxy] %s %s -> %d%n", method, path, statusCode);
            }
        } catch (Exception e) {
            System.err.printf("[proxy] %s %s -> error: %s%n", method, path, e.getMessage());
        }
    }

    private void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        out.flush();
    }

    private byte[] readRequest(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        int total = 0;
        while (true) {
            int read = in.read(buf, total, buf.length - total);
            if (read < 0) break;
            total += read;
            // Check if we have the full header + body
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
                        // No content-length, request is complete
                        byte[] full = new byte[total];
                        System.arraycopy(buf, 0, full, 0, total);
                        return full;
                    }
                }
                // Buffer full, need to expand
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
        Map<String, String> headers = new java.util.LinkedHashMap<>();
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
