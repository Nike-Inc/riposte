package com.nike.riposte.server.hooks;

import com.nike.riposte.server.config.ServerConfig;

import org.jetbrains.annotations.NotNull;

import io.netty.channel.Channel;

/**
 * Hook for server shutdown events.
 */
@FunctionalInterface
public interface ServerShutdownHook {

    void executeServerShutdownHook(@NotNull ServerConfig serverConfig, @NotNull Channel channel);
}
