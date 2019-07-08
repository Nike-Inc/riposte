package com.nike.riposte.util;

import org.jetbrains.annotations.NotNull;

public class MatcherUtil {

    // Intentionally protected - use the static methods
    protected MatcherUtil() { /* do nothing */ }

    public static String stripEndSlash(@NotNull String path) {
        if (path.endsWith("/")) {
            if (path.length() == 1) {
                // The path is only a slash. i.e. it's the root path. In that case we don't want to strip the slash.
                return path;
            }

            return path.substring(0, path.length() - 1);
        } else {
            return path;
        }
    }
}
