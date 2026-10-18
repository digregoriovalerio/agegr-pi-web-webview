package com.example.webview;

import com.example.webview.logging.AppLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A standalone HTTP proxy built on a raw {@link ServerSocket}.
 *
 * <h2>Why this class exists</h2>
 * JavaFX WebView cannot connect reliably to localhost servers such as pi-web
 * on {@code 127.0.0.1:30141}. It fails origin checks, sends the wrong
 * User-Agent, and does not handle SSE streaming or chunked transfer encoding
 * correctly. This proxy sits between the WebView page and pi-web:
 * <ul>
 *   <li>Rewrites {@code Origin} and {@code Referer} headers to the target origin</li>
 *   <li>Injects a browser-like {@code User-Agent}</li>
 *   <li>Strips restricted headers (Host, Connection, Sec-*, etc.)</li>
 *   <li>Pipes streaming (SSE/event-stream) responses incrementally</li>
 *   <li>Validates request paths for SSRF protection</li>
 *   <li>Handles both Content-Length and chunked request bodies</li>
 * </ul>
 *
 * <h2>Architecture diagram</h2>
 * <pre>
 *   WebView page --> Proxy --> Target (pi-web)
 *      ^                       |
 *      |                       v
 *   Proxy <-- Target response <--+
 *   Proxy --> WebView page
 * </pre>
 *
 * <h2>Communication edges logged</h2>
 * <ul>
 *   <li>{@code Client -> Proxy} — incoming HTTP request</li>
 *   <li>{@code Proxy -> Target} — forwarded HTTP request</li>
 *   <li>{@code Target -> Proxy} — HTTP response</li>
 *   <li>{@code Proxy -> Client} — response forwarded back to WebView</li>
 * </ul>
 *
 * <h2>Critical bug fixes in this class</h2>
 * <h3>Bug 1: POST body double-read (fixed)</h3>
 * The original code called {@link #readRequest(InputStream)} which already
 * consumed the <b>full</b> request (headers + body) from the client socket.
 * Then {@code handleClient} tried to read {@code Content-Length} more bytes
 * from {@code clientIn} again. Since the client had already sent everything,
 * that second read blocked until the 300-second socket read timeout, and the
 * message POST never reached pi-web.
 * <p>
 * Fix: {@code readRequest()} returns the complete raw request (headers +
 * body). The body is extracted from the already-read byte array via
 * {@link #extractBody(byte[], int, Map)} — never re-read from the socket.
 *
 * <h3>Bug 2: getOutputStream() + explicit connect() = "Already connected"
 * (fixed)</h3>
 * {@code HttpURLConnection.getOutputStream()} implicitly calls
 * {@code connect()}. The original code then called {@code conn.connect()}
 * explicitly afterward, which threw {@code IllegalStateException:
 * Already connected} on every POST/PUT/PATCH/DELETE request.
 * <p>
 * Fix: Removed all explicit {@code conn.connect()} calls. Headers are set
 * first, then {@code setDoOutput(true)} + {@code getOutputStream()} writes
 * the body (implicitly connecting), then {@code getResponseCode()} reads the
 * response.
 *
 * <h3>Bug 3: Chunked request body parsing (fixed)</h3>
 * The original incremental chunk parser was fragile: it tracked offsets into
 * a fixed buffer with complex state transitions that could misparse chunk
 * size lines split across reads.
 * <p>
 * Fix: Replaced with an accumulate-and-dechunk approach
 * ({@link #readChunkedRequest} + {@link #tryDechunk}) that buffers all raw
 * chunked bytes in memory, then parses them as complete ASCII framing lines.
 *
 * <h2>SSRF protection</h2>
 * Request paths must start with {@code /} and must not be absolute-form URIs
 * ({@code scheme://host/...}) or contain path traversal ({@code ../}).
 * Violations return HTTP 400.
 *
 * @see AppConfig
 * @see AppLogger
 * @see ProxiedWebView
 */
public class ProxyServer {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private final String targetOrigin;
    private final String proxyHost;
    private final int bufferSize;
    private final String userAgent;
    private final int connectTimeout;
    private final int sseReadTimeout;
    private final int maxRequestBodySize;

    private final ExecutorService executor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors() * 2,
        r -> {
            Thread t = new Thread(r, "proxy-" + System.identityHashCode(r));
            t.setDaemon(true);
            return t;
        }
    );

    private ServerSocket serverSocket;
    private int listenPort;
    private volatile boolean running = false;

    private final AtomicInteger connectionCounter = new AtomicInteger(0);

    /**
     * Creates a proxy that forwards to the given target origin.
     *
     * @param targetOrigin       full target origin (e.g. {@code http://127.0.0.1:30141})
     * @param listenPort         local port to bind (0 = random available port)
     * @param proxyHost          proxy bind host
     * @param bufferSize         streaming buffer size (bytes)
     * @param userAgent          User-Agent for forwarding to target
     * @param connectTimeout     connection timeout (ms)
     * @param sseReadTimeout     SSE read timeout (ms)
     * @param maxRequestBodySize max request body size (bytes)
     */
    public ProxyServer(
        String targetOrigin,
        int listenPort,
        String proxyHost,
        int bufferSize,
        String userAgent,
        int connectTimeout,
        int sseReadTimeout,
        int maxRequestBodySize
    ) {
        this.targetOrigin = targetOrigin;
        this.proxyHost = proxyHost;
        this.bufferSize = bufferSize;
        this.userAgent = userAgent;
        this.connectTimeout = connectTimeout;
        this.sseReadTimeout = sseReadTimeout;
        this.maxRequestBodySize = maxRequestBodySize;
        this.listenPort = listenPort;
        LOG.info("ProxyServer", "Created: target=" + targetOrigin
            + ", listenPort=" + listenPort + ", bufferSize=" + bufferSize);
    }

    /**
     * Starts the proxy server on a background thread.
     *
     * @throws IOException if the server socket cannot be opened
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(listenPort);
        serverSocket.setReuseAddress(true);
        listenPort = serverSocket.getLocalPort();
        running = true;
        executor.execute(this::acceptLoop);
        LOG.info("ProxyServer", "Started on " + proxyHost + ":"
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

    /**
     * Handles a single client connection end-to-end: reads the request,
     * forwards it to the target origin, reads the response, and sends it back
     * to the client.
     *
     * @param clientSocket the client socket
     * @param connId       connection identifier for logging
     */
    private void handleClient(Socket clientSocket, int connId) {
        String clientAddr = clientSocket.getInetAddress().getHostAddress();
        String method = "UNKNOWN";
        String path = "/";
        try (clientSocket) {
            clientSocket.setSoTimeout(300000);
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            // ---- Client -> Proxy: read request ----
            // NOTE: readRequest() consumes the FULL request (headers AND body)
            // from the socket. The body must be sliced out of reqBuf below —
            // reading it from clientIn again would block until the read
            // timeout because the client has already sent everything.
            byte[] reqBuf = readRequest(clientIn);
            int headerEnd = indexOfHeaderEnd(reqBuf);
            int bodyStart = (headerEnd >= 0) ? headerEnd + 4 : reqBuf.length;
            String reqStr = new String(reqBuf, 0, bodyStart, "UTF-8");

            // Parse request line
            String[] firstLine = reqStr.split("\r?\n", 2)[0].split(" ", 3);
            method = firstLine[0];
            path = firstLine.length > 1 ? firstLine[1] : "/";

            // SSRF protection: validate path
            if (!isValidRequestPath(path)) {
                LOG.warn("ProxyServer", "Invalid request path (SSRF protection): " + path);
                sendErrorResponse(clientOut, 400, "Bad Request: Invalid path");
                return;
            }

            String targetUrl = targetOrigin + path;

            LOG.comm("Client(" + connId + ")", "Proxy", "REQUEST",
                method + " " + path + " from " + clientAddr);

            // Log request headers at TRACE
            Map<String, String> headers = parseHeaders(reqStr);
            LOG.commTrace("Client(" + connId + ")", "Proxy", "REQUEST HEADERS",
                headers.toString());

            // ---- Extract body (already fully read into reqBuf by readRequest) ----
            byte[] body = extractBody(reqBuf, bodyStart, headers);

            // ---- Proxy -> Target: forward request using HttpURLConnection ----
            // getOutputStream() implicitly connects. Write the body, then
            // getResponseCode() reads the response without re-connecting.
            // Do NOT call conn.connect() explicitly.
            URL url = URI.create(targetUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(sseReadTimeout);
            conn.setUseCaches(false);

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
                conn.setRequestProperty("User-Agent", userAgent);
                LOG.commTrace("Proxy", "Target", "HEADER INJECT",
                    "User-Agent: " + userAgent);
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

            // Forward body for methods that support it
            if (method.equals("POST") || method.equals("PUT") ||
                method.equals("PATCH") || method.equals("DELETE")) {
                conn.setDoOutput(true);
                if (body.length > 0) {
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body);
                    }
                }
            }

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
                    // Preserve error status code in response
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
                || (transferEncoding != null && transferEncoding.contains("chunked"))
                || (contentType != null && contentType.contains("application/x-ndjson"))
                || (contentType != null && contentType.contains("application/stream+json"));

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
            e.printStackTrace();
        } finally {
            LOG.debug("ProxyServer", "Connection #" + connId + " (" + clientAddr + ") closed");
        }
    }

    /**
     * Pipes bytes from an input stream to an output stream until EOF,
     * logging each chunk at TRACE level.
     */
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

    /**
     * Reads the complete HTTP request (headers + body) from the client socket.
     * The body is consumed entirely here so it does not need to be re-read
     * later.
     */
    private byte[] readRequest(InputStream in) throws IOException {
        // First, read headers
        byte[] buf = new byte[bufferSize];
        int total = 0;
        int headerEnd = -1;
        
        while (headerEnd == -1) {
            int read = in.read(buf, total, buf.length - total);
            if (read < 0) break;
            total += read;
            if (total >= 4) {
                String partial = new String(buf, 0, total, "UTF-8");
                headerEnd = partial.indexOf("\r\n\r\n");
            }
            if (headerEnd == -1 && total == buf.length) {
                byte[] newBuf = new byte[buf.length * 2];
                System.arraycopy(buf, 0, newBuf, 0, buf.length);
                buf = newBuf;
            }
        }
        
        if (headerEnd == -1) {
            // No headers found, return what we have
            byte[] full = new byte[total];
            System.arraycopy(buf, 0, full, 0, total);
            return full;
        }
        
        String headersPart = new String(buf, 0, headerEnd + 4, "UTF-8");
        int bodyStart = headerEnd + 4;
        int bodyPresent = total - bodyStart;
        
        // Parse headers for Content-Length and Transfer-Encoding
        String clStr = null;
        boolean chunked = false;
        for (String line : headersPart.split("\r\n")) {
            String lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                clStr = line.substring("content-length:".length()).trim();
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        
        if (chunked) {
            return readChunkedRequest(in, buf, headerEnd + 4, bodyStart, bodyPresent);
        }
        
        if (clStr != null) {
            int contentLength = Integer.parseInt(clStr);
            if (contentLength > maxRequestBodySize) {
                throw new IOException("Request body too large: " + contentLength + " > " + maxRequestBodySize);
            }
            // Read remaining body
            byte[] body = new byte[contentLength];
            System.arraycopy(buf, bodyStart, body, 0, bodyPresent);
            int totalRead = bodyPresent;
            while (totalRead < contentLength) {
                int read = in.read(body, totalRead, contentLength - totalRead);
                if (read < 0) break;
                totalRead += read;
            }
            // Reconstruct full request
            byte[] full = new byte[headerEnd + 4 + contentLength];
            System.arraycopy(buf, 0, full, 0, headerEnd + 4);
            System.arraycopy(body, 0, full, headerEnd + 4, contentLength);
            return full;
        }
        
        // No body expected
        byte[] full = new byte[total];
        System.arraycopy(buf, 0, full, 0, total);
        return full;
    }

    /**
     * Finds the start of the {@code \r\n\r\n} header terminator in raw
     * request bytes.
     */
    private static int indexOfHeaderEnd(byte[] buf) {
        for (int i = 0; i + 3 < buf.length; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Extracts the request body from the already-read request bytes.
     * {@code readRequest()} already consumed the body from the socket (and
     * de-chunked chunked bodies), so the body is sliced from {@code reqBuf} —
     * never re-read from the client stream.
     */
    private byte[] extractBody(byte[] reqBuf, int bodyStart, Map<String, String> headers) throws IOException {
        int available = reqBuf.length - bodyStart;
        if (available <= 0) {
            return new byte[0];
        }
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            // readChunkedRequest() already de-chunked the body into reqBuf
            return Arrays.copyOfRange(reqBuf, bodyStart, reqBuf.length);
        }
        String contentLengthStr = headers.get("content-length");
        if (contentLengthStr != null) {
            int cl = Integer.parseInt(contentLengthStr);
            if (cl > maxRequestBodySize) {
                throw new IOException("Request body too large: " + cl + " > " + maxRequestBodySize);
            }
            if (cl > 0) {
                if (cl > available) {
                    throw new IOException("Truncated request body: expected " + cl + " bytes, got " + available);
                }
                return Arrays.copyOfRange(reqBuf, bodyStart, bodyStart + cl);
            }
        }
        return new byte[0];
    }

    /**
     * Reads a chunked request body, de-chunks it in memory, and returns the
     * raw request (headers + de-chunked body).
     *
     * Uses an accumulate-and-dechunk approach: all raw chunked bytes are
     * buffered, then {@link #tryDechunk} parses them as complete ASCII
     * framing lines. This avoids the fragile incremental offset tracking of
     * the previous implementation.
     */
    private byte[] readChunkedRequest(InputStream in, byte[] headerBuf, int headerEnd, int bodyStart, int bodyPresent) throws IOException {
        // Accumulate the raw chunked bytes and de-chunk in memory.
        // The caller only needs headers + de-chunked body (extractBody slices the body).
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(headerBuf, bodyStart, bodyPresent);

        byte[] readBuf = new byte[bufferSize];
        while (true) {
            byte[] dechunked = tryDechunk(raw.toByteArray(), maxRequestBodySize);
            if (dechunked != null) {
                ByteArrayOutputStream full = new ByteArrayOutputStream();
                full.write(headerBuf, 0, headerEnd);
                full.write(dechunked, 0, dechunked.length);
                return full.toByteArray();
            }
            int read = in.read(readBuf);
            if (read < 0) {
                throw new IOException("Unexpected EOF in chunked request body");
            }
            raw.write(readBuf, 0, read);
            if (raw.size() > maxRequestBodySize + 65536) {
                throw new IOException("Chunked request body too large");
            }
        }
    }

    /**
     * Attempts to de-chunk accumulated raw request body bytes.
     *
     * @return the de-chunked body, or {@code null} if the buffer does not yet
     *         contain the complete chunked body (terminating 0-chunk consumed)
     */
    private static byte[] tryDechunk(byte[] raw, int maxBodySize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (true) {
            int lineEnd = findCrlf(raw, pos);
            if (lineEnd < 0) return null; // need more data
            String sizeLine = new String(raw, pos, lineEnd - pos, "UTF-8").trim();
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi).trim();
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size: " + sizeLine);
            }
            pos = lineEnd + 2;
            if (chunkSize == 0) {
                // terminating chunk: body is complete (we accept it even without
                // the final blank line — trailers are ignored)
                return out.toByteArray();
            }
            if (out.size() + chunkSize > maxBodySize) {
                throw new IOException("Chunked request body too large");
            }
            if (pos + chunkSize + 2 > raw.length) return null; // need more data
            out.write(raw, pos, chunkSize);
            pos += chunkSize;
            // consume CRLF after chunk data
            if (raw[pos] == '\r' && pos + 1 < raw.length && raw[pos + 1] == '\n') {
                pos += 2;
            } else if (pos + 1 >= raw.length) {
                return null; // need more data to see CRLF
            } else {
                throw new IOException("Missing CRLF after chunk data");
            }
        }
    }

    private static int findCrlf(byte[] buf, int from) {
        for (int i = from; i + 1 < buf.length; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') return i;
        }
        return -1;
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
            "sec-websocket-version",
            "te", "trailer",
            "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "sec-fetch-user",
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
            "priority"
        ).contains(lower);
    }

    private static boolean isRestrictedResponseHeader(String name) {
        String lower = name.toLowerCase();
        return Set.of(
            "connection", "transfer-encoding", "content-length",
            "content-security-policy", "content-security-policy-report-only",
            "set-cookie", "www-authenticate", "proxy-authenticate",
            "age", "date", "server", "vary"
        ).contains(lower);
    }

    /**
     * Validates a request path to prevent SSRF attacks.
     *
     * @param path the request path
     * @return {@code true} if the path is valid, {@code false} if it is an
     *         absolute-form URI, contains path traversal, or is empty
     */
    private static boolean isValidRequestPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        // Must start with /
        if (!path.startsWith("/")) {
            return false;
        }
        // Reject absolute-form URI (scheme://host...)
        if (path.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            return false;
        }
        // Basic path traversal protection
        if (path.contains("../") || path.contains("..\\") || path.contains("%2e%2e")) {
            return false;
        }
        return true;
    }

    /** Sends an HTTP error response to the client. */
    private void sendErrorResponse(OutputStream out, int statusCode, String message) throws IOException {
        String statusMsg;
        switch (statusCode) {
            case 400: statusMsg = "Bad Request"; break;
            case 404: statusMsg = "Not Found"; break;
            case 500: statusMsg = "Internal Server Error"; break;
            case 502: statusMsg = "Bad Gateway"; break;
            case 504: statusMsg = "Gateway Timeout"; break;
            default: statusMsg = "Error";
        }
        String body = "<html><body><h1>" + statusCode + " " + statusMsg + "</h1><p>" + message + "</p></body></html>";
        String response = "HTTP/1.1 " + statusCode + " " + statusMsg + "\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Content-Length: " + body.getBytes("UTF-8").length + "\r\n" +
            "Connection: close\r\n\r\n" +
            body;
        out.write(response.getBytes("UTF-8"));
        out.flush();
    }

    /** Stops the proxy server and shuts down its thread pool. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warn("ProxyServer", "Executor did not terminate gracefully, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        LOG.info("ProxyServer", "Stopped (was listening on " + proxyHost
            + ":" + listenPort + ")");
    }

    /** Returns the local port the proxy is listening on. */
    public int getListenPort() {
        return listenPort;
    }

    /** Returns the full target origin URL (e.g. {@code http://127.0.0.1:30141}). */
    public String getTargetOrigin() {
        return targetOrigin;
    }
}
