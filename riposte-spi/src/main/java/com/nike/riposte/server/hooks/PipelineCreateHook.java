package com.nike.riposte.server.hooks;

import io.netty.channel.ChannelPipeline;

/**
 * Hook for pipeline create events - allows to modify the pipeline before the channel is initialized.
 */
public interface PipelineCreateHook {

    void executePipelineCreateHook(ChannelPipeline pipeline);
}
