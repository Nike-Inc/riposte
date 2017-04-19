package com.nike.riposte.server.handler;

import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessage;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.metrics.ServerMetricsEvent;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;

import static com.nike.riposte.server.channelpipeline.HttpChannelInitializer.IDLE_CHANNEL_TIMEOUT_HANDLER_NAME;
import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Finalizes incoming messages so that the pipeline considers the message handled and won't throw an error. This first
 * checks to see if a response was sent to the user - if not then a generic error will be thrown. After that it cleans
 * out the {@link HttpProcessingState} for this channel in preparation for a new incoming request, and sets up a new
 * {@link IdleChannelTimeoutHandler} that will kill the channel if it sits idle for longer than the timeout value before
 * the next request comes in.
 * <p/>
 * This handler should be the last handler in the pipeline.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class ChannelPipelineFinalizerHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final ExceptionHandlingHandler exceptionHandlingHandler;
    private final ResponseSender responseSender;
    private final MetricsListener metricsListener;
    private final long workerChannelIdleTimeoutMillis;

    /**
     * @param exceptionHandlingHandler
     *     The {@link ExceptionHandlingHandler} that is used by the pipeline where this class is registered for handling
     *     errors/exceptions.
     * @param responseSender
     *     The {@link ResponseSender} that is used by the pipeline where this class is registered for sending data to
     *     the user.
     * @param workerChannelIdleTimeoutMillis
     *     The time in millis that should be given to {@link IdleChannelTimeoutHandler}s when they are created for
     *     detecting idle channels that need to be closed.
     */
    public ChannelPipelineFinalizerHandler(ExceptionHandlingHandler exceptionHandlingHandler,
                                           ResponseSender responseSender,
                                           MetricsListener metricsListener, long workerChannelIdleTimeoutMillis) {
        if (exceptionHandlingHandler == null)
            throw new IllegalArgumentException("exceptionHandlingHandler cannot be null");

        if (responseSender == null)
            throw new IllegalArgumentException("responseSender cannot be null");

        this.exceptionHandlingHandler = exceptionHandlingHandler;
        this.responseSender = responseSender;
        this.metricsListener = metricsListener;
        this.workerChannelIdleTimeoutMillis = workerChannelIdleTimeoutMillis;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LastOutboundMessage) {
            Exception ex = new Exception("Manually created exception to be used for diagnostic stack trace");
            HttpProcessingState state = getStateAndCreateIfNeeded(ctx, ex);
            finalizeChannelPipeline(ctx, msg, state, ex);
        }

        return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
    }

    @Override
    public PipelineContinuationBehavior doExceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        HttpProcessingState state = getStateAndCreateIfNeeded(ctx, cause);
        finalizeChannelPipeline(ctx, null, state, cause);

        return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // At this point in the pipeline DTraceEndHandler has already run,
        //      so there is no tracing information to link/unlink.
        return false;
    }

    /**
     * Returns the given context's channel's state. We expect the state to exist, so if it doesn't this method will log
     * an error (using the passed-in cause as the stack trace) and setup a new blank state.
     */
    protected HttpProcessingState getStateAndCreateIfNeeded(ChannelHandlerContext ctx, Throwable cause) {
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state == null) {
            // The error must have occurred before RequestStateCleanerHandler could even execute. Create a new state and
            //      put it into the channel so that we can process without worrying about null states.
            logger.error(
                "Found an empty state for this request in the finalizer - this should not be possible and indicates a "
                + "major problem in the channel pipeline.",
                new Exception("Wrapper exception", cause)
            );
            state = new HttpProcessingState();
            ctx.channel().attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY).set(state);
        }

        return state;
    }

    /**
     * This will first check the given state to see if a response was sent to the user. If not then this method will
     * send a generic error to the user so they get some response (so this method is kind of a backstop in case requests
     * somehow slip through our pipeline without being handled, which should never happen, but we have to have this just
     * in case). Then it will clean out the state so that it is ready for the next request.
     * <p/>
     * If the state indicates that a response was already sent then this method will only clean out the state for the
     * next request and will not send an error.
     */
    protected void finalizeChannelPipeline(ChannelHandlerContext ctx, Object msg, HttpProcessingState state,
                                           Throwable cause) throws JsonProcessingException {
        RequestInfo<?> requestInfo = exceptionHandlingHandler.getRequestInfo(state, msg);

        // Send a generic error response to the client if no response has already been sent. NOTE: In the case of
        //      multiple chunked messages, this block will only be executed once because when the generic error response
        //      is sent it will update the state.isResponseSent() so that further calls will return true.
        if (!state.isResponseSendingStarted()) {
            String errorMsg = "Discovered a request that snuck through without a response being sent. This should not "
                              + "be possible and indicates a major problem in the channel pipeline.";
            logger.error(errorMsg, new Exception("Wrapper exception", cause));

            // Send a generic unhandled error response with a wrapper exception so that the logging info output by the
            //      exceptionHandlingHandler will have the overview of what went wrong.
            Exception exceptionToUse = new Exception(errorMsg, cause);
            ResponseInfo<ErrorResponseBody> responseInfo =
                exceptionHandlingHandler.processUnhandledError(state, msg, exceptionToUse);
            responseSender.sendErrorResponse(ctx, requestInfo, responseInfo);
        }

        ctx.flush();

        // Send response sent event for metrics purposes now that we handled all possible cases.
        //      Due to multiple messages and exception possibilities/interactions it's possible we've already dealt with
        //      the metrics for this request, so make sure we only do it if appropriate.
        if (metricsListener != null && !state.isRequestMetricsRecordedOrScheduled()) {
            // If there was no response sent then do the metrics event now (should only happen under rare error
            //      conditions), otherwise do it when the response finishes.
            if (!state.isResponseSendingLastChunkSent()) {
                // TODO: Somehow mark the state as a failed request and update the metrics listener to handle it
                metricsListener.onEvent(ServerMetricsEvent.RESPONSE_SENT, state);
            }
            else {
                // We need to use a copy of the state in case the original state gets cleaned.
                HttpProcessingState stateCopy = new HttpProcessingState(state);
                stateCopy.getResponseWriterFinalChunkChannelFuture()
                         .addListener((ChannelFutureListener) channelFuture -> {
                             if (channelFuture.isSuccess())
                                 metricsListener.onEvent(ServerMetricsEvent.RESPONSE_SENT, stateCopy);
                             else {
                                 // TODO: Somehow mark the state as a failed request and update the metrics listener to handle it
                                 metricsListener.onEvent(ServerMetricsEvent.RESPONSE_WRITE_FAILED, null);
                             }
                         });
            }

            state.setRequestMetricsRecordedOrScheduled(true);
        }

        // Make sure to clear out request info chunks, multipart data, and any other resources to prevent reference
        //      counting memory leaks (or any other kind of memory leaks).
        requestInfo.releaseAllResources();

        // Add an IdleChannelTimeoutHandler (if desired) to the start of the pipeline in order to auto-close this
        //      channel if it sits unused longer than the timeout value before the next request arrives.
        if (workerChannelIdleTimeoutMillis > 0 && ctx.pipeline().get(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME) == null) {
            ctx.pipeline().addFirst(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME,
                                    new IdleChannelTimeoutHandler(workerChannelIdleTimeoutMillis,
                                                                  "ServerWorkerChannel"));
        }

        // If we're in an error case (cause != null) and the response sending has started but not completed, then this
        //      request is broken. We can't do anything except kill the channel.
        if ((cause != null) && state.isResponseSendingStarted() && !state.isResponseSendingLastChunkSent()) {
            logger.error("Received an error in ChannelPipelineFinalizerHandler after response sending was started, but "
                         + "before it finished. Closing the channel.", cause);
            ctx.channel().close();
        }
    }

    /**
     * This method is used as the final cleanup safety net for when a channel is closed. It guarantees that any
     * {@link ByteBuf}s being held by {@link RequestInfo} or {@link ProxyRouterProcessingState} are {@link
     * ByteBuf#release()}d so that we don't end up with a memory leak.
     *
     * <p>Note that we can't use {@link ChannelOutboundHandler#close(ChannelHandlerContext, ChannelPromise)} for this
     * purpose as it is only called if we close the connection in our application code. It won't be triggered if
     * (for example) the caller closes the connection, and we need it to *always* run for *every* closed connection,
     * no matter the source of the close. {@link ChannelInboundHandler#channelInactive(ChannelHandlerContext)} is always
     * called, so we're using that.
     */
    @Override
    public PipelineContinuationBehavior doChannelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            // Grab hold of the things we may need when cleaning up.
            HttpProcessingState httpState = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            ProxyRouterProcessingState proxyRouterState =
                ChannelAttributes.getProxyRouterProcessingStateForChannel(ctx).get();

            RequestInfo<?> requestInfo = (httpState == null) ? null : httpState.getRequestInfo();
            ResponseInfo<?> responseInfo = (httpState == null) ? null : httpState.getResponseInfo();

            if (logger.isDebugEnabled()) {
                runnableWithTracingAndMdc(
                    () -> logger.debug("Cleaning up channel after it was closed. closed_channel_id={}",
                                       ctx.channel().toString()),
                    ctx
                ).run();
            }

            // Handle the case where the response wasn't fully sent or tracing wasn't completed for some reason.
            //      We want to finish the distributed tracing span for this request since there's no other place it
            //      might be done, and if the request wasn't fully sent then we should spit out a log message so
            //      debug investigations can find out what happened.
            // TODO: Is there a way we can handle access logging and/or metrics here, but only if it wasn't done elsewhere?
            @SuppressWarnings("SimplifiableConditionalExpression")
            boolean tracingAlreadyCompleted = (httpState == null) ? true : httpState.isTraceCompletedOrScheduled();
            boolean responseNotFullySent = responseInfo == null || !responseInfo.isResponseSendingLastChunkSent();
            if (responseNotFullySent || !tracingAlreadyCompleted) {
                runnableWithTracingAndMdc(
                    () -> {
                        if (responseNotFullySent) {
                            logger.warn(
                                "The caller's channel was closed before a response could be sent. This means that "
                                + "an access log probably does not exist for this request. Distributed tracing "
                                + "will be completed now if it wasn't already done. Any dangling resources will be "
                                + "released."
                            );
                        }

                        if (!tracingAlreadyCompleted) {
                            Span currentSpan = Tracer.getInstance().getCurrentSpan();
                            if (currentSpan != null && !currentSpan.isCompleted())
                                Tracer.getInstance().completeRequestSpan();

                            httpState.setTraceCompletedOrScheduled(true);
                        }
                    },
                    ctx
                ).run();
            }

            // Tell the RequestInfo it can release all its resources.
            if (requestInfo != null)
                requestInfo.releaseAllResources();

            // Tell the ProxyRouterProcessingState that the stream failed and trigger its chunk streaming error handling
            //      with an artificial exception. If the call had already succeeded previously then this will do
            //      nothing, but if it hasn't already succeeded then it's not going to (since the connection is closing)
            //      and doing this will cause any resources its holding onto to be released.
            if (proxyRouterState != null) {
                proxyRouterState.setStreamingFailed();
                proxyRouterState.triggerStreamingChannelErrorForChunks(
                    new RuntimeException("Server worker channel closed")
                );
            }
        }
        catch(Throwable t) {
            runnableWithTracingAndMdc(
                () -> logger.error(
                    "An unexpected error occurred during ChannelPipelineFinalizerHandler.doChannelInactive() - this "
                    + "should not happen and indicates a bug that needs to be fixed in Riposte.", t),
                ctx
            ).run();
        }

        return PipelineContinuationBehavior.CONTINUE;
    }
}
