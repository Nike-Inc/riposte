package com.nike.riposte.server.hooks;

import org.jetbrains.annotations.NotNull;

import io.netty.channel.ChannelPipeline;

/**
 * Hook for pipeline create events - allows to modify the pipeline before the channel is initialized.
 */
@FunctionalInterface
public interface PipelineCreateHook {

    void executePipelineCreateHook(@NotNull ChannelPipeline pipeline);
}
