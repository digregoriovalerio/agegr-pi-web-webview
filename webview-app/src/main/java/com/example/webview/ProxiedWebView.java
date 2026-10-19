package com.example.webview;

import com.example.webview.logging.AppLogger;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Worker;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebErrorEvent;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A JavaFX {@link BorderPane} that wraps a {@link WebView} and interposes a
 * local {@link ProxyServer} between the page and the network.
 *
 * <h2>Why this class exists</h2>
 * JavaFX {@link WebView} is declared {@code final}, so it cannot be extended.
 * We use composition instead: this class owns a {@code WebView} instance and
 * exposes it via {@link #getWebView()}. Every page load is routed through a
 * local HTTP proxy ({@link ProxyServer}) to work around JavaFX WebView's
 * shortcomings with localhost servers:
 * <ul>
 *   <li>Origin checks — JavaFX sends the wrong Origin header to localhost</li>
 *   <li>User-Agent sniffing — pi-web rejects non-browser user agents</li>
 *   <li>SSE streaming — JavaFX does not expose native {@code EventSource}</li>
 *   <li>Chunked transfer encoding — JavaFX does not handle it reliably</li>
 * </ul>
 *
 * <h2>Architecture diagram</h2>
 * <pre>
 *   WebViewApp ──owns──> ProxiedWebView ──owns──> ProxyServer
 *                                  │              (HTTP proxy, header rewrite, chunked encoding)
 *                                  ├─owns──> SSEHandler  (SSE stream lifecycle, bounded pool)
 *                                  ├─owns──> AutoHello   (dev-only, removable)
 *                                  └─uses──> JSBridge    (window.javaBridge)
 *                                        ↑
 *                              Page JS calls postMessage()
 * </pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Own the {@link WebView} instance (center of this {@link BorderPane})</li>
 *   <li>Create and lifecycle-manage a {@link ProxyServer}</li>
 *   <li>Rewrite target URLs to proxy URLs on {@link #navigateTo(String)}</li>
 *   <li>Install the {@link JSBridge} (console capture, postMessage, error capture)</li>
 *   <li>Install the EventSource polyfill for SSE streaming</li>
 *   <li>Route JS-&gt;Java messages (via JSBridge callback) to the {@link SSEHandler}</li>
 *   <li>Log all communication between entities via {@link AppLogger}</li>
 * </ul>
 *
 * <h2>Critical design decision: strong JSBridge reference</h2>
 * JavaFX WebKit stores objects passed to {@code JSObject.setMember()} via
 * <b>weak references</b>. Without a strong reference held on the Java side,
 * the {@link JSBridge} is garbage-collected shortly after installation, and
 * every later {@code javaBridge.*} call from JavaScript fails silently (all
 * call sites in {@code bridge.js} and {@code autoHello.js} swallow exceptions
 * in try/catch blocks). This was the primary root cause of the "Hello message
 * never gets a reply" bug — once the bridge was GC'd, the EventSource polyfill
 * could no longer open SSE streams, so no replies ever reached the page.
 *
 * We fixed this by holding a {@link CopyOnWriteArrayList} of installed
 * {@link JSBridge} objects ({@link #installedBridges}), keeping them alive
 * for the lifetime of the {@link ProxiedWebView}.
 *
 * <h2>Usage</h2>
 * <pre>
 *   ProxiedWebView view = new ProxiedWebView(
 *       targetOrigin, proxyHost, bufferSize, userAgent,
 *       connectTimeout, sseReadTimeout, maxRequestBodySize);
 *   view.start();
 *   view.navigateTo("http://127.0.0.1:30141");
 *   Stage stage = new Stage();
 *   stage.setScene(new Scene(view, 1100, 750));
 *   stage.show();
 *   view.stop();
 * </pre>
 *
 * @see ProxyServer
 * @see JSBridge
 * @see SSEHandler
 * @see AutoHello
 * @see AppConfig
 * @see AppLogger
 */
public class ProxiedWebView extends BorderPane {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private final WebView webView;
    private ProxyServer proxy;
    private final String targetOrigin;
    private final String proxyHost;
    private final int bufferSize;
    private final String userAgent;
    private final int connectTimeout;
    private final int sseReadTimeout;
    private final int maxRequestBodySize;

    private String currentUrl;

    /**
     * Callback invoked when the page sends a message via
     * {@code javaBridge.postMessage(msg)}. Non-SSE messages only;
     * {@code __PI_SSE_START__} / {@code __PI_SSE_STOP__} are handled
     * internally by {@link #installJSBridge()}.
     */
    private Consumer<String> messageCallback;

    /**
     * Callback invoked when the page finishes loading.
     */
    private Runnable onPageLoaded;

    /**
     * Strong references to installed {@link JSBridge} objects.
     *
     * JavaFX WebKit stores objects passed to {@code setMember()} via WEAK
     * references. Without a strong reference held on the Java side, the bridge
     * is garbage-collected shortly after installation and every later
     * {@code javaBridge.*} call from JS fails silently.
     */
    private final List<JSBridge> installedBridges = new CopyOnWriteArrayList<>();

    /** Lazily initialized SSEHandler to avoid 'this' escape in constructor. */
    private volatile SSEHandler sseHandler;

    /**
     * Creates a proxied WebView with the given configuration.
     *
     * @param targetOrigin       target origin (e.g. {@code http://127.0.0.1:30141})
     * @param proxyHost          proxy bind host (e.g. {@code 127.0.0.1})
     * @param bufferSize         streaming buffer size (bytes)
     * @param userAgent          User-Agent string for proxy and SSE client
     * @param connectTimeout     connection timeout (ms)
     * @param sseReadTimeout     SSE stream read timeout (ms)
     * @param maxRequestBodySize max request body size (bytes)
     */
    @SuppressWarnings("this-escape")
    public ProxiedWebView(
        String targetOrigin,
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

        LOG.info("ProxiedWebView", "Creating with target " + targetOrigin);

        this.proxy = new ProxyServer(targetOrigin, 0, proxyHost, bufferSize, userAgent, connectTimeout, sseReadTimeout, maxRequestBodySize);

        this.webView = new WebView();
        // Explicit sizes so WebView2 (Windows) gets a valid layout pass
        webView.setMinSize(400, 300);
        webView.setPrefSize(1100, 700);
        webView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        // --- Diagnostics: load errors ---
        webView.getEngine().setOnError((WebErrorEvent event) -> {
            String msg = String.format(
                "WebView load error: %s (location=%s)", event.getMessage(), webView.getEngine().getLocation());
            LOG.error("ProxiedWebView", msg);
        });

        // --- Diagnostics: load worker exceptions ---
        webView.getEngine().getLoadWorker().exceptionProperty().addListener((obs, oldEx, newEx) -> {
            if (newEx != null) {
                LOG.error("ProxiedWebView", "Load worker exception: " + newEx.getMessage(), newEx);
            }
        });

        // --- Track page load and trigger callbacks ---
        webView.getEngine().getLoadWorker().stateProperty().addListener((ChangeListener<Worker.State>)
            (obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                String loc = webView.getEngine().getLocation();
                LOG.info("ProxiedWebView", "Page loaded: " + loc);
                installJSBridge();
                if (onPageLoaded != null) {
                    LOG.debug("ProxiedWebView", "Running onPageLoaded callback");
                    onPageLoaded.run();
                }
            } else if (newState == Worker.State.FAILED) {
                Throwable exc = webView.getEngine().getLoadWorker().getException();
                LOG.error("ProxiedWebView", "Load worker failed: "
                    + (exc != null ? exc.getMessage() : "unknown error"));
            }
        });

        // --- Track location changes (for address bar sync by WebViewApp) ---
        webView.getEngine().locationProperty().addListener((ChangeListener<String>) (obs, oldLoc, newLoc) -> {
            if (newLoc != null && (newLoc.startsWith("http://") || newLoc.startsWith("https://")) && !newLoc.equals(currentUrl)) {
                LOG.debug("ProxiedWebView", "Location changed: " + oldLoc + " -> " + newLoc);
                currentUrl = newLoc;
            }
        });

        setCenter(webView);
    }

    /**
     * Sets a callback to run when the page finishes loading.
     *
     * @param callback the callback to run on successful page load
     */
    public void setOnPageLoaded(Runnable callback) {
        this.onPageLoaded = callback;
    }

    /**
     * Sets a callback to receive messages posted from the page via
     * {@code javaBridge.postMessage(msg)}. Non-SSE messages only;
     * {@code __PI_SSE_START__} / {@code __PI_SSE_STOP__} are handled
     * internally.
     *
     * @param callback the message callback, or {@code null} to clear it
     */
    public void setMessageCallback(Consumer<String> callback) {
        this.messageCallback = callback;
        LOG.debug("ProxiedWebView", "Message callback set: " + (callback != null));
    }

    /**
     * Sends a message string into the page's JavaScript context by invoking
     * {@code window.receiveMessageFromJava(msg)} if that function exists.
     *
     * The message is escaped for safe embedding in a JavaScript string literal
     * (backslashes, quotes, newlines, control characters, and {@code </}).
     *
     * @param message the message to send to the page
     */
    public void sendToPage(String message) {
        String escaped = message
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("</", "<\\/")
            // U+2028/U+2029 are JS line terminators and would break the generated
            // script literal; escape them as Unicode sequences.
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029");
        String script = "if (typeof window.receiveMessageFromJava === 'function') { window.receiveMessageFromJava(\"" + escaped + "\"); }";
        LOG.comm("ProxiedWebView", "Page JS", "SEND_TO_PAGE",
            message.substring(0, Math.min(120, message.length())) + (message.length() > 120 ? "..." : ""));
        webView.getEngine().executeScript(script);
    }

    /** Returns the underlying JavaFX {@link WebView}. */
    public WebView getWebView() {
        return webView;
    }

    /** Returns the local port the proxy is listening on. */
    public int getProxyListenPort() {
        return proxy.getListenPort();
    }

    /** Returns the underlying proxy server (useful for testing or advanced config). */
    public ProxyServer getProxy() {
        return proxy;
    }

    /**
     * Gets or creates the {@link SSEHandler} (lazy initialization to avoid
     * 'this' escape in the constructor).
     *
     * @return the SSE handler instance
     */
    private SSEHandler getSseHandler() {
        if (sseHandler == null) {
            synchronized (this) {
                if (sseHandler == null) {
                    sseHandler = new SSEHandler(this, proxyHost, bufferSize, userAgent, connectTimeout, sseReadTimeout);
                }
            }
        }
        return sseHandler;
    }

    /**
     * Starts the proxy server. Must be called before {@link #navigateTo(String)}.
     *
     * @throws RuntimeException if the proxy fails to start
     */
    public void start() {
        try {
            proxy.start();
            LOG.info("ProxiedWebView", "Proxy listening on " + proxyHost + ":" + proxy.getListenPort());
        } catch (Exception e) {
            LOG.error("ProxiedWebView", "Failed to start proxy: " + e.getMessage());
            throw new RuntimeException("Failed to start proxy", e);
        }
    }

    /**
     * Stops the proxy server and all SSE streams. Call this when the
     * application exits.
     */
    public void stop() {
        LOG.info("ProxiedWebView", "Stopping...");
        getSseHandler().stopAll();
        proxy.stop();
        LOG.info("ProxiedWebView", "Stopped");
    }

    /**
     * Navigates to the given URL through the proxy. The URL is normalized
     * (scheme added if missing), rewritten to the local proxy port, and
     * loaded by the WebView engine.
     *
     * @param rawUrl the URL to navigate to (e.g. {@code 127.0.0.1:30141})
     */
    public void navigateTo(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        if (url == null) {
            return;
        }
        String scheme;
        try {
            scheme = new URI(url).getScheme();
        } catch (Exception ignored) {
            scheme = null;
        }
        boolean proxied = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (proxied) {
            String targetOrigin = extractTargetOrigin(url);
            if (!targetOrigin.equals(proxy.getTargetOrigin())) {
                LOG.info("ProxiedWebView", "Target origin changed: " + proxy.getTargetOrigin() + " -> " + targetOrigin + ", recreating proxy");
                recreateProxy(targetOrigin);
            }
            currentUrl = url;
            String proxyUrl = rewriteThroughProxy(url);
            LOG.comm("User", "ProxiedWebView", "NAVIGATE", rawUrl);
            LOG.comm("ProxiedWebView", "Proxy", "REWRITE", url + " -> " + proxyUrl);
            webView.getEngine().load(proxyUrl);
        } else {
            // Non-HTTP(S) URLs (e.g. about:blank) load directly without the proxy so they
            // cannot corrupt the proxy target origin or orphan SSE streams.
            LOG.info("ProxiedWebView", "Loading non-proxied URL directly: " + url);
            currentUrl = url;
            webView.getEngine().load(url);
        }
    }

    /**
     * Extracts the target origin (scheme://host:port) from a full URL.
     *
     * @param url the full URL
     * @return the origin string, or the current proxy target origin on failure
     */
    private String extractTargetOrigin(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null) {
                // No host (e.g. about:blank) — cannot derive an origin; keep current proxy target.
                return proxy.getTargetOrigin();
            }
            int port = uri.getPort();
            if (port == -1) {
                port = ("https".equalsIgnoreCase(scheme)) ? 443 : 80;
            }
            return scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            LOG.warn("ProxiedWebView", "Failed to extract target origin from: " + url);
            return proxy.getTargetOrigin(); // fallback to current
        }
    }

    /**
     * Recreates the proxy server with a new target origin. Called when the
     * user navigates to a different origin than the one currently proxied.
     */
    private void recreateProxy(String newTargetOrigin) {
        // Stop existing SSE streams first — they target the old origin and would
        // otherwise keep running (and orphan reader threads) against the old proxy.
        getSseHandler().stopAll();
        // Stop existing proxy
        proxy.stop();
        // Create new proxy with new target origin
        this.proxy = new ProxyServer(newTargetOrigin, 0, proxyHost, bufferSize, userAgent, connectTimeout, sseReadTimeout, maxRequestBodySize);
        // Restart proxy
        try {
            proxy.start();
            LOG.info("ProxiedWebView", "Proxy recreated and listening on " + proxyHost + ":" + proxy.getListenPort());
        } catch (Exception e) {
            LOG.error("ProxiedWebView", "Failed to restart proxy: " + e.getMessage());
            throw new RuntimeException("Failed to restart proxy", e);
        }
        // SSE handler needs to be recreated because it holds reference to old proxy
        this.sseHandler = null; // will be lazily recreated via getSseHandler()
    }

    /**
     * Drops all but the most recently installed {@link JSBridge}, preventing
     * unbounded growth of the strong-reference list (one new bridge per page load).
     */
    private void pruneInstalledBridges() {
        while (installedBridges.size() > 1) {
            installedBridges.remove(0);
        }
    }

    /**
     * Rewrites a target URL so it goes through the local proxy. The proxy
     * always speaks HTTP locally; the target scheme is preserved via headers.
     *
     * @param url the target URL
     * @return the proxy URL
     */
    private String rewriteThroughProxy(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                LOG.warn("ProxiedWebView", "Unsupported scheme in URL: " + url);
                return url;
            }
            // Proxy always speaks HTTP locally; target scheme is preserved via headers
            String proxyUrl = "http://" + proxyHost + ":" + proxy.getListenPort()
                + (uri.getPath().isEmpty() ? "/" : uri.getPath())
                + (uri.getQuery() != null ? "?" + uri.getQuery() : "")
                + (uri.getFragment() != null ? "#" + uri.getFragment() : "");
            return proxyUrl;
        } catch (Exception e) {
            LOG.error("ProxiedWebView", "Failed to rewrite URL: " + url + " - " + e.getMessage());
            return url; // fallback
        }
    }

    /**
     * Normalizes a user-supplied URL. Adds {@code https://} when no scheme is
     * present; returns {@code null} for blank input.
     *
     * @param raw the raw user input
     * @return the normalized URL, or {@code null} if blank
     */
    static String normalizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim();
        if (url.isEmpty()) {
            return null;
        }
        if (url.startsWith("about:")) {
            return url;
        }
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null) {
                // No scheme - default to HTTP so local plain-HTTP targets (e.g. pi-web)
                // are reachable through the proxy.
                url = "http://" + url;
            }
            return url;
        } catch (Exception e) {
            // If URI parsing fails, apply simple heuristic
            if (!url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
                url = "http://" + url;
            }
            return url;
        }
    }

    // ========================================================================
    // JS Bridge installation (idempotent)
    // ========================================================================

    /**
     * Sets up the JS bridge and EventSource polyfill.
     *
     * Runs on every successful page load: a new document gets a fresh window
     * object, so {@code setMember()} must be re-executed (bridge.js itself is
     * guarded per-document by {@code window.__piBridgeInstalled}).
     *
     * The bridge routes messages as follows:
     * <ul>
     *   <li>{@code __PI_SSE_START__:url} — forwarded to {@link SSEHandler#start(String)}</li>
     *   <li>{@code __PI_SSE_STOP__:url} — forwarded to {@link SSEHandler#stop(String)}</li>
     *   <li>everything else — forwarded to the external {@link #messageCallback}</li>
     * </ul>
     */
    private void installJSBridge() {
        try {
            JSObject window = (JSObject) webView.getEngine().executeScript("window");
            if (window == null) {
                LOG.warn("ProxiedWebView", "JS window object is null, cannot install bridge");
                return;
            }

            // Create the bridge. The internal callback routes SSE start/stop
            // messages to the SSEHandler and forwards everything else to the
            // external messageCallback (owned by WebViewApp).
            JSBridge jsBridge = new JSBridge(msg -> {
                LOG.comm("JSBridge", "ProxiedWebView", "MESSAGE RECEIVED", msg);
                if (msg.startsWith("__PI_SSE_START__:")) {
                    String sseUrl = msg.substring("__PI_SSE_START__:".length());
                    LOG.comm("ProxiedWebView", "SSEHandler", "START", sseUrl);
                    getSseHandler().start(sseUrl);
                    return;
                }
                if (msg.startsWith("__PI_SSE_STOP__:")) {
                    String sseUrl = msg.substring("__PI_SSE_STOP__:".length());
                    LOG.comm("ProxiedWebView", "SSEHandler", "STOP", sseUrl);
                    getSseHandler().stop(sseUrl);
                    return;
                }
                if (messageCallback != null) {
                    messageCallback.accept(msg);
                } else {
                    LOG.warn("ProxiedWebView", "Received JS message but no messageCallback set: " + msg);
                }
            });
            // Hold a strong reference: WebKit keeps only a weak reference to
            // setMember objects; without this the bridge is GC'd and all later
            // JS->Java calls fail silently.
            installedBridges.add(jsBridge);
            pruneInstalledBridges();
            window.setMember("javaBridge", jsBridge);

            // Load the bridge script from classpath resource and execute it.
            // Guarded by __piBridgeInstalled so repeated calls don't re-run the IIFE.
            String bridgeScript = readResource("bridge.js");
            if (bridgeScript == null || bridgeScript.isEmpty()) {
                LOG.error("ProxiedWebView", "bridge.js resource not found or empty");
                return;
            }
            webView.getEngine().executeScript(bridgeScript);
            LOG.info("ProxiedWebView", "JS bridge installed");
        } catch (Exception e) {
            LOG.error("ProxiedWebView", "Failed to install JS bridge: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a text resource from the classpath using {@link ResourceUtil}.
     *
     * @param name resource name (e.g. {@code bridge.js})
     * @return resource content as UTF-8 string, or {@code null} if not found
     */
    private static String readResource(String name) {
        return ResourceUtil.readResource(name, ProxiedWebView.class);
    }

    /**
     * Executes arbitrary JavaScript after the page has loaded. Useful for
     * injecting extensions such as AutoHello.
     *
     * @param script JavaScript code to execute
     */
    public void executeScriptAfterLoad(String script) {
        if (script == null || script.isEmpty()) {
            return;
        }
        LOG.info("ProxiedWebView", "Executing post-load script (" + script.length() + " chars)");
        webView.getEngine().executeScript(script);
    }
}
