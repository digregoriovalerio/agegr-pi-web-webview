package com.example.webview;

import com.example.webview.logging.AppLogger;

/**
 * AutoHello is a development-only entity that automatically types "Hello" into
 * the chat input and submits it when the page loads.
 *
 * Responsibilities:
 *   - Loads the JavaScript IIFE from autoHello.js that finds the chat input,
 *     sets "Hello", and dispatches Enter to submit.
 *   - Logs all interactions with the page DOM via the JS bridge.
 *
 * Interface:
 *   String script()          // JS source to inject
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