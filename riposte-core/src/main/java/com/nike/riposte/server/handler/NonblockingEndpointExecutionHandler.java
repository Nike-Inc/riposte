package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.*;
import com.nike.riposte.server.error.exception.NonblockingEndpointCompletableFutureTimedOut;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.NonblockingEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import com.nike.riposte.server.http.impl.ChunkedResponseInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import static com.nike.riposte.util.AsyncNettyHelper.executeOnlyIfChannelIsActive;
import static com.nike.riposte.util.AsyncNettyHelper.functionWithTracingAndMdc;
import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Inspects the current channel state's {@link HttpProcessingState#getEndpointForExecution()} to see if it is a {@link
 * NonblockingEndpoint}. If so, that endpoint will be executed and the returned {@link CompletableFuture} will be
 * adjusted so that we are notified via callback when it is done. The resulting {@link ResponseInfo} will be placed in
 * the channel's state and a Netty event will be fired to get back on the Netty worker thread and complete the
 * pipeline.
 * <p/>
 * This handler should come after {@link RoutingHandler} in the chain to make sure that {@link
 * HttpProcessingState#getEndpointForExecution()} has been populated. It should also come after {@link
 * RequestContentDeserializerHandler} and {@link RequestContentValidationHandler} to make sure the {@link RequestInfo}
 * is fully setup before executing the endpoint.
 */
@SuppressWarnings("WeakerAccess")
public class NonblockingEndpointExecutionHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Executor longRunningTaskExecutor;
    private final long defaultCompletableFutureTimeoutMillis;

    public NonblockingEndpointExecutionHandler(Executor longRunningTaskExecutor,
                                               long defaultCompletableFutureTimeoutMillis) {
        if (longRunningTaskExecutor == null)
            throw new IllegalArgumentException("longRunningTaskExecutor cannot be null");

        this.longRunningTaskExecutor = longRunningTaskExecutor;
        this.defaultCompletableFutureTimeoutMillis = defaultCompletableFutureTimeoutMillis;
    }

    protected boolean shouldHandleDoChannelReadMessage(Object msg, Endpoint<?> endpoint) {
        // This handler should only do something if the endpoint is a NonblockingEndpoint.
        //      Additionally, this handler should only pay attention to Netty HTTP messages. Other messages (e.g. user
        //      event messages) should be ignored.
        return (msg instanceof HttpObject)
               && (endpoint != null)
               && (endpoint instanceof NonblockingEndpoint);
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        Endpoint<?> endpoint = state.getEndpointForExecution();

        if (shouldHandleDoChannelReadMessage(msg, endpoint)) {
            try {
                // We only do something when the last chunk of content has arrived.
                if (msg instanceof LastHttpContent) {
                    NonblockingEndpoint nonblockingEndpoint = ((NonblockingEndpoint) endpoint);

                    // We're supposed to execute the endpoint. There may be pre-endpoint-execution validation logic or
                    //      other work that needs to happen before the endpoint is executed, so set up the
                    //      CompletableFuture for the endpoint call to only execute if the pre-endpoint-execution
                    //      validation/work chain is successful.
                    RequestInfo<?> requestInfo = state.getRequestInfo();
                    @SuppressWarnings("unchecked")
                    CompletableFuture<ResponseInfo<?>> responseFuture = state
                        .getPreEndpointExecutionWorkChain()
                        .thenCompose(functionWithTracingAndMdc(
                            aVoid -> (CompletableFuture<ResponseInfo<?>>)nonblockingEndpoint.execute(
                                requestInfo, longRunningTaskExecutor, ctx
                            ), ctx)
                        );

                    // Register an on-completion callback so we can be notified when the CompletableFuture finishes.
                    responseFuture.whenComplete((responseInfo, throwable) -> {
                        if (throwable != null)
                            asyncErrorCallback(ctx, throwable);
                        else
                            asyncCallback(ctx, responseInfo);
                    });

                    // Also schedule a timeout check with our Netty event loop to make sure we kill the
                    //      CompletableFuture if it goes on too long.
                    long timeoutValueToUse = (nonblockingEndpoint.completableFutureTimeoutOverrideMillis() == null)
                                             ? defaultCompletableFutureTimeoutMillis
                                             : nonblockingEndpoint.completableFutureTimeoutOverrideMillis();
                    ScheduledFuture<?> responseTimeoutScheduledFuture = ctx.channel().eventLoop().schedule(() -> {
                        if (!responseFuture.isDone()) {
                            runnableWithTracingAndMdc(
                                () -> logger.error("A non-blocking endpoint's CompletableFuture did not finish within "
                                                   + "the allotted timeout ({} milliseconds). Forcibly cancelling it.",
                                                   timeoutValueToUse), ctx
                            ).run();
                            @SuppressWarnings("unchecked")
                            Throwable errorToUse = nonblockingEndpoint.getCustomTimeoutExceptionCause(requestInfo, ctx);
                            if (errorToUse == null)
                                errorToUse = new NonblockingEndpointCompletableFutureTimedOut(timeoutValueToUse);
                            responseFuture.completeExceptionally(errorToUse);
                        }
                    }, timeoutValueToUse, TimeUnit.MILLISECONDS);

                    /*
                        The problem with the scheduled timeout check is that it holds on to the RequestInfo,
                        ChannelHandlerContext, and a bunch of other stuff that *should* become garbage the instant the
                        request finishes, but because of the timeout check it has to wait until the check executes
                        before the garbage is collectable. In high volume servers the default 60 second timeout is way
                        too long and acts like a memory leak and results in garbage collection thrashing if the
                        available memory can be filled within the 60 second timeout. To combat this we cancel the
                        timeout future when the endpoint future finishes. Netty will remove the cancelled timeout future
                        from its scheduled list within a short time, thus letting the garbage be collected.
                    */
                    responseFuture.whenComplete((responseInfo, throwable) -> {
                        if (!responseTimeoutScheduledFuture.isDone())
                            responseTimeoutScheduledFuture.cancel(false);
                    });
                }
            }
            finally {
                // No matter what, we're done with the message. Release it (if the RequestInfo object is collecting
                //      chunks in order to build the raw content string then it will have retain()-ed the message
                //      already and this release won't cause the msg's reference count to fall below 1, but from the
                //      pipeline's point of view we're done with this message so we call release).
                ReferenceCountUtil.release(msg);
            }

            // Whether it was the last chunk or not, we don't want the pipeline to continue since the endpoint was a
            //      NonblockingEndpoint and we need to wait for the CompletableFuture to complete. When the
            //      NonblockingEndpoint processes the request then the pipeline will continue when the CompletableFuture
            //      completes (see asyncCallback() and asyncErrorCallback()).
            return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
        }

        // Not a NonblockingEndpoint, so continue the pipeline in case another endpoint handler is in the pipeline and
        //      wants to deal with it. If no such endpoint handler exists then ExceptionHandlingHandler will cause an
        //      error to be returned to the client.
        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // To save on extraneous linking/unlinking, we'll do it as-necessary in this class.
        return false;
    }

    protected void asyncCallback(ChannelHandlerContext ctx, ResponseInfo<?> responseInfo) {
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();

        if (responseInfo.isChunkedResponse()) {
            state.setResponseInfo(responseInfo);
            executeOnlyIfChannelIsActive(
                    ctx, "NonblockingEndpointExecutionHandler-asyncCallback",
                    () -> {
                        ctx.fireChannelRead(OutboundMessageSendHeadersChunkFromResponseInfo.INSTANCE);
                        ChunkedResponseInfo info = (ChunkedResponseInfo) responseInfo;
                        info.writer.accept(content -> {
                            if (content instanceof LastHttpContent) {
                                ctx.fireChannelRead(new LastOutboundMessageSendLastContentChunk((LastHttpContent) content));
                            }
                            else {
                                ctx.fireChannelRead(new OutboundMessageSendContentChunk(content));
                            }
                        });
                    }
            );
        }
        else {
            state.setResponseInfo(responseInfo);
            executeOnlyIfChannelIsActive(
                ctx, "NonblockingEndpointExecutionHandler-asyncCallback",
                () -> ctx.fireChannelRead(LastOutboundMessageSendFullResponseInfo.INSTANCE)
            );
        }
    }

    protected void asyncErrorCallback(ChannelHandlerContext ctx, Throwable error) {
        executeOnlyIfChannelIsActive(
            ctx, "NonblockingEndpointExecutionHandler-asyncErrorCallback",
            () -> ctx.fireExceptionCaught(error)
        );
    }
}