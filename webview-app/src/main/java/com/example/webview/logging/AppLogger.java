package com.example.webview.logging;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Centralized logging facade for the WebViewApp architecture.
 *
 * Provides five levels (from most to least verbose):
 *  <ul>
 *    <li>{@link #TRACE}   – every byte, every internal step (maximum verbosity)</li>
 *    <li>{@link #DEBUG}   – debug-level details for development</li>
 *    <li>{@link #INFO}    – normal operational events (default)</li>
 *    <li>{@link #WARN}    – unexpected but non-fatal conditions</li>
 *    <li>{@link #ERROR}   – errors and exceptions</li>
 *  </ul>
 *
 * The initial level is read from the system property {@code pi.log.level}
 * (accepted values: TRACE, DEBUG, INFO, WARN, ERROR). Defaults to DEBUG when
 * launched via {@code run.ps1}.
 *
 * All output goes to {@link System.out} with a compact timestamp prefix, so
 * logs are visible regardless of the JDK's {@code java.util.logging}
 * configuration. Directional communication between any two entities is logged
 * at DEBUG or TRACE so it can be enabled when needed and silenced in production.
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

    /** Tracks whether each source has logged at least once (for dedup of startup banner). */
    private final ConcurrentMap<String, Boolean> loggedSources = new ConcurrentHashMap<>();

    AppLogger() {
        this.currentLevel = readLevelFromSystemProperty();
        System.out.println("[AppLogger] Logging initialized at level=" + currentLevel);
    }

    /** Read the initial log level from the {@code pi.log.level} system property. */
    private static Level readLevelFromSystemProperty() {
        String prop = System.getProperty("pi.log.level", "INFO");
        try {
            return Level.valueOf(prop.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Level.INFO;
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

    /** Set the global log level. Only messages at this level or above are emitted. */
    public void setLevel(Level level) {
        this.currentLevel = level;
    }

    /** Return the current log level. */
    public Level getLevel() {
        return currentLevel;
    }

    private boolean enabled(Level level) {
        return level.value() >= currentLevel.value();
    }

    private void emit(Level level, String source, String message) {
        if (!enabled(level)) return;
        String tag = "[" + source + "]";
        System.out.println("[" + level.name() + "] " + padRight(tag, 24) + " " + message);
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
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

    /** Log a directional communication between two entities (DEBUG level). */
    public void comm(String from, String to, String direction, String message) {
        if (!enabled(Level.DEBUG)) return;
        String tag = "[" + from + " -> " + to + "]";
        System.out.println("[DEBUG] " + padRight(tag, 32) + " [" + direction + "] " + message);
    }

    /** Log a directional communication at TRACE level. */
    public void commTrace(String from, String to, String direction, String message) {
        if (!enabled(Level.TRACE)) return;
        String tag = "[" + from + " -> " + to + "]";
        System.out.println("[TRACE] " + padRight(tag, 32) + " [" + direction + "] " + message);
    }
}
