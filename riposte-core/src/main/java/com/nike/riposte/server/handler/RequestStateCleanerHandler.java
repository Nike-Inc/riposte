package com.nike.riposte.server.handler;

import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.ChannelAttributes.ProcessingStateClassAndKeyPair;
import com.nike.riposte.server.channelpipeline.HttpChannelInitializer;
import com.nike.riposte.server.http.ProcessingState;
import com.nike.riposte.server.metrics.ServerMetricsEvent;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import static com.nike.riposte.server.channelpipeline.ChannelAttributes.PROCESSING_STATE_ATTRIBUTE_KEYS;

/**
 * Handler that makes sure the channel has a clean instance of {@link com.nike.riposte.server.http.HttpProcessingState}
 * for every new {@link HttpRequest}.
 * <p/>
 * This should be the first handler in a pipeline following {@link io.netty.handler.codec.http.HttpRequestDecoder}.
 *
 * @author Nic Munroe
 */
public class RequestStateCleanerHandler extends ChannelInboundHandlerAdapter {

    private final MetricsListener metricsListener;

    public RequestStateCleanerHandler(MetricsListener metricsListener) {
        this.metricsListener = metricsListener;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {

            // New request incoming - setup/clear *all* state objects for new requests
            for (ProcessingStateClassAndKeyPair<? extends ProcessingState> stateClassAndKeyPair : PROCESSING_STATE_ATTRIBUTE_KEYS) {
                // See if we have an existing state object for this channel for the given state type.
                @SuppressWarnings("unchecked")
                AttributeKey<ProcessingState> attrKey = (AttributeKey<ProcessingState>) stateClassAndKeyPair.getRight();
                Attribute<ProcessingState> processingStateAttr = ctx.channel().attr(attrKey);
                ProcessingState processingState = processingStateAttr.get();

                if (processingState == null) {
                    // We don't already have one for this channel, so create one and register it.
                    processingState = stateClassAndKeyPair.getLeft().newInstance();
                    processingStateAttr.set(processingState);
                }

                // Clean the state for the new request.
                processingState.cleanStateForNewRequest();
            }

            // send request received event
            if (metricsListener != null) {
                metricsListener.onEvent(ServerMetricsEvent.REQUEST_RECEIVED,
                                        ChannelAttributes.getHttpProcessingStateForChannel(ctx).get());
            }

            // Remove the idle channel timeout handler (if there is one) so that it doesn't kill this new request if the
            //      endpoint takes longer to complete than the idle timeout value - the idle channel timeout is only for
            //      timing out channels that are idle *in-between* requests.
            ChannelPipeline pipeline = ctx.pipeline();
            ChannelHandler idleChannelTimeoutHandler =
                pipeline.get(HttpChannelInitializer.IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
            if (idleChannelTimeoutHandler != null)
                pipeline.remove(idleChannelTimeoutHandler);
        }

        // Continue on the pipeline processing.
        super.channelRead(ctx, msg);
    }
}
