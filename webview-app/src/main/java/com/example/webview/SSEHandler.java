package com.example.webview;

import com.example.webview.logging.AppLogger;

import javafx.application.Platform;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSEHandler manages Server-Sent Events (SSE) streaming between the target
 * server and the JavaFX WebView page.
 *
 * <h2>Purpose</h2>
 * JavaFX WebView does not expose a working native {@code EventSource}, so
 * pi-web's chat replies (streamed via SSE on {@code /api/agent/{id}/events})
 * cannot reach the page directly. This class opens an outbound HTTP GET to
 * the SSE endpoint on behalf of the page, reads events as they arrive, and
 * pushes each one back into the page via
 * {@link ProxiedWebView#sendToPage(String)} →
 * {@code window.receiveMessageFromJava(msg)}.
 *
 * <h2>Architecture diagram</h2>
 * <pre>
 *   Page JS (EventSource polyfill)
 *       --__PI_SSE_START__:url--> JSBridge --postMessage--> ProxiedWebView
 *                                                      |
 *                                                      v (callback)
 *                                                SSEHandler.start(url)
 *                                                      |
 *                                                      v (HTTP GET)
 *                                               Target SSE endpoint
 *                                                      |
 *                                                      v (events/chunks)
 *                                                SSEHandler
 *                                                      |
 *                                                      v (sendToPage)
 *                                               ProxiedWebView
 *                                                      |
 *                                                      v (executeScript)
 *                                               Page JS (receiveMessageFromJava)
 *                                                      |
 *                                                       (EventSource.close)
 *       --__PI_SSE_STOP__:url--> JSBridge --postMessage--> ProxiedWebView
 *                                                      |
 *                                                      v (callback)
 *                                                SSEHandler.stop(url)
 * </pre>
 *
 * <h2>Critical bug fix: relative SSE URLs (fixed)</h2>
 * pi-web's frontend opens SSE streams with relative URLs like
 * {@code /api/agent/${id}/events}. The EventSource polyfill forwards the raw
 * string to {@code __PI_SSE_START__}. The original code passed that string
 * directly to {@code URI.create().toURL()}, which throws
 * {@code IllegalArgumentException: URI is not absolute} for relative URLs —
 * so the reply stream never opened and no chat responses ever reached the GUI.
 * <p>
 * Fix: {@link #start(String)} now detects relative URLs (no scheme) and
 * resolves them against the target origin before opening the connection.
 *
 * <h2>Stream lifecycle</h2>
 * Each SSE stream runs on its own thread from a bounded executor pool (4
 * threads). Stream state (running flag + leftover buffer) is tracked per URL
 * so that stop requests can interrupt the correct reader. SSE data is
 * parsed incrementally: chunks are buffered, split on {@code \n\n} event
 * boundaries, and each complete event is sent to the page.
 *
 * <h2>Communication edges logged</h2>
 * <ul>
 *   <li>{@code SSEHandler -> Target} — SSE stream open</li>
 *   <li>{@code Target -> SSEHandler} — SSE events/chunks</li>
 *   <li>{@code SSEHandler -> Page} — SSE events via sendToPage</li>
 * </ul>
 *
 * @see ProxiedWebView
 * @see JSBridge
 * @see AppLogger
 */
public class SSEHandler {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private final ProxiedWebView proxiedWebView;
    private final int bufferSize;
    private final String userAgent;
    private final int connectTimeout;
    private final int sseReadTimeout;

    /** Executor for SSE reader threads (bounded pool, 4 threads). */
    private final ExecutorService executor = Executors.newFixedThreadPool(
        4,
        new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sse-reader-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        }
    );

    /** Per-stream state including running flag and leftover buffer. */
    private static class StreamState {
        final AtomicBoolean running = new AtomicBoolean(true);
        final StringBuilder leftover = new StringBuilder();
    }

    /** Tracks active SSE streams keyed by URL. */
    private final Map<String, StreamState> sseStreams = new ConcurrentHashMap<>();

    /**
     * Creates an SSE handler attached to a ProxiedWebView.
     *
     * @param proxiedWebView the view used to send events back to the page
     * @param proxyHost      proxy host for Origin/Referer headers
     * @param bufferSize     streaming buffer size (bytes)
     * @param userAgent      User-Agent for SSE requests
     * @param connectTimeout connection timeout (ms)
     * @param sseReadTimeout SSE stream read timeout (ms)
     */
    public SSEHandler(
        ProxiedWebView proxiedWebView,
        String proxyHost,
        int bufferSize,
        String userAgent,
        int connectTimeout,
        int sseReadTimeout
    ) {
        this.proxiedWebView = proxiedWebView;
        this.bufferSize = bufferSize;
        this.userAgent = userAgent;
        this.connectTimeout = connectTimeout;
        this.sseReadTimeout = sseReadTimeout;
        LOG.info("SSEHandler", "Created and attached to ProxiedWebView"
            + " (bufferSize=" + bufferSize + ")");
    }

    /**
     * Handles a {@code __PI_SSE_START__} message from the page. Opens a
     * background thread that reads the SSE stream and pushes each chunk back
     * into the page via {@link ProxiedWebView#sendToPage(String)}.
     *
     * @param sseUrl the SSE stream URL (may be relative, e.g.
     *               {@code /api/agent/.../events})
     */
    public void start(String sseUrl) {
        LOG.info("SSEHandler", "START requested for " + sseUrl);

        StreamState state = new StreamState();
        StreamState previous = sseStreams.putIfAbsent(sseUrl, state);
        if (previous != null) {
            LOG.warn("SSEHandler", "Stream already running for " + sseUrl + ", stopping old one first");
            stop(sseUrl);
            sseStreams.put(sseUrl, state);
        }

        executor.execute(() -> {
            String fetchUrl = sseUrl;
            // Page JS may pass relative URLs (e.g. "/api/agent/.../events");
            // resolve them against the target origin.
            if (!fetchUrl.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
                fetchUrl = proxiedWebView.getProxy().getTargetOrigin() + fetchUrl;
                LOG.debug("SSEHandler", "Resolved relative SSE URL against target origin: " + fetchUrl);
            }
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                LOG.comm("SSEHandler", "Target(" + fetchUrl + ")", "SSE OPEN",
                    "Opening SSE stream");

                URL url = URI.create(fetchUrl).toURL();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(connectTimeout);
                conn.setReadTimeout(sseReadTimeout); // Idle timeout
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Connection", "keep-alive");
                conn.setRequestProperty("User-Agent", userAgent);
                // Use target origin for CORS, not proxy
                String targetOrigin = proxiedWebView.getProxy().getTargetOrigin();
                conn.setRequestProperty("Origin", targetOrigin);
                conn.setRequestProperty("Referer", targetOrigin + "/");

                conn.connect();
                int status = conn.getResponseCode();
                LOG.comm("Target(" + fetchUrl + ")", "SSEHandler", "SSE RESPONSE",
                    "Status " + status + " for " + fetchUrl);

                if (status >= 400) {
                    in = conn.getErrorStream();
                } else {
                    in = conn.getInputStream();
                }
                if (in == null) {
                    LOG.error("SSEHandler", "No input stream for " + fetchUrl);
                    return;
                }
                LOG.info("SSEHandler", "Got input stream, starting to read from " + fetchUrl);

                byte[] buf = new byte[bufferSize];
                int read;
                int totalBytes = 0;

                while (state.running.get() && (read = in.read(buf)) != -1) {
                    totalBytes += read;
                    String chunk = new String(buf, 0, read, "UTF-8");
                    LOG.commTrace("Target(" + fetchUrl + ")", "SSEHandler", "SSE CHUNK",
                        read + " bytes (total: " + totalBytes + "): "
                        + chunk.substring(0, Math.min(200, chunk.length())).replace("\n", "\\n"));

                    state.leftover.append(chunk);
                    processSSEChunk(state);
                }

                // Flush any remaining data on normal stream end
                if (state.leftover.length() > 0 && state.running.get()) {
                    String remaining = state.leftover.toString().trim();
                    if (!remaining.isEmpty()) {
                        LOG.comm("SSEHandler", "Page", "SSE FLUSH",
                            "Flushing remaining " + remaining.length() + " bytes");
                        Platform.runLater(() -> proxiedWebView.sendToPage(remaining));
                    }
                }

                LOG.info("SSEHandler", "Stream ended normally: " + fetchUrl + " (total bytes: " + totalBytes + ")");
            } catch (Exception e) {
                if (state.running.get()) {
                    LOG.error("SSEHandler", "Error reading " + fetchUrl + ": " + e.getMessage());
                }
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
                sseStreams.remove(sseUrl, state);
                LOG.info("SSEHandler", "Stream closed: " + fetchUrl);
            }
        });
    }

    /**
     * Processes a chunk of SSE data, extracting and dispatching complete
     * events (delimited by {@code \n\n}) to the page.
     */
    private void processSSEChunk(StreamState state) {
        String data = state.leftover.toString();
        int idx;
        while ((idx = data.indexOf("\n\n")) >= 0) {
            String eventBlock = data.substring(0, idx);
            data = data.substring(idx + 2);
            if (state.running.get() && !eventBlock.isBlank()) {
                String finalEvent = eventBlock.trim();
                if (!finalEvent.isEmpty()) {
                    String eventWithTerminator = finalEvent + "\n\n";
                    LOG.comm("SSEHandler", "Page", "SSE EVENT",
                        finalEvent.substring(0, Math.min(100, finalEvent.length())).replace("\n", "\\n"));
                    Platform.runLater(() -> proxiedWebView.sendToPage(eventWithTerminator));
                }
            }
        }
        state.leftover.setLength(0);
        state.leftover.append(data);
    }

    /**
     * Handles a {@code __PI_SSE_STOP__} message from the page. Stops the
     * SSE reader thread for the given URL and flushes any remaining
     * buffered data.
     *
     * @param sseUrl the SSE stream URL to stop
     */
    public void stop(String sseUrl) {
        LOG.info("SSEHandler", "STOP requested for " + sseUrl);
        StreamState state = sseStreams.get(sseUrl);
        if (state != null) {
            state.running.set(false);
            // Flush any remaining buffered data before removing
            if (state.leftover.length() > 0) {
                String remaining = state.leftover.toString().trim();
                if (!remaining.isEmpty()) {
                    LOG.comm("SSEHandler", "Page", "SSE FLUSH (on stop)",
                        "Flushing remaining " + remaining.length() + " bytes");
                    Platform.runLater(() -> proxiedWebView.sendToPage(remaining));
                }
            }
            sseStreams.remove(sseUrl, state); // conditional remove to avoid race
            LOG.info("SSEHandler", "Stopping stream: " + sseUrl);
        } else {
            LOG.debug("SSEHandler", "No active stream for " + sseUrl);
        }
    }

    /** Stops all active SSE streams. Call on application shutdown. */
    public void stopAll() {
        LOG.info("SSEHandler", "Stopping all " + sseStreams.size() + " streams");
        for (Map.Entry<String, StreamState> entry : sseStreams.entrySet()) {
            StreamState state = entry.getValue();
            state.running.set(false);
            // Flush any remaining buffered data
            if (state.leftover.length() > 0) {
                String remaining = state.leftover.toString().trim();
                if (!remaining.isEmpty()) {
                    LOG.comm("SSEHandler", "Page", "SSE FLUSH (on stopAll)",
                        "Flushing remaining " + remaining.length() + " bytes for " + entry.getKey());
                    Platform.runLater(() -> proxiedWebView.sendToPage(remaining));
                }
            }
            LOG.debug("SSEHandler", "Signalling stop for " + entry.getKey());
        }
        sseStreams.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warn("SSEHandler", "Executor did not terminate gracefully, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
