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
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * A JavaFX {@link BorderPane} that wraps a {@link WebView} and interposes a
 * local {@link ProxyServer} between the page and the network.
 *
 * Since {@link WebView} is {@code final}, we use composition: this class owns
 * the WebView instance and exposes it via {@link #getWebView()}. All navigation
 * is rewritten to go through the proxy, which fixes up JavaFX WebView's
 * shortcomings with localhost servers (origin checks, User-Agent sniffing,
 * SSE streaming, etc.).
 *
 * Responsibilities:
 *   - Own the WebView instance (center of this BorderPane)
 *   - Create and lifecycle-manage a ProxyServer
 *   - Rewrite target URLs to proxy URLs on navigate()
 *   - Install the JSBridge (console capture, postMessage, error capture)
 *   - Install the EventSource polyfill for SSE streaming
 *   - Optionally inject AutoHello (development only)
 *   - Route JS->Java messages (via JSBridge callback) to the SSEHandler
 *   - Log all communication between entities
 *
 * Relationships:
 *   ProxiedWebView HAS-A WebView
 *   ProxiedWebView HAS-A ProxyServer
 *   ProxiedWebView HAS-A JSBridge
 *   ProxiedWebView HAS-A SSEHandler
 *   ProxiedWebView HAS-A AutoHello (optional)
 *   WebViewApp HAS-A ProxiedWebView
 *
 * Usage:
 *   ProxiedWebView view = new ProxiedWebView("127.0.0.1", 30141);
 *   view.start();
 *   view.navigateTo("http://127.0.0.1:30141");
 *   Stage stage = new Stage();
 *   stage.setScene(new Scene(view, 1100, 750));
 *   stage.show();
 *   view.stop();
 */
public class ProxiedWebView extends BorderPane {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private final WebView webView;
    private final ProxyServer proxy;
    private final SSEHandler sseHandler;
    private final AutoHello autoHello;

    private String currentUrl;

    /** Callback invoked when the page sends a message via javaBridge.postMessage(). */
    private Consumer<String> messageCallback;

    /** Callback invoked when the page finishes loading. */
    private Runnable onPageLoaded;

    /**
     * Create a proxied WebView.
     *
     * @param targetHost target host (e.g. "127.0.0.1")
     * @param targetPort target port (e.g. 30141)
     */
    public ProxiedWebView(String targetHost, int targetPort) {
        LOG.info("ProxiedWebView", "Creating with target " + targetHost + ":" + targetPort);

        this.proxy = new ProxyServer(targetHost, targetPort, 0);
        this.sseHandler = new SSEHandler(this);
        this.autoHello = new AutoHello();

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
                LOG.error("ProxiedWebView", "Load worker exception: " + newEx.getMessage());
                newEx.printStackTrace();
            }
        });

        // --- Track page load and trigger callbacks ---
        webView.getEngine().getLoadWorker().stateProperty().addListener((ChangeListener<Worker.State>)
            (obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                String loc = webView.getEngine().getLocation();
                LOG.info("ProxiedWebView", "Page loaded: " + loc);
                installJSBridge();
                injectAutoHello();
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
            if (newLoc != null && newLoc.matches("^https?://") && !newLoc.equals(currentUrl)) {
                LOG.debug("ProxiedWebView", "Location changed: " + oldLoc + " -> " + newLoc);
                currentUrl = newLoc;
            }
        });

        setCenter(webView);
    }

    /**
     * Set a callback to run when the page finishes loading.
     */
    public void setOnPageLoaded(Runnable callback) {
        this.onPageLoaded = callback;
    }

    /**
     * Set a callback to receive messages posted from the page via {@code javaBridge.postMessage(msg)}.
     * Non-SSE messages only; __PI_SSE_START__/__PI_SSE_STOP__ are handled internally.
     */
    public void setMessageCallback(Consumer<String> callback) {
        this.messageCallback = callback;
        LOG.debug("ProxiedWebView", "Message callback set: " + (callback != null));
    }

    /**
     * Send a message string into the page's JavaScript context by invoking
     * {@code window.receiveMessageFromJava(msg)} if that function exists.
     *
     * The message is escaped for safe embedding in a JavaScript string literal.
     */
    public void sendToPage(String message) {
        String escaped = message
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
        String script = "if (typeof window.receiveMessageFromJava === 'function') { window.receiveMessageFromJava(\"" + escaped + "\"); }";
        LOG.comm("ProxiedWebView", "Page JS", "SEND_TO_PAGE",
            message.substring(0, Math.min(120, message.length())) + (message.length() > 120 ? "..." : ""));
        webView.getEngine().executeScript(script);
    }

    /** Return the underlying JavaFX WebView. */
    public WebView getWebView() {
        return webView;
    }

    /** Return the local port the proxy is listening on. */
    public int getProxyListenPort() {
        return proxy.getListenPort();
    }

    /** Return the underlying proxy server (useful for testing or advanced config). */
    public ProxyServer getProxy() {
        return proxy;
    }

    /** Start the proxy server. Must be called before {@link #navigateTo(String)}. */
    public void start() {
        try {
            proxy.start();
            LOG.info("ProxiedWebView", "Proxy listening on " + AppConfig.getProxyHost() + ":" + proxy.getListenPort());
        } catch (Exception e) {
            LOG.error("ProxiedWebView", "Failed to start proxy: " + e.getMessage());
            throw new RuntimeException("Failed to start proxy", e);
        }
    }

    /** Stop the proxy server and all SSE streams. Call this when the application exits. */
    public void stop() {
        LOG.info("ProxiedWebView", "Stopping...");
        sseHandler.stopAll();
        proxy.stop();
        LOG.info("ProxiedWebView", "Stopped");
    }

    /** Navigate to the given URL through the proxy. */
    public void navigateTo(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        if (url == null) {
            return;
        }
        currentUrl = url;
        String proxyUrl = rewriteThroughProxy(url);
        LOG.comm("User", "ProxiedWebView", "NAVIGATE", rawUrl);
        LOG.comm("ProxiedWebView", "Proxy", "REWRITE", url + " -> " + proxyUrl);
        webView.getEngine().load(proxyUrl);
    }

    /** Rewrite a target URL so it goes through the local proxy. */
    private String rewriteThroughProxy(String url) {
        return url.replaceFirst("^https?://" + proxy.getTargetOrigin().substring(7),
            "http://" + AppConfig.getProxyHost() + ":" + proxy.getListenPort());
    }

    /** Adds https:// when the input has no scheme; returns null for blank input. */
    private static String normalizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim();
        if (url.isEmpty()) {
            return null;
        }
        if (!url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") && !url.startsWith("about:")) {
            url = "https://" + url;
        }
        return url;
    }

    // ========================================================================
    // JS Bridge installation (idempotent)
    // ========================================================================

    /** Set up the JS bridge and EventSource polyfill (idempotent). */
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
                    sseHandler.start(sseUrl);
                    return;
                }
                if (msg.startsWith("__PI_SSE_STOP__:")) {
                    String sseUrl = msg.substring("__PI_SSE_STOP__:".length());
                    LOG.comm("ProxiedWebView", "SSEHandler", "STOP", sseUrl);
                    sseHandler.stop(sseUrl);
                    return;
                }
                if (messageCallback != null) {
                    messageCallback.accept(msg);
                } else {
                    LOG.warn("ProxiedWebView", "Received JS message but no messageCallback set: " + msg);
                }
            });
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
            LOG.error("ProxiedWebView", "Failed to install JS bridge: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Read a text resource from the classpath. */
    private static String readResource(String name) {
        try (InputStream is = ProxiedWebView.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("ProxiedWebView", "Failed to read resource " + name + ": " + e.getMessage());
            return null;
        }
    }

    /** Inject the AutoHello script if enabled. */
    private void injectAutoHello() {
        if (!autoHello.isEnabled()) {
            LOG.info("ProxiedWebView", "AutoHello disabled – skipping injection");
            return;
        }
        String script = autoHello.script();
        if (!script.isEmpty()) {
            LOG.info("ProxiedWebView", "Injecting AutoHello script");
            webView.getEngine().executeScript(script);
        }
    }
}
