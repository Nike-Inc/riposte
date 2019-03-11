package com.nike.riposte.server.hooks;

import org.jetbrains.annotations.NotNull;

import io.netty.bootstrap.ServerBootstrap;

/**
 * Hook for pre server startup events.
 */
@FunctionalInterface
public interface PreServerStartupHook {

    void executePreServerStartupHook(@NotNull ServerBootstrap bootstrap);
}
