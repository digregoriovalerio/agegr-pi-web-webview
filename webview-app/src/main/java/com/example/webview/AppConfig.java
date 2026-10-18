package com.example.webview;

import com.example.webview.logging.AppLogger;

/**
 * Immutable application configuration - hardcoded constants.
 * No system properties, no runtime configuration.
 * Use the static constants directly in components.
 */
public final class AppConfig {

    /** Target origin (scheme://host:port). */
    public static final String TARGET_ORIGIN = "http://127.0.0.1:30141";

    /** Proxy bind host. */
    public static final String PROXY_HOST = "127.0.0.1";

    /** Buffer size for streaming pipe operations (bytes). */
    public static final int BUFFER_SIZE = 8192;

    /** User-Agent string injected by the proxy and SSE client. */
    public static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    /** SSE stream read timeout (ms). */
    public static final int SSE_READ_TIMEOUT = 300000;

    /** Connection timeout (ms). */
    public static final int CONNECT_TIMEOUT = 10000;

    /** Max request body size (bytes). */
    public static final int MAX_REQUEST_BODY_SIZE = 10 * 1024 * 1024; // 10 MB

    /** AutoHello enabled state. */
    public static final boolean AUTOHELLO_ENABLED = true;

    /** Log level. */
    public static final String LOG_LEVEL = "TRACE";

    private AppConfig() {
        AppLogger.INSTANCE.info("AppConfig", "Config: target=" + TARGET_ORIGIN
            + ", proxy=" + PROXY_HOST + ", buffer=" + BUFFER_SIZE
            + ", ua=" + USER_AGENT
            + ", sseTimeout=" + SSE_READ_TIMEOUT
            + ", connTimeout=" + CONNECT_TIMEOUT
            + ", maxBody=" + MAX_REQUEST_BODY_SIZE
            + ", autoHello=" + AUTOHELLO_ENABLED
            + ", logLevel=" + LOG_LEVEL);
    }
}