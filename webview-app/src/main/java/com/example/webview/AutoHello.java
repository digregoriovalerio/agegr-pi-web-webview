package com.example.webview;

import com.example.webview.logging.AppLogger;

/**
 * AutoHello is a development-only entity that automatically types "Hello" into
 * the chat input and submits it when the page loads.
 *
 * <h2>Purpose</h2>
 * Provides an end-to-end smoke test of the Hello → reply flow without manual
 * user interaction. On page load it injects a JavaScript IIFE
 * ({@code autoHello.js}) that:
 * <ol>
 *   <li>Finds the chat input element (tries multiple selectors)</li>
 *   <li>Sets its value to "Hello"</li>
 *   <li>Dispatches Enter key events to submit</li>
 *   <li>Verifies the textarea was cleared (indicating successful submit)</li>
 * </ol>
 *
 * <h2>Lifecycle</h2>
 * The script is guarded by {@code window.__piAutoHelloDone} so it only runs
 * once per page load. It retries finding the input up to 30 times at
 * 500ms intervals, giving the React page time to hydrate.
 *
 * <h2>Production housekeeping</h2>
 * Set {@link AppConfig#AUTOHELLO_ENABLED} to {@code false} to remove
 * AutoHello entirely. It is not needed for manual use — the user can type
 * and submit messages through the page's own UI.
 *
 * <h2>Logging</h2>
 * All AutoHello actions are logged via {@code javaBridge.log("[autoHello] ...)"}
 * which flows through {@link JSBridge#log(String)} to {@link AppLogger}.
 * If the JSBridge has been garbage-collected (see {@link ProxiedWebView}
 * for details), these logs are silently swallowed.
 *
 * @see AppConfig
 * @see AppLogger
 * @see ResourceUtil
 */
public class AutoHello {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    public AutoHello() {
        LOG.info("AutoHello", "Initialized");
    }

    /**
     * Returns the JavaScript source to inject into the page.
     * The script is loaded from {@code autoHello.js} on the classpath
     * and is guarded by {@code __piAutoHelloDone} so it only runs once per page load.
     *
     * @return the AutoHello JavaScript source, or empty string if not found
     */
    public String script() {
        LOG.debug("AutoHello", "Loading auto-hello script from classpath resource");
        String script = ResourceUtil.readResource("autoHello.js", AutoHello.class);
        if (script == null || script.isEmpty()) {
            LOG.warn("AutoHello", "autoHello.js resource not found or empty");
            return "";
        }
        return script;
    }
}
