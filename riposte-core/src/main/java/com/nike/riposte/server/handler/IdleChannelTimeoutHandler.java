package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.HttpProcessingState;

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

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final String customHandlerIdForLogs;
    private final long idleTimeoutMillis;

    public IdleChannelTimeoutHandler(long idleTimeoutMillis, String customHandlerIdForLogs) {
        super(0, 0, (int) idleTimeoutMillis, TimeUnit.MILLISECONDS);
        this.customHandlerIdForLogs = customHandlerIdForLogs;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        logger.debug(
            "Closing server channel due to idle timeout. "
            + "custom_handler_id={}, idle_timeout_millis={}, worker_channel_being_closed={}",
            customHandlerIdForLogs, idleTimeoutMillis, ctx.channel().toString()
        );

        // Release any state if possible.
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state != null) {
            state.getRequestInfo().releaseAllResources();
            state.cleanStateForNewRequest();
        }

        // Close the channel.
        ctx.channel().close();

        super.channelIdle(ctx, evt);
    }
}
