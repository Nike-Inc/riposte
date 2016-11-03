package com.nike.riposte.server.hooks;

import com.nike.riposte.server.config.ServerConfig;

import io.netty.channel.Channel;

/**
 * Hook for server shutdown events.
 */
public interface ServerShutdownHook {

    void executeServerShutdownHook(ServerConfig serverConfig, Channel channel);
}
