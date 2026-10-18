package com.example.webview;

import com.example.webview.logging.AppLogger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for loading text resources from the classpath.
 *
 * <h2>Purpose</h2>
 * Loads JavaScript resources (e.g. {@code bridge.js}, {@code autoHello.js})
 * from the classpath at runtime. Tries the source class's classloader first,
 * then falls back to the context classloader (important for modular or
 * isolated classloader scenarios).
 *
 * <h2>Why not inline JS strings?</h2>
 * PowerShell corrupts Java text blocks ({@code """}) — multi-line JS strings
 * in Java files break compilation. All JavaScript must live in
 * {@code src/main/resources/*.js} and be loaded via {@link #readResource}.
 *
 * @see AppLogger
 */
public final class ResourceUtil {

    private ResourceUtil() {
        // utility class
    }

    private static final AppLogger LOG = AppLogger.INSTANCE;

    /**
     * Reads a text resource from the classpath.
     *
     * @param name        resource name (e.g. {@code bridge.js})
     * @param sourceClass class to use for classloader resolution (typically the caller)
     * @return resource content as UTF-8 string, or {@code null} if not found
     */
    public static String readResource(String name, Class<?> sourceClass) {
        // Try source class's classloader first
        ClassLoader cl = sourceClass.getClassLoader();
        String result = readResourceWithLoader(name, cl);
        if (result != null) {
            return result;
        }

        // Fallback to context classloader
        cl = Thread.currentThread().getContextClassLoader();
        if (cl != sourceClass.getClassLoader()) {
            result = readResourceWithLoader(name, cl);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    private static String readResourceWithLoader(String name, ClassLoader cl) {
        try (InputStream is = cl.getResourceAsStream(name)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("ResourceUtil", "Failed to read resource " + name + " with classloader " + cl + ": " + e.getMessage());
            return null;
        }
    }
}
