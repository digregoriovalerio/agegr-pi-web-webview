package com.example.webview;

/**
 * Immutable application configuration - all tunable values in one place.
 *
 * <h2>Purpose</h2>
 * Centralizes every constant that components need so nothing is hardcoded
 * scattered across the codebase. Changing a proxy port, buffer size, or
 * User-Agent here updates every consumer automatically.
 *
 * <h2>Configuration constants</h2>
 * <table>
 *   <tr><th>Constant</th><th>Value</th><th>Purpose</th></tr>
 *   <tr><td>{@link #TARGET_ORIGIN}</td><td>{@link #TARGET_ORIGIN}</td>
 *       <td>Target origin (scheme://host:port) that the proxy forwards to</td></tr>
 *   <tr><td>{@link #PROXY_HOST}</td><td>{@link #PROXY_HOST}</td>
 *       <td>Host the local proxy binds to</td></tr>
 *   <tr><td>{@link #BUFFER_SIZE}</td><td>{@link #BUFFER_SIZE}</td>
 *       <td>Streaming pipe buffer size (bytes)</td></tr>
 *   <tr><td>{@link #USER_AGENT}</td><td>{@link #USER_AGENT}</td>
 *       <td>Browser-like User-Agent injected by proxy and SSE client</td></tr>
 *   <tr><td>{@link #SSE_READ_TIMEOUT}</td><td>{@link #SSE_READ_TIMEOUT}</td>
 *       <td>SSE stream idle read timeout (ms)</td></tr>
 *   <tr><td>{@link #CONNECT_TIMEOUT}</td><td>{@link #CONNECT_TIMEOUT}</td>
 *       <td>Target connection timeout (ms)</td></tr>
 *   <tr><td>{@link #MAX_REQUEST_BODY_SIZE}</td><td>{@link #MAX_REQUEST_BODY_SIZE}</td>
 *       <td>Max inbound request body size (bytes)</td></tr>
 *   <tr><td>{@link #SOCKET_TIMEOUT}</td><td>{@link #SOCKET_TIMEOUT}</td>
 *       <td>Client socket read timeout (ms)</td></tr>
 *   <tr><td>{@link #SSE_POOL_SIZE}</td><td>{@link #SSE_POOL_SIZE}</td>
 *       <td>SSE reader thread pool size</td></tr>
 *   <tr><td>{@link #AUTOHELLO_ENABLED}</td><td>{@link #AUTOHELLO_ENABLED}</td>
 *       <td>Whether AutoHello injects "Hello" on page load (dev-only)</td></tr>
 *   <tr><td>{@link #LOG_LEVEL}</td><td>{@link #LOG_LEVEL}</td>
 *       <td>Default log level</td></tr>
 * </table>
 *
 * <h2>How logging level is resolved</h2>
 * Defaults to WARN. The level can be changed at runtime via {@code AppLogger.INSTANCE.setLevel(...)}.
 *
 * <h2>Production housekeeping</h2>
 * <ul>
 *   <li>Set {@link #AUTOHELLO_ENABLED} to {@code true} to enable AutoHello</li>
 *   <li>Set {@link #LOG_LEVEL} to {@code "TRACE"} for verbose logging</li>
 *   <li>Review {@link #USER_AGENT} if the target server changes its browser sniffing</li>
 *   <li>Ensure {@link ProxyServer} header rewrites (Origin/Referer) still match the target origin</li>
 * </ul>
 *
 * <h3>No hardcoding rule</h3>
 * Do not hardcode {@code 127.0.0.1}, {@code 8192}, or User-Agent string literals
 * outside this class. Always use the {@link AppConfig} constants.
 */
public final class AppConfig {

    /** Target origin (scheme://host:port) that the proxy forwards to. */
    public static final String TARGET_ORIGIN = "http://127.0.0.1:30141";

    /** Host the local proxy binds to. */
    public static final String PROXY_HOST = "127.0.0.1";

    /** Streaming pipe buffer size (bytes). */
    public static final int BUFFER_SIZE = 8192;

    /** Browser-like User-Agent injected by proxy and SSE client. */
    public static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    /** SSE stream idle read timeout (ms). */
    public static final int SSE_READ_TIMEOUT = 300000;

    /** Target connection timeout (ms). */
    public static final int CONNECT_TIMEOUT = 10000;

    /** Max inbound request body size (bytes, 10 MB). */
    public static final int MAX_REQUEST_BODY_SIZE = 10 * 1024 * 1024;

    /** Client socket read timeout (ms). */
    public static final int SOCKET_TIMEOUT = 300000;

    /** SSE reader thread pool size. */
    public static final int SSE_POOL_SIZE = 4;

    /** Whether AutoHello injects "Hello" on page load (dev-only). */
    public static final boolean AUTOHELLO_ENABLED = false;

    /** Default log level. */
    public static final String LOG_LEVEL = "WARN";
}
