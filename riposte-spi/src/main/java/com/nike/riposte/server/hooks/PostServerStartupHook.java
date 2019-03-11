package com.nike.riposte.server.hooks;

import com.nike.riposte.server.config.ServerConfig;

import org.jetbrains.annotations.NotNull;

import io.netty.channel.Channel;

/**
 * Hook for post server startup events.
 */
@FunctionalInterface
public interface PostServerStartupHook {

    void executePostServerStartupHook(@NotNull ServerConfig serverConfig, @NotNull Channel channel);
}
