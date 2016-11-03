package com.nike.riposte.server.hooks;

import io.netty.bootstrap.ServerBootstrap;

/**
 * Hook for pre server startup events.
 */
public interface PreServerStartupHook {

    void executePreServerStartupHook(ServerBootstrap bootstrap);
}
