package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.exception.IncompleteHttpCallTimeoutException;
import com.nike.riposte.server.error.exception.InvalidRipostePipelineException;
import com.nike.riposte.server.error.exception.TooManyOpenChannelsException;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.error.handler.ErrorResponseInfo;
import com.nike.riposte.server.error.handler.RiposteErrorHandler;
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.impl.FullResponseInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_EXCEPTION_CAUGHT;
import static com.nike.riposte.util.AsyncNettyHelper.callableWithTracingAndMdc;

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

    public ExceptionHandlingHandler(RiposteErrorHandler riposteErrorHandler,
                                    RiposteUnhandledErrorHandler riposteUnhandledErrorHandler) {
        if (riposteErrorHandler == null)
            throw new IllegalArgumentException("riposteErrorHandler cannot be null");

        if (riposteUnhandledErrorHandler == null)
            throw new IllegalArgumentException("riposteUnhandledErrorHandler cannot be null");

        this.riposteErrorHandler = riposteErrorHandler;
        this.riposteUnhandledErrorHandler = riposteUnhandledErrorHandler;
    }

    @Override
    public PipelineContinuationBehavior doExceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // We expect to end up here when handlers previously in the pipeline throw an error, so do the normal
        //      processError call.
        HttpProcessingState state = getStateAndCreateIfNeeded(ctx, cause);
        if (state.isResponseSendingStarted()) {
            logger.info(
                "A response has already been started. Ignoring this exception since it's secondary. NOTE: This often "
                + "occurs when an error happens repeatedly on multiple chunks of a request or response - only the "
                + "first one is processed into the error sent to the user. The original error is probably higher up in "
                + "the logs. ignored_secondary_exception=\"{}\"", cause.toString());

            return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
        }
        else {
            ResponseInfo<ErrorResponseBody> responseInfo = processError(state, null, cause);

            if (shouldForceConnectionCloseAfterResponseSent(cause))
                responseInfo.setForceConnectionCloseAfterResponseSent(true);

            state.setResponseInfo(responseInfo);
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // We expect to be here for normal message processing, but only as a pass-through. If the state indicates that
        //      the request was not handled then that's a pipeline misconfiguration and we need to throw an error.
        HttpProcessingState state = getStateAndCreateIfNeeded(ctx, null);
        if (!state.isRequestHandled()) {
            callableWithTracingAndMdc(() -> {
                String errorMsg = "In ExceptionHandlingHandler's channelRead method, but the request has not yet been "
                                  + "handled. This should not be possible and indicates the pipeline is not set up "
                                  + "properly or some unknown and unexpected error state was triggered. Sending "
                                  + "unhandled error response";
                logger.error(errorMsg);
                Exception ex = new InvalidRipostePipelineException(errorMsg);
                ResponseInfo<ErrorResponseBody> responseInfo = processUnhandledError(state, msg, ex);
                state.setResponseInfo(responseInfo);
                return null;
            }, ctx).call();
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        return (methodToExecute == DO_EXCEPTION_CAUGHT);
    }

    protected HttpProcessingState getStateAndCreateIfNeeded(ChannelHandlerContext ctx, Throwable cause) {
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
     */
    RequestInfo<?> getRequestInfo(HttpProcessingState state, Object msg) {
        // Try to get the RequestInfo from the state variable first.
        RequestInfo requestInfo = state.getRequestInfo();

        // If the state did not have a request info, see if we can build one from the msg
        if (requestInfo == null && msg != null && (msg instanceof HttpRequest))
            requestInfo = new RequestInfoImpl((HttpRequest) msg);

        // If requestInfo is still null then something major blew up, and we just need to create a dummy one for an
        //      unknown request
        if (requestInfo == null)
            requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();

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
    protected ResponseInfo<ErrorResponseBody> processError(HttpProcessingState state,
                                                           Object msg,
                                                           Throwable cause) throws JsonProcessingException {
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
    ResponseInfo<ErrorResponseBody> processUnhandledError(HttpProcessingState state,
                                                          Object msg,
                                                          Throwable cause) throws JsonProcessingException {
        RequestInfo<?> requestInfo = getRequestInfo(state, msg);

        // Run the error through the riposteUnhandledErrorHandler
        ErrorResponseInfo contentFromErrorHandler = riposteUnhandledErrorHandler.handleError(cause, requestInfo);
        ResponseInfo<ErrorResponseBody> responseInfo = new FullResponseInfo<>();
        setupResponseInfoBasedOnErrorResponseInfo(responseInfo, contentFromErrorHandler);

        return responseInfo;
    }

    protected void setupResponseInfoBasedOnErrorResponseInfo(ResponseInfo<ErrorResponseBody> responseInfo,
                                                             ErrorResponseInfo errorInfo) {
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
