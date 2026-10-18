package com.example.webview;

import com.example.webview.logging.AppLogger;

import javafx.application.Platform;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSEHandler manages Server-Sent Events (SSE) streaming between the target server
 * and the JavaFX WebView page.
 *
 * Communication flow:
 *   Page JS (EventSource polyfill) --__PI_SSE_START__:url--> JSBridge --postMessage--> ProxiedWebView --callback--> SSEHandler.start(url)
 *   SSEHandler --HTTP GET--> Target SSE endpoint
 *   Target SSE endpoint --events--> SSEHandler --sendToPage--> ProxiedWebView --executeScript--> Page JS (receiveMessageFromJava)
 *   Page JS (EventSource.close) --__PI_SSE_STOP__:url--> JSBridge --postMessage--> ProxiedWebView --callback--> SSEHandler.stop(url)
 *
 * All configuration (User-Agent, buffer size, timeouts, proxy host) comes from
 * {@link AppConfig}, which can be overridden via system properties.
 */
public class SSEHandler {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private final ProxiedWebView proxiedWebView;

    // Tracks active SSE streams opened by the EventSource polyfill in the page.
    private final Map<String, AtomicBoolean> sseStreams = new ConcurrentHashMap<>();

    /** Buffer size for reading SSE stream chunks. */
    private final int bufferSize;

    /** Proxy host used for Origin/Referer headers. */
    private final String proxyHost;

    /**
     * Create an SSE handler attached to a ProxiedWebView.
     *
     * @param proxiedWebView the view used to send events back to the page
     */
    public SSEHandler(ProxiedWebView proxiedWebView) {
        this.proxiedWebView = proxiedWebView;
        this.bufferSize = AppConfig.getBufferSize();
        this.proxyHost = AppConfig.getProxyHost();
        LOG.info("SSEHandler", "Created and attached to ProxiedWebView"
            + " (bufferSize=" + bufferSize + ", proxyHost=" + proxyHost + ")");
    }

    /**
     * Handle a __PI_SSE_START__ message from the page. Opens a background thread
     * that reads the SSE stream and pushes each chunk back into the page.
     *
     * @param sseUrl the SSE stream URL
     */
    public void start(String sseUrl) {
        LOG.info("SSEHandler", "START requested for " + sseUrl);

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean previous = sseStreams.putIfAbsent(sseUrl, running);
        if (previous != null) {
            LOG.warn("SSEHandler", "Stream already running for " + sseUrl + ", stopping old one first");
            stop(sseUrl);
            sseStreams.put(sseUrl, running);
        }

        Thread readerThread = new Thread(() -> {
            String fetchUrl = sseUrl;
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                LOG.comm("SSEHandler", "Target(" + fetchUrl + ")", "SSE OPEN",
                    "Opening SSE stream");

                URL url = new URL(fetchUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(AppConfig.DEFAULT_CONNECT_TIMEOUT);
                conn.setReadTimeout(AppConfig.DEFAULT_SSE_READ_TIMEOUT);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Connection", "keep-alive");
                conn.setRequestProperty("User-Agent", AppConfig.getUserAgent());
                int proxyPort = proxiedWebView.getProxyListenPort();
                conn.setRequestProperty("Origin", "http://" + proxyHost + ":" + proxyPort);
                conn.setRequestProperty("Referer", "http://" + proxyHost + ":" + proxyPort + "/");

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
                StringBuilder leftover = new StringBuilder();
                int totalBytes = 0;

                while (running.get() && (read = in.read(buf)) != -1) {
                    totalBytes += read;
                    String chunk = new String(buf, 0, read, "UTF-8");
                    LOG.commTrace("Target(" + fetchUrl + ")", "SSEHandler", "SSE CHUNK",
                        read + " bytes (total: " + totalBytes + "): "
                        + chunk.substring(0, Math.min(200, chunk.length())).replace("\n", "\\n"));

                    leftover.append(chunk);
                    processSSEChunk(leftover, running);
                }

                if (leftover.length() > 0 && running.get()) {
                    String remaining = leftover.toString().trim();
                    if (!remaining.isEmpty()) {
                        LOG.comm("SSEHandler", "Page", "SSE FLUSH",
                            "Flushing remaining " + remaining.length() + " bytes");
                        Platform.runLater(() -> proxiedWebView.sendToPage(remaining));
                    }
                }

                LOG.info("SSEHandler", "Stream ended normally: " + fetchUrl + " (total bytes: " + totalBytes + ")");
            } catch (Exception e) {
                if (running.get()) {
                    LOG.error("SSEHandler", "Error reading " + fetchUrl + ": " + e.getMessage());
                }
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
                sseStreams.remove(sseUrl, running);
                LOG.info("SSEHandler", "Stream closed: " + fetchUrl);
            }
        }, "sse-reader-" + System.identityHashCode(sseUrl));
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Process a chunk of SSE data, extracting and dispatching complete events.
     */
    private void processSSEChunk(StringBuilder leftover, AtomicBoolean running) {
        String data = leftover.toString();
        int idx;
        while ((idx = data.indexOf("\n\n")) >= 0) {
            String eventBlock = data.substring(0, idx);
            data = data.substring(idx + 2);
            if (running.get() && !eventBlock.isBlank()) {
                String finalEvent = eventBlock.trim();
                if (!finalEvent.isEmpty()) {
                    String eventWithTerminator = finalEvent + "\n\n";
                    LOG.comm("SSEHandler", "Page", "SSE EVENT",
                        finalEvent.substring(0, Math.min(100, finalEvent.length())).replace("\n", "\\n"));
                    Platform.runLater(() -> proxiedWebView.sendToPage(eventWithTerminator));
                }
            }
        }
        leftover.setLength(0);
        leftover.append(data);
    }

    /**
     * Handle a __PI_SSE_STOP__ message from the page. Stops the SSE reader thread.
     *
     * @param sseUrl the SSE stream URL to stop
     */
    public void stop(String sseUrl) {
        LOG.info("SSEHandler", "STOP requested for " + sseUrl);
        AtomicBoolean running = sseStreams.get(sseUrl);
        if (running != null) {
            running.set(false);
            sseStreams.remove(sseUrl);
            LOG.info("SSEHandler", "Stopping stream: " + sseUrl);
        } else {
            LOG.debug("SSEHandler", "No active stream for " + sseUrl);
        }
    }

    /** Stop all active SSE streams. Call on application shutdown. */
    public void stopAll() {
        LOG.info("SSEHandler", "Stopping all " + sseStreams.size() + " streams");
        for (Map.Entry<String, AtomicBoolean> entry : sseStreams.entrySet()) {
            entry.getValue().set(false);
            LOG.debug("SSEHandler", "Signalling stop for " + entry.getKey());
        }
        sseStreams.clear();
    }
}
