package com.nike.riposte.server.handler;

import com.nike.riposte.server.error.exception.IncompleteHttpCallTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Extension of {@link IdleStateHandler} intended to catch when an HTTP call is incomplete - i.e. when
 * a caller sends some of the data required for a full HTTP request but does not finish it. This could happen because
 * of a really slow client, or a bad network connection that keeps the connection open but doesn't send data, or a
 * broken client that doesn't send requests that conform to the HTTP spec. In any case we want to catch these cases
 * and send back an error response rather than waiting indefinitely for data that isn't going to come, consuming
 * resources the entire time. Without this handler these bad calls would essentially become a memory leak.
 *
 * <p>NOTE: This is still an *idle* timeout handler, so if the caller is still sending data (even if it's a really slow
 * trickle) it will not trigger this handler's timeout logic. It will only timeout and close the connection if no
 * incoming or outgoing data has passed through the channel *at all* in {@link #idleTimeoutMillis} milliseconds.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class IncompleteHttpCallTimeoutHandler extends IdleStateHandler {

    private static final Logger logger = LoggerFactory.getLogger(IncompleteHttpCallTimeoutHandler.class);
    protected final long idleTimeoutMillis;
    protected boolean alreadyTriggeredException = false;

    public IncompleteHttpCallTimeoutHandler(long idleTimeoutMillis) {
        super(0, 0, (int) idleTimeoutMillis, TimeUnit.MILLISECONDS);
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        if (alreadyTriggeredException) {
            runnableWithTracingAndMdc(
                () -> logger.error(
                    "IncompleteHttpCallTimeoutHandler triggered multiple times - this should not happen."
                ),
                ctx
            ).run();
            return;
        }

        channelIdleTriggered(ctx, evt);

        alreadyTriggeredException = true;
        throw new IncompleteHttpCallTimeoutException(idleTimeoutMillis);
    }

    protected void channelIdleTriggered(ChannelHandlerContext ctx, IdleStateEvent evt) {
        runnableWithTracingAndMdc(
            () -> logger.warn(
                "Too much time passed without receiving any HTTP chunks from the caller after starting a request. The "
                + "HTTP request is incomplete and invalid, and the caller doesn't seem to be sending any more data, "
                + "therefore an error response will be returned and this connection closed. This could be due to a "
                + "content-length header that claims the payload size is larger than what was actually sent, or a "
                + "chunked transfer-encoding request where the final chunk wasn't sent, or any number of other reasons. "
                + "incomplete_http_call_timeout_millis={}, worker_channel_being_closed={}",
                idleTimeoutMillis, ctx.channel().toString()
            ),
            ctx
        ).run();
    }
}
