package com.nike.riposte.server.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * Handler that {@link io.netty.channel.Channel#close()}s idle channels after the specified number of milliseconds. This
 * should be added as one of the first handlers in the pipeline, but it should only be added in between requests so
 * that it doesn't squash long-running-but-valid requests.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class IdleChannelTimeoutHandler extends IdleStateHandler {

    private static final Logger logger = LoggerFactory.getLogger(IdleChannelTimeoutHandler.class);

    protected final String customHandlerIdForLogs;
    protected final long idleTimeoutMillis;

    public IdleChannelTimeoutHandler(long idleTimeoutMillis, String customHandlerIdForLogs) {
        super(0, 0, (int) idleTimeoutMillis, TimeUnit.MILLISECONDS);
        this.customHandlerIdForLogs = customHandlerIdForLogs;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        channelIdleTriggered(ctx, evt);

        // Close the channel. ChannelPipelineFinalizerHandler.channelInactive(...) will ensure that content is released.
        ctx.channel().close();

        super.channelIdle(ctx, evt);
    }

    /**
     * Helper method that is called when the idle channel event is triggered, but before anything else happens
     * in {@link #channelIdle(ChannelHandlerContext, IdleStateEvent)}. This is usually used to add an appropriate
     * log message.
     */
    protected void channelIdleTriggered(ChannelHandlerContext ctx, IdleStateEvent evt) {
        logger.debug(
            "Closing server channel due to idle timeout. "
            + "custom_handler_id={}, idle_timeout_millis={}, worker_channel_being_closed={}",
            customHandlerIdForLogs, idleTimeoutMillis, ctx.channel().toString()
        );
    }
}
