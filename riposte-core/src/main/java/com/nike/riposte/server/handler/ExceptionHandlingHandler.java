package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.ServerSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.error.exception.IncompleteHttpCallTimeoutException;
import com.nike.riposte.server.error.exception.InvalidRipostePipelineException;
import com.nike.riposte.server.error.exception.TooManyOpenChannelsException;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.error.handler.ErrorResponseInfo;
import com.nike.riposte.server.error.handler.RiposteErrorHandler;
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.impl.FullResponseInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;
import com.nike.wingtips.Span;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import static com.nike.riposte.server.channelpipeline.ChannelAttributes.getProxyRouterProcessingStateForChannel;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_EXCEPTION_CAUGHT;
import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Handles errors thrown due to the incoming message and converts them to the appropriate {@link ResponseInfo} payload
 * so it can be sent to the user. Also catches cases where an error was not thrown but the request somehow slipped
 * through our endpoints without being handled at all, and sets up an error payload so that the response sending handler
 * spits out an error (since this case indicates a pipeline misconfiguration).
 * <p/>
 * This handler should come directly before {@link ResponseSenderHandler} in the pipeline so that we don't have handlers
 * between this class and the response sender that could throw errors that don't get handled properly. It should also
 * come after the main request handlers (e.g. {@link NonblockingEndpointExecutionHandler} and {@link
 * ProxyRouterEndpointExecutionHandler}) to give the request a chance to be handled at all.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class ExceptionHandlingHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final RiposteErrorHandler riposteErrorHandler;
    private final RiposteUnhandledErrorHandler riposteUnhandledErrorHandler;

    private final @NotNull ServerSpanNamingAndTaggingStrategy<Span> spanNamingAndTaggingStrategy;

    private final RiposteHandlerInternalUtil handlerUtils = RiposteHandlerInternalUtil.DEFAULT_IMPL;

    @SuppressWarnings("ConstantConditions")
    public ExceptionHandlingHandler(
        @NotNull RiposteErrorHandler riposteErrorHandler,
        @NotNull RiposteUnhandledErrorHandler riposteUnhandledErrorHandler,
        @NotNull DistributedTracingConfig<Span> distributedTracingConfig
    ) {
        if (riposteErrorHandler == null) {
            throw new IllegalArgumentException("riposteErrorHandler cannot be null");
        }

        if (riposteUnhandledErrorHandler == null) {
            throw new IllegalArgumentException("riposteUnhandledErrorHandler cannot be null");
        }

        if (distributedTracingConfig == null) {
            throw new IllegalArgumentException("distributedTracingConfig cannot be null");
        }

        this.riposteErrorHandler = riposteErrorHandler;
        this.riposteUnhandledErrorHandler = riposteUnhandledErrorHandler;
        this.spanNamingAndTaggingStrategy = distributedTracingConfig.getServerSpanNamingAndTaggingStrategy();
    }

    @Override
    public PipelineContinuationBehavior doExceptionCaught(ChannelHandlerContext ctx, @NotNull Throwable cause) {
        // We expect to end up here when handlers previously in the pipeline throw an error, so do the normal
        //      processError call.
        HttpProcessingState state = getStateAndCreateIfNeeded(ctx, cause);
        // Ensure that a RequestInfo is set on the state, no matter what.
        getRequestInfo(state, null);
        
        if (state.isResponseSendingStarted()) {
            String infoMessage =
                "A response has already been started. Ignoring this exception since it's secondary. NOTE: This often "
                + "occurs when an error happens repeatedly on multiple chunks of a request or response - only the "
                + "first one is processed into the error sent to the user. The original error is probably higher up in "
                + "the logs. ignored_secondary_exception=\"{}\"";
            if (cause instanceof NullPointerException)
                logger.info(infoMessage, cause.toString(), cause);
            else
                logger.info(infoMessage, cause.toString());

            return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
        }
        else {
            ResponseInfo<ErrorResponseBody> responseInfo = processError(state, null, cause);

            if (shouldForceConnectionCloseAfterResponseSent(cause))
                responseInfo.setForceConnectionCloseAfterResponseSent(true);

            state.setResponseInfo(responseInfo, cause);

            // We're about to send a full error response back to the original caller, so any proxy/router streaming is
            //      invalid. Cancel request and response streaming for proxy/router endpoints.
            Endpoint<?> endpoint = state.getEndpointForExecution();
            if (endpoint instanceof ProxyRouterEndpoint) {
                ProxyRouterProcessingState proxyRouterState = getProxyRouterProcessingStateForChannel(ctx).get();
                if (proxyRouterState != null) {
                    proxyRouterState.cancelRequestStreaming(cause, ctx);
                    proxyRouterState.cancelDownstreamRequest(cause);
                }
            }

            addErrorAnnotationToOverallRequestSpan(state, responseInfo, cause);
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) {
        // We expect to be here for normal message processing, but only as a pass-through. If the state indicates that
        //      the request was not handled then that's a pipeline misconfiguration and we need to throw an error.
        HttpProcessingState state = getStateAndCreateIfNeeded(ctx, null);
        // Ensure that a RequestInfo is set on the state, no matter what.
        getRequestInfo(state, msg);
        
        if (!state.isRequestHandled()) {
            runnableWithTracingAndMdc(() -> {
                String errorMsg = "In ExceptionHandlingHandler's channelRead method, but the request has not yet been "
                                  + "handled. This should not be possible and indicates the pipeline is not set up "
                                  + "properly or some unknown and unexpected error state was triggered. Sending "
                                  + "unhandled error response";
                logger.error(errorMsg);
                Exception ex = new InvalidRipostePipelineException(errorMsg);
                ResponseInfo<ErrorResponseBody> responseInfo = processUnhandledError(state, msg, ex);
                state.setResponseInfo(responseInfo, ex);
                addErrorAnnotationToOverallRequestSpan(state, responseInfo, ex);
            }, ctx).run();
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    protected void addErrorAnnotationToOverallRequestSpan(
        @NotNull HttpProcessingState state,
        @NotNull ResponseInfo<ErrorResponseBody> responseInfo,
        @NotNull Throwable error
    ) {
        try {
            Span requestSpan = state.getOverallRequestSpan();
            if (
                requestSpan != null
                && spanNamingAndTaggingStrategy.shouldAddErrorAnnotationForCaughtException(responseInfo, error)
            ) {
                requestSpan.addTimestampedAnnotationForCurrentTime(
                    spanNamingAndTaggingStrategy.errorAnnotationName(responseInfo, error)
                );
            }
        }
        catch (Throwable t) {
            logger.error(
                "An unexpected error occurred while trying to add an error annotation to the overall request span. "
                + "This should never happen.", t
            );
        }
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        return (methodToExecute == DO_EXCEPTION_CAUGHT);
    }

    protected @NotNull HttpProcessingState getStateAndCreateIfNeeded(ChannelHandlerContext ctx, Throwable cause) {
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state == null) {
            // The error must have occurred before RequestStateCleanerHandler could even execute. Create a new state and
            //      put it into the channel so that we can populate the ResponseInfo for our response sender.
            logger.error(
                "No HttpProcessingState was available. This means the error occurred before RequestStateCleanerHandler "
                + "could execute.", cause
            );
            state = new HttpProcessingState();
            ctx.channel().attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY).set(state);
        }

        return state;
    }

    /**
     * Tries to extract the {@link RequestInfo} associated with the current request using the given arguments. First it
     * will try to get it from the given state. If that fails, it will try to create a new one based on the given msg
     * (which only works if the msg is a {@link HttpRequest}). If that also fails then a new dummy instance for an
     * unknown request will be created via {@link RequestInfoImpl#dummyInstanceForUnknownRequests()} and returned.
     * This will never return null, and the given {@link HttpProcessingState#getRequestInfo()} will always be non-null
     * by the time this method returns.
     */
    @NotNull RequestInfo<?> getRequestInfo(@NotNull HttpProcessingState state, Object msg) {
        // Try to get the RequestInfo from the state variable first.
        RequestInfo requestInfo = state.getRequestInfo();

        if (requestInfo != null) {
            return requestInfo;
        }

        // The state did not have a request info. See if we can build one from the msg.
        if (msg instanceof HttpRequest) {
            try {
                return handlerUtils.createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(
                    (HttpRequest) msg, state
                );
            } catch (Throwable t) {
                logger.error(
                    "Unable to generate RequestInfo from HttpRequest. Defaulting to a synthetic RequestInfo.", t
                );
            }
        }

        // Something major blew up if we reach here, so we just need to create a dummy RequestInfo for an unknown
        //      request.
        requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        state.setRequestInfo(requestInfo);

        return requestInfo;
    }

    /**
     * Attempts to process the given error using the "normal" error handler {@link #riposteErrorHandler} to produce the
     * most specific error response possible for the given error. If that fails for any reason then the unhandled error
     * handler will take over to guarantee the user gets a generic error response that still follows our error contract.
     * If you already know your error is a non-normal unhandled error of the "how did we get here, this should never
     * happen" variety you can (and should) directly call {@link #processUnhandledError(HttpProcessingState, Object,
     * Throwable)} instead.
     */
    protected @NotNull ResponseInfo<ErrorResponseBody> processError(
        @NotNull HttpProcessingState state,
        Object msg,
        @NotNull Throwable cause
    ) {
        RequestInfo<?> requestInfo = getRequestInfo(state, msg);

        try {
            ErrorResponseInfo contentFromErrorHandler = riposteErrorHandler.maybeHandleError(cause, requestInfo);
            if (contentFromErrorHandler != null) {
                // The regular error handler did handle the error. Setup our ResponseInfo.
                ResponseInfo<ErrorResponseBody> responseInfo = new FullResponseInfo<>();
                setupResponseInfoBasedOnErrorResponseInfo(responseInfo, contentFromErrorHandler);
                return responseInfo;
            }
        }
        catch (Throwable errorHandlerFailed) {
            logger.error("An unexpected problem occurred while trying to handle an error.", errorHandlerFailed);
        }

        // If we reach here then it means the regular handler didn't handle the error (or blew up trying to handle it),
        //      so the riposteUnhandledErrorHandler should take care of it.
        return processUnhandledError(state, msg, cause);
    }

    /**
     * Produces a generic error response. Call this if you know the error is a non-normal unhandled error of the "how
     * did we get here, this should never happen" variety, or if other attempts to deal with the error failed and you
     * need a guaranteed fallback that will produce a generic error response that follows our error contract. If you
     * have an error that happened during normal processing you should try {@link #processError(HttpProcessingState,
     * Object, Throwable)} instead in order to get an error response that is better tailored to the given error rather
     * than this one which guarantees a somewhat unhelpful generic error response.
     */
    @NotNull ResponseInfo<ErrorResponseBody> processUnhandledError(
        @NotNull HttpProcessingState state,
        Object msg,
        @NotNull Throwable cause
    ) {
        RequestInfo<?> requestInfo = getRequestInfo(state, msg);

        // Run the error through the riposteUnhandledErrorHandler
        ErrorResponseInfo contentFromErrorHandler = riposteUnhandledErrorHandler.handleError(cause, requestInfo);
        ResponseInfo<ErrorResponseBody> responseInfo = new FullResponseInfo<>();
        setupResponseInfoBasedOnErrorResponseInfo(responseInfo, contentFromErrorHandler);

        return responseInfo;
    }

    protected void setupResponseInfoBasedOnErrorResponseInfo(
        @NotNull ResponseInfo<ErrorResponseBody> responseInfo,
        @NotNull ErrorResponseInfo errorInfo
    ) {
        responseInfo.setContentForFullResponse(errorInfo.getErrorResponseBody());
        responseInfo.setHttpStatusCode(errorInfo.getErrorHttpStatusCode());
        Map<String, List<String>> extraHeaders = errorInfo.getExtraHeadersToAddToResponse();
        if (extraHeaders != null) {
            for (Map.Entry<String, List<String>> headerEntry : extraHeaders.entrySet()) {
                responseInfo.getHeaders().add(headerEntry.getKey(), headerEntry.getValue());
            }
        }
    }

    protected boolean shouldForceConnectionCloseAfterResponseSent(Throwable cause) {
        return (cause instanceof TooManyOpenChannelsException)
            || (cause instanceof IncompleteHttpCallTimeoutException);

    }
}
