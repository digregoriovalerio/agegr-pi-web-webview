package com.example.webview;

/**
 * Centralized application configuration.
 *
 * Holds shared constants and defaults so that multiple components
 * (ProxyServer, SSEHandler, ProxiedWebView, etc.) use the same values.
 *
 * All values can be overridden via system properties at startup.
 */
public final class AppConfig {

    private AppConfig() {
        // utility class
    }

    /** Default target host. */
    public static final String DEFAULT_TARGET_HOST = "127.0.0.1";

    /** Default target port. */
    public static final int DEFAULT_TARGET_PORT = 30141;

    /** Default proxy bind host. */
    public static final String DEFAULT_PROXY_HOST = "127.0.0.1";

    /** Default buffer size for streaming pipe operations (bytes). */
    public static final int DEFAULT_BUFFER_SIZE = 8192;

    /** Default User-Agent string injected by the proxy and SSE client. */
    public static final String DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** Default SSE stream read timeout (ms). */
    public static final int DEFAULT_SSE_READ_TIMEOUT = 300000;

    /** Default connection timeout (ms). */
    public static final int DEFAULT_CONNECT_TIMEOUT = 10000;

    /**
     * Returns the User-Agent string, overridable via {@code pi.user.agent}.
     */
    public static String getUserAgent() {
        String ua = System.getProperty("pi.user.agent");
        return (ua != null && !ua.isEmpty()) ? ua : DEFAULT_USER_AGENT;
    }

    /**
     * Returns the buffer size for streaming, overridable via {@code pi.buffer.size}.
     */
    public static int getBufferSize() {
        String s = System.getProperty("pi.buffer.size");
        if (s != null && !s.isEmpty()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_BUFFER_SIZE;
    }

    /**
     * Returns the proxy bind host, overridable via {@code pi.proxy.host}.
     */
    public static String getProxyHost() {
        String h = System.getProperty("pi.proxy.host");
        return (h != null && !h.isEmpty()) ? h : DEFAULT_PROXY_HOST;
    }

    /**
     * Returns the target host, overridable via {@code pi.target.host}.
     */
    public static String getTargetHost() {
        String h = System.getProperty("pi.target.host");
        return (h != null && !h.isEmpty()) ? h : DEFAULT_TARGET_HOST;
    }

    /**
     * Returns the target port, overridable via {@code pi.target.port}.
     */
    public static int getTargetPort() {
        String s = System.getProperty("pi.target.port");
        if (s != null && !s.isEmpty()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_TARGET_PORT;
    }
}
