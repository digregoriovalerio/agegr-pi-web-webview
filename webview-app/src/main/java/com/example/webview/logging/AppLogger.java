package com.example.webview.logging;

import com.example.webview.AppConfig;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Centralized level-based logging facade for the WebViewApp architecture.
 *
 * <h2>Why not java.util.logging?</h2>
 * The JDK's {@code java.util.logging} is overridden by JDK's
 * {@code logging.properties}, which causes logs to disappear silently.
 * This class writes directly to {@link System.out} with a compact timestamp
 * prefix, so logs are visible regardless of the JDK's logging configuration.
 *
 * <h2>Log levels</h2>
 * Five levels, from most to least verbose:
 * <table>
 *   <tr><th>Level</th><th>Ordinal</th><th>Purpose</th></tr>
 *   <tr><td>TRACE</td><td>4</td><td>Every byte, every internal step (maximum verbosity)</td></tr>
 *   <tr><td>DEBUG</td><td>3</td><td>Debug-level details for development</td></tr>
 *   <tr><td>INFO</td><td>2</td><td>Normal operational events</td></tr>
 *   <tr><td>WARN</td><td>1</td><td>Unexpected but non-fatal conditions</td></tr>
 *   <tr><td>ERROR</td><td>0</td><td>Errors and exceptions</td></tr>
 * </table>
 *
 * A message is emitted if its level ordinal is less than or equal to the
 * configured level's ordinal (i.e., lower ordinal = higher severity).
 *
 * <h2>Configuration</h2>
 * The initial level is read from AppConfig.LOG_LEVEL (accepted values: TRACE, DEBUG, INFO, WARN, ERROR).
 * Defaults to WARN when launched via {@code run.ps1}. The level can be changed at runtime via
 * {@link #setLevel(Level)}.
 *
 * <h2>Directional communication logging</h2>
 * Every directional edge between components is instrumented:
 * <table>
 *   <tr><th>Method</th><th>Level</th><th>Purpose</th></tr>
 *   <tr><td>{@link #comm(String, String, String, String)}</td><td>DEBUG</td><td>Who talks to whom (component communication)</td></tr>
 *   <tr><td>{@link #commTrace(String, String, String, String)}</td><td>TRACE</td><td>Byte-level detail for troubleshooting</td></tr>
 * </table>
 *
 * To add a new communication edge, add a {@code comm()} call at the sender
 * side with {@code (source, destination, "DIRECTION", message)}.
 *
 * <h2>Output format</h2>
 * All output goes to {@link System.out} with a compact prefix:
 * {@code [LEVEL] [source        ] message}
 *
 * @see AppConfig
 */
public enum AppLogger {

    INSTANCE;

    private volatile Level currentLevel;

    /** Minimum level for a message to be emitted. */
    public enum Level {
        TRACE(4), DEBUG(3), INFO(2), WARN(1), ERROR(0);

        private final int ordinal;
        Level(int ordinal) { this.ordinal = ordinal; }
        public int value() { return ordinal; }
    }

    /** Maximum length for comm/commTrace messages before truncation. */
    private static final int MAX_COMM_LENGTH = 500;

    AppLogger() {
        try {
            this.currentLevel = Level.valueOf(AppConfig.LOG_LEVEL.toUpperCase());
            this.info("AppLogger", "Logging initialized at level=" + currentLevel.name());
        } catch (IllegalArgumentException e) {
            this.currentLevel = Level.WARN;
            this.warn("AppLogger", "Logging defaulted at level=" + currentLevel.name());
        }
    }

    /** Map a friendly level name to a {@link Level}. */
    public static Level parseLevel(String name) {
        try {
            return Level.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Level.INFO;
        }
    }

    /** Sets the global log level. Only messages at this level or above are emitted. */
    public void setLevel(Level level) {
        this.currentLevel = level;
    }

    /** Returns the current log level. */
    public Level getLevel() {
        return currentLevel;
    }

    private boolean enabled(Level level) {
        return level.value() <= currentLevel.value();
    }

    private void emit(Level level, String source, String message) {
        if (!enabled(level)) return;
        String tag = "[" + source + "]";
        System.out.println("[" + level.name() + "] " + padRight(tag, 24) + " " + message);
    }

    private static String padRight(String s, int width) {
        return String.format("%-" + width + "s", s);
    }

    /** Truncates a message for comm/commTrace logging. */
    private static String truncate(String message) {
        if (message.length() <= MAX_COMM_LENGTH) return message;
        return message.substring(0, MAX_COMM_LENGTH) + "... [" + (message.length() - MAX_COMM_LENGTH) + " chars truncated]";
    }

    /** Log at TRACE level. */
    public void trace(String source, String message) {
        emit(Level.TRACE, source, message);
    }

    /** Log at DEBUG level. */
    public void debug(String source, String message) {
        emit(Level.DEBUG, source, message);
    }

    /** Log at INFO level. */
    public void info(String source, String message) {
        emit(Level.INFO, source, message);
    }

    /** Log at WARN level. */
    public void warn(String source, String message) {
        emit(Level.WARN, source, message);
    }

    /** Log at ERROR level. */
    public void error(String source, String message) {
        emit(Level.ERROR, source, message);
    }

    /** Log at ERROR level, including the stack trace of the given throwable. */
    public void error(String source, String message, Throwable t) {
        emit(Level.ERROR, source, message + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        for (String line : sw.toString().split("\r?\n")) {
            emit(Level.ERROR, source, "    " + line);
        }
    }

    /**
     * Logs a directional communication between two entities at DEBUG level.
     *
     * @param from        source entity name
     * @param to          destination entity name
     * @param direction   description of the communication (e.g. "REQUEST", "RESPONSE")
     * @param message     the message content (truncated if too long)
     */
    public void comm(String from, String to, String direction, String message) {
        if (!enabled(Level.DEBUG)) return;
        String tag = "[" + from + " -> " + to + "]";
        System.out.println("[DEBUG] " + padRight(tag, 32) + " [" + direction + "] " + truncate(message));
    }

    /**
     * Logs a directional communication between two entities at TRACE level.
     *
     * @param from        source entity name
     * @param to          destination entity name
     * @param direction   description of the communication
     * @param message     the message content (truncated if too long)
     */
    public void commTrace(String from, String to, String direction, String message) {
        if (!enabled(Level.TRACE)) return;
        String tag = "[" + from + " -> " + to + "]";
        System.out.println("[TRACE] " + padRight(tag, 32) + " [" + direction + "] " + truncate(message));
    }
}
