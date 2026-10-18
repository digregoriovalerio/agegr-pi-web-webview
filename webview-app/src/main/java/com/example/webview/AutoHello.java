package com.example.webview;

import com.example.webview.logging.AppLogger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * AutoHello is a development-only entity that automatically types "Hello" into
 * the chat input and submits it when the page loads.
 *
 * It can be disabled in production by setting the system property
 * {@code pi.autohello.enabled} to {@code false} (defaults to {@code true}).
 *
 * Responsibilities:
 *   - Loads the JavaScript IIFE from autoHello.js that finds the chat input,
 *     sets "Hello", and dispatches Enter to submit.
 *   - Logs all interactions with the page DOM via the JS bridge.
 *
 * Interface:
 *   boolean isEnabled()
 *   String script()          // JS source to inject
 */
public class AutoHello {

    private static final AppLogger LOG = AppLogger.INSTANCE;

    private final boolean enabled;

    public AutoHello() {
        String prop = System.getProperty("pi.autohello.enabled", "true");
        this.enabled = Boolean.parseBoolean(prop);
        LOG.info("AutoHello", "Initialized: enabled=" + enabled
            + " (system property pi.autohello.enabled=" + prop + ")");
    }

    /** Whether auto-hello is active. Can be toggled via system property at startup. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the JavaScript source to inject into the page, or an empty string
     * if auto-hello is disabled. The script is loaded from {@code autoHello.js}
     * on the classpath and is guarded by {@code __piAutoHelloDone}
     * so it only runs once per page load.
     */
    public String script() {
        if (!enabled) {
            LOG.debug("AutoHello", "Disabled – returning empty script");
            return "";
        }

        LOG.debug("AutoHello", "Loading auto-hello script from classpath resource");
        String script = readResource("autoHello.js");
        if (script == null || script.isEmpty()) {
            LOG.warn("AutoHello", "autoHello.js resource not found or empty");
            return "";
        }
        return script;
    }

    private static String readResource(String name) {
        try (InputStream is = AutoHello.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("AutoHello", "Failed to read resource " + name + ": " + e.getMessage());
            return null;
        }
    }
}
