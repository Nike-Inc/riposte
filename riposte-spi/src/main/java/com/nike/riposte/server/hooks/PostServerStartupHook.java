package com.nike.riposte.server.hooks;

import com.nike.riposte.server.config.ServerConfig;

import io.netty.channel.Channel;

/**
 * Hook for post server startup events.
 */
public interface PostServerStartupHook {

    void executePostServerStartupHook(ServerConfig serverConfig, Channel channel);
}
