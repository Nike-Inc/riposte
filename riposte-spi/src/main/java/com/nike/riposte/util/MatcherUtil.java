package com.nike.riposte.util;

import org.jetbrains.annotations.NotNull;

@SuppressWarnings("WeakerAccess")
public class MatcherUtil {

    // Intentionally protected - use the static methods
    protected MatcherUtil() { /* do nothing */ }

    public static String stripEndSlash(@NotNull String path) {
        if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        } else {
            return path;
        }
    }
}
