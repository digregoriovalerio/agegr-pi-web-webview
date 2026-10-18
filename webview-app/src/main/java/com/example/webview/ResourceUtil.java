package com.example.webview;

import com.example.webview.logging.AppLogger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for loading classpath resources.
 */
public final class ResourceUtil {

    private ResourceUtil() {
        // utility class
    }

    private static final AppLogger LOG = AppLogger.INSTANCE;

    /**
     * Reads a text resource from the classpath.
     * Tries the source class's classloader first, then falls back to context classloader.
     *
     * @param name resource name (e.g., "bridge.js")
     * @param sourceClass class to use for classloader resolution (typically the caller)
     * @return resource content as UTF-8 string, or null if not found
     */
    public static String readResource(String name, Class<?> sourceClass) {
        // Try source class's classloader first
        ClassLoader cl = sourceClass.getClassLoader();
        String result = readResourceWithLoader(name, cl);
        if (result != null) {
            return result;
        }

        // Fallback to context classloader (important for modular/classloader isolation scenarios)
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