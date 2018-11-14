package com.nike.riposte.server.handler;

import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.ChannelAttributes.ProcessingStateClassAndKeyPair;
import com.nike.riposte.server.channelpipeline.HttpChannelInitializer;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProcessingState;
import com.nike.riposte.server.metrics.ServerMetricsEvent;
import com.nike.wingtips.Span;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import static com.nike.riposte.server.channelpipeline.ChannelAttributes.PROCESSING_STATE_ATTRIBUTE_KEYS;
import static com.nike.riposte.server.channelpipeline.HttpChannelInitializer.INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME;

/**
 * Handler that makes sure the channel has a clean instance of {@link com.nike.riposte.server.http.HttpProcessingState}
 * for every new {@link HttpRequest}.
 * <p/>
 * This should be the first handler on the inbound side in a pipeline following
 * {@link io.netty.handler.codec.http.HttpServerCodec}.
 *
 * @author Nic Munroe
 */
public class RequestStateCleanerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RequestStateCleanerHandler.class);

    protected final MetricsListener metricsListener;
    protected final long incompleteHttpCallTimeoutMillis;

    protected final DistributedTracingConfig<Span> distributedTracingConfig;

    public RequestStateCleanerHandler(
        MetricsListener metricsListener,
        long incompleteHttpCallTimeoutMillis,
        DistributedTracingConfig<Span> distributedTracingConfig
    ) {
        this.metricsListener = metricsListener;
        this.incompleteHttpCallTimeoutMillis = incompleteHttpCallTimeoutMillis;
        this.distributedTracingConfig = distributedTracingConfig;
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

            HttpProcessingState httpProcessingState = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();

            // Set the DistributedTracingConfig on the HttpProcessingState.
            //noinspection deprecation - This is the only place that should actually be calling this method.
            httpProcessingState.setDistributedTracingConfig(distributedTracingConfig);

            // Send a request received event to the metricsListener.
            if (metricsListener != null) {
                metricsListener.onEvent(ServerMetricsEvent.REQUEST_RECEIVED, httpProcessingState);
            }

            // Remove the idle channel timeout handler (if there is one) so that it doesn't kill this new request if the
            //      endpoint takes longer to complete than the idle timeout value - the idle channel timeout is only for
            //      timing out channels that are idle *in-between* requests.
            ChannelPipeline pipeline = ctx.pipeline();
            ChannelHandler idleChannelTimeoutHandler =
                pipeline.get(HttpChannelInitializer.IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
            if (idleChannelTimeoutHandler != null)
                pipeline.remove(idleChannelTimeoutHandler);

            // Add the incomplete-call-timeout-handler (if desired) so that incomplete calls don't hang forever and
            //      essentially become memory leaks. Unlike the idleChannelTimeoutHandler above, *this* timeout handler
            //      is for timing out HTTP calls where we've received the first chunk, but are still waiting for the
            //      last chunk when the timeout hits.
            if (incompleteHttpCallTimeoutMillis > 0 && !(msg instanceof LastHttpContent)) {
                IncompleteHttpCallTimeoutHandler newHandler = new IncompleteHttpCallTimeoutHandler(
                    incompleteHttpCallTimeoutMillis
                );

                ChannelHandler existingHandler = pipeline.get(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME);
                if (existingHandler == null) {
                    pipeline.addFirst(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME, newHandler);
                }
                else {
                    logger.error("Handling HttpRequest for new request and found an IncompleteHttpCallTimeoutHandler "
                                 + "already in the pipeline. This should not be possible. A new "
                                 + "IncompleteHttpCallTimeoutHandler will replace the old one. worker_channel_id={}",
                                 ctx.channel().toString());
                    pipeline.replace(existingHandler, INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME, newHandler);
                }
            }
        }
        else if (msg instanceof LastHttpContent) {
            // The HTTP call is complete, so we can remove the IncompleteHttpCallTimeoutHandler.
            ChannelPipeline pipeline = ctx.pipeline();
            ChannelHandler existingHandler = pipeline.get(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME);
            if (existingHandler != null)
                pipeline.remove(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME);
        }

        // Continue on the pipeline processing.
        super.channelRead(ctx, msg);
    }
}
