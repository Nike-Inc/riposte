package com.nike.riposte.server.handler;

import com.nike.riposte.server.error.exception.TooManyOpenChannelsException;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;

import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * This handler keeps track of the number of open channels in the server. If it detects a new request coming in when
 * there are too many open channels then it will throw a {@link TooManyOpenChannelsException}, which should ultimately
 * cause the channel to be closed after sending an appropriate message to the client. See {@link
 * com.nike.riposte.server.config.ServerConfig#maxOpenIncomingServerChannels()} for details on how this is used by the
 * server.
 * <p/>
 * This handler should come as soon after {@link RequestInfoSetterHandler} as possible in the pipeline.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class OpenChannelLimitHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    /**
     * Attr key for a channel attribute that indicates there are too many open connections and this channel should be
     * closed at the earliest opportunity.
     */
    public static final AttributeKey<Integer> TOO_MANY_OPEN_CONNECTIONS_THIS_CHANNEL_SHOULD_CLOSE =
        AttributeKey.valueOf("TOO_MANY_OPEN_CONNECTIONS_THIS_CHANNEL_SHOULD_CLOSE");

    protected final ChannelGroup openChannelsGroup;
    protected final int maxOpenChannelsThreshold;

    public OpenChannelLimitHandler(ChannelGroup openChannelsGroup, int maxOpenChannelsThreshold) {
        if (openChannelsGroup == null)
            throw new IllegalArgumentException("openChannelsGroup cannot be null");

        if (maxOpenChannelsThreshold < 1)
            throw new IllegalArgumentException("maxOpenChannelsThreshold must be at least 1");

        this.openChannelsGroup = openChannelsGroup;
        this.maxOpenChannelsThreshold = maxOpenChannelsThreshold;
    }

    @Override
    public PipelineContinuationBehavior doChannelActive(ChannelHandlerContext ctx) throws Exception {
        // New channel opening. See if we have too many open channels.
        int actualOpenChannelsCount = openChannelsGroup.size();
        if (actualOpenChannelsCount >= maxOpenChannelsThreshold) {
            Channel channel = ctx.channel();

            // Mark this channel as needing to be closed.
            ctx.channel().attr(TOO_MANY_OPEN_CONNECTIONS_THIS_CHANNEL_SHOULD_CLOSE).set(actualOpenChannelsCount);

            // Schedule a double-check event to make sure the channel gets closed.
            ScheduledFuture doubleCheckScheduledFuture = ctx.channel().eventLoop().schedule(() -> {
                if (channel.isOpen())
                    channel.close();
            }, 100, TimeUnit.MILLISECONDS);

            // Add a channel close future listener to cancel the double-check scheduled event immediately if the channel
            //      is closed quickly. Even though the double-check event will execute in 100 milliseconds that's 100
            //      milliseconds of potential garbage accumulating when it shouldn't. Could be a lot for a high traffic
            //      server (which this likely is if the open channels limit is being hit).
            channel.closeFuture().addListener(future -> {
                if (!doubleCheckScheduledFuture.isDone())
                    doubleCheckScheduledFuture.cancel(false);
            });
        }
        else {
            // Not at the threshold. Add this channel to the open channel group.
            openChannelsGroup.add(ctx.channel());
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            // First chunk in the request. See if this channel has been marked for death.
            Integer actualNumOpenChannels =
                ctx.channel().attr(TOO_MANY_OPEN_CONNECTIONS_THIS_CHANNEL_SHOULD_CLOSE).get();
            if (actualNumOpenChannels != null && actualNumOpenChannels >= maxOpenChannelsThreshold)
                throw new TooManyOpenChannelsException(actualNumOpenChannels, maxOpenChannelsThreshold);
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // This class does not log, and nothing that happens in this class should cause logging to happen elsewhere.
        //      Therefore we should never bother with linking/unlinking tracing info to save on the extra processing.
        return false;
    }
}
