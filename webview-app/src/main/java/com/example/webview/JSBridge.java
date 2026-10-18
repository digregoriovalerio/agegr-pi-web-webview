package com.example.webview;

import com.example.webview.logging.AppLogger;

import java.util.function.Consumer;

/**
 * Bridge object exposed to JavaScript via {@link netscape.javascript.JSObject#setMember}.
 * Must be a public top-level (or static nested) class with public methods so that
 * JavaFX's JS reflection layer can access them.
 *
 * Communication logged:
 *   Page JS -> JSBridge.log(msg)       : console.log capture
 *   Page JS -> JSBridge.error(msg)     : console.error / window.onerror capture
 *   Page JS -> JSBridge.postMessage(msg): __PI_SSE_START__ / __PI_SSE_STOP__ / custom messages
 *   JSBridge -> ProxiedWebView (callback): message delivered to Java handler
 */
public class JSBridge {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private final Consumer<String> messageCallback;

    public JSBridge(Consumer<String> messageCallback) {
        this.messageCallback = messageCallback;
        LOG.debug("JSBridge", "Created with messageCallback=" + (messageCallback != null));
    }

    /** Called from JS: javaBridge.log(msg) — captures console.log output. */
    public void log(String msg) {
        LOG.comm("Page JS", "JSBridge", "LOG", msg);
    }

    /** Called from JS: javaBridge.error(msg) — captures console.error / window.onerror. */
    public void error(String msg) {
        LOG.comm("Page JS", "JSBridge", "ERROR", msg);
    }

    /**
     * Called from JS: javaBridge.postMessage(msg).
     * Used by the EventSource polyfill (__PI_SSE_START__ / __PI_SSE_STOP__)
     * and any custom page-to-Java messaging.
     */
    public void postMessage(String msg) {
        LOG.comm("Page JS", "JSBridge", "POST_MESSAGE", msg);
        if (messageCallback != null) {
            LOG.comm("JSBridge", "ProxiedWebView", "CALLBACK", msg);
            messageCallback.accept(msg);
        }
    }
}
