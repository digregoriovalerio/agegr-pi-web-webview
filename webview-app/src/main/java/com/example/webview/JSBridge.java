package com.example.webview;

import com.example.webview.logging.AppLogger;

import java.util.function.Consumer;

/**
 * Bridge object exposed to JavaScript via
 * {@link netscape.javascript.JSObject#setMember}.
 *
 * <h2>Purpose</h2>
 * Provides a Java object that JavaScript in the WebView page can call as
 * {@code window.javaBridge.*}. It captures console output and routes
 * page-to-Java messages (SSE start/stop and custom messages) to the
 * {@link ProxiedWebView}.
 *
 * <h2>JavaScript contract</h2>
 * The page calls three methods:
 * <table>
 *   <tr><th>JS call</th><th>Purpose</th></tr>
 *   <tr><td>{@code javaBridge.log(msg)}</td><td>Captures {@code console.log} output</td></tr>
 *   <tr><td>{@code javaBridge.error(msg)}</td><td>Captures {@code console.error} / {@code window.onerror}</td></tr>
 *   <tr><td>{@code javaBridge.postMessage(msg)}</td><td>Routes SSE start/stop and custom messages</td></tr>
 * </table>
 *
 * <h2>Message routing</h2>
 * Messages starting with {@code __PI_SSE_START__:} or
 * {@code __PI_SSE_STOP__:} are SSE protocol messages handled internally by
 * {@link ProxiedWebView#installJSBridge()} — they start or stop SSE streams
 * via {@link SSEHandler}. All other messages are forwarded to the external
 * {@link #messageCallback} (owned by {@link WebViewApp}).
 *
 * <h2>Lifecycle warning</h2>
 * This class must be a public top-level class with public methods so that
 * JavaFX's JS reflection layer can access it via {@code setMember}.
 * Critically, JavaFX keeps only a <b>weak reference</b> to objects passed to
 * {@code setMember}. The {@link ProxiedWebView} holds a strong reference to
 * each installed {@code JSBridge} to prevent garbage collection from
 * silently breaking all {@code javaBridge.*} calls from JavaScript.
 *
 * @see ProxiedWebView
 * @see SSEHandler
 * @see AppLogger
 */
public class JSBridge {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private static final String SSE_START_PREFIX = "__PI_SSE_START__:";
    private static final String SSE_STOP_PREFIX = "__PI_SSE_STOP__:";

    private final Consumer<String> messageCallback;

    /**
     * Creates the bridge with a callback for non-SSE messages.
     *
     * @param messageCallback callback for page-to-Java messages, may be null
     */
    public JSBridge(Consumer<String> messageCallback) {
        this.messageCallback = messageCallback;
        LOG.debug("JSBridge", "Created with messageCallback=" + (messageCallback != null));
    }

    /**
     * Called from JS: {@code javaBridge.log(msg)}. Captures console.log output.
     *
     * @param msg the log message from JavaScript
     */
    public void log(String msg) {
        LOG.comm("Page JS", "JSBridge", "LOG", msg);
    }

    /**
     * Called from JS: {@code javaBridge.error(msg)}. Captures console.error
     * and {@code window.onerror} output.
     *
     * @param msg the error message from JavaScript
     */
    public void error(String msg) {
        LOG.comm("Page JS", "JSBridge", "ERROR", msg);
    }

    /**
     * Called from JS: {@code javaBridge.postMessage(msg)}.
     * Used by the EventSource polyfill
     * ({@code __PI_SSE_START__} / {@code __PI_SSE_STOP__}) and any custom
     * page-to-Java messaging.
     *
     * @param msg the message posted from JavaScript
     */
    public void postMessage(String msg) {
        boolean isSseProtocol = msg.startsWith(SSE_START_PREFIX) || msg.startsWith(SSE_STOP_PREFIX);
        if (isSseProtocol) {
            LOG.commTrace("Page JS", "JSBridge", "POST_MESSAGE", msg);
        } else {
            LOG.comm("Page JS", "JSBridge", "POST_MESSAGE", msg);
        }

        if (messageCallback != null) {
            if (isSseProtocol) {
                LOG.commTrace("JSBridge", "ProxiedWebView", "CALLBACK", msg);
            } else {
                LOG.comm("JSBridge", "ProxiedWebView", "CALLBACK", msg);
            }
            messageCallback.accept(msg);
        }
    }
}
