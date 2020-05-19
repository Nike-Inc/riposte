package com.nike.riposte.server.handler;

import com.nike.backstopper.apierror.sample.SampleCoreApiError;
import com.nike.backstopper.model.riposte.ErrorResponseBodyImpl;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.ChunkedOutboundMessage;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.http.impl.RequestInfoImpl;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.tags.KnownZipkinTags;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.UUID;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;

import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Handler responsible for sending the {@link ResponseInfo} (contained in your channel's {@link
 * com.nike.riposte.server.http.HttpProcessingState#getResponseInfo()}) to the user. Also handles sending content chunks
 * if the response is chunked (requires the messages passed to {@code channelRead} to be extensions of {@link
 * ChunkedOutboundMessage}).
 * <p/>
 * This should come after any endpoint handlers and the {@link com.nike.riposte.server.handler.ExceptionHandlingHandler}
 * to make sure that the channel state's {@link com.nike.riposte.server.http.HttpProcessingState#getResponseInfo()} has
 * been populated by the time this handler executes.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class ResponseSenderHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final ResponseSender responseSender;
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final String LAST_DITCH_RESPONSE_SET_REQ_ATTR_KEY = this.getClass().getName() + "-LastDitchResponseSet";

    public ResponseSenderHandler(ResponseSender responseSender) {
        if (responseSender == null)
            throw new IllegalArgumentException("responseSender cannot be null");

        this.responseSender = responseSender;
    }

    protected void sendResponse(ChannelHandlerContext ctx, Object msg, boolean sendLastDitchResponseInline) throws JsonProcessingException {
        try {
            // Try to send the response.
            doSendResponse(ctx, msg);
        }
        catch (Exception origSendEx) {
            boolean shouldRethrowOriginalSendEx = true;

            // Something went wrong while trying to send the response. We want to create a generic service error and
            //      send that back to the caller if possible.

            // The HttpProcessingState will never be null thanks to ExceptionHandlingHandler
            HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();

            boolean alreadyTriedSendingLastDitchResponse = alreadyTriedSendingLastDitchResponse(state);

            // Cancel any proxy router streaming that may be happening.
            ProxyRouterProcessingState proxyRouterProcessingState =
                ChannelAttributes.getProxyRouterProcessingStateForChannel(ctx).get();
            if (proxyRouterProcessingState != null) {
                proxyRouterProcessingState.cancelRequestStreaming(origSendEx, ctx);
                proxyRouterProcessingState.cancelDownstreamRequest(origSendEx);
            }

            // If the response sending has already started, then there's really nothing we can do but log the error
            //      and close the connection. Otherwise, where response sending has not already started,
            //      we can create a generic service error and try sending that.
            if (state.isResponseSendingStarted()) {
                runnableWithTracingAndMdc(
                    () -> {
                        logger.error(
                            "An unexpected error occurred while sending the response. At least part of the "
                            + "response was sent, so there's nothing we can do at this point but close the connection.",
                            origSendEx
                        );
                        // Add this error to the current span if possible, but only if no error tag already exists.
                        Span currentSpan = Tracer.getInstance().getCurrentSpan();
                        if (currentSpan != null && currentSpan.getTags().get(KnownZipkinTags.ERROR) == null) {
                            String errorTagValue = (origSendEx.getMessage() == null)
                                             ? origSendEx.getClass().getSimpleName()
                                             : origSendEx.getMessage();
                            currentSpan.putTag(KnownZipkinTags.ERROR, errorTagValue);
                        }
                    },
                    ctx
                ).run();
                ctx.channel().close();
            }
            else if (!alreadyTriedSendingLastDitchResponse){
                // Mark that we've tried doing the last-ditch response so that we only ever attempt it once.
                markTriedSendingLastDitchResponse(state);

                // We haven't already started response sending, so we can try sending a last ditch error response
                //      instead that represents the response-sending exception.
                String errorId = UUID.randomUUID().toString();
                ResponseInfo<?> lastDitchErrorResponseInfo = ResponseInfo
                    .newBuilder(
                        new ErrorResponseBodyImpl(
                            errorId, Collections.singleton(SampleCoreApiError.GENERIC_SERVICE_ERROR))
                    )
                    .withHeaders(new DefaultHttpHeaders().set("error_uid", errorId))
                    .withHttpStatusCode(500)
                    .build();

                state.setResponseInfo(lastDitchErrorResponseInfo, origSendEx);

                runnableWithTracingAndMdc(
                    () -> logger.error(
                        "An unexpected error occurred while attempting to send a response. We'll attempt to send a "
                        + "last-ditch error message. error_uid={}",
                        errorId, origSendEx
                    ),
                    ctx
                ).run();

                if (sendLastDitchResponseInline) {
                    doSendResponse(ctx, msg);
                    // The last ditch response was successfully sent - we don't need to rethrow the original exception.
                    shouldRethrowOriginalSendEx = false;
                }
            }

            if (shouldRethrowOriginalSendEx) {
                // Rethrow the original response-sending exception. If doChannelRead() is calling this method, then
                //      this rethrown exception will cause doExceptionCaught() to execute, which will then attempt
                //      to send the last-ditch response. But if doExceptionCaught() is calling this method, then it
                //      will catch this rethrown exception, log it, and close the channel. In any case, no exception
                //      will be allowed to propagate out of this class.
                throw origSendEx;
            }
        }
    }

    protected void markTriedSendingLastDitchResponse(@NotNull HttpProcessingState state) {
        RequestInfo<?> requestInfo = state.getRequestInfo();
        if (requestInfo == null) {
            return;
        }

        requestInfo.addRequestAttribute(LAST_DITCH_RESPONSE_SET_REQ_ATTR_KEY, Boolean.TRUE);
    }

    protected boolean alreadyTriedSendingLastDitchResponse(@NotNull HttpProcessingState state) {
        RequestInfo<?> requestInfo = state.getRequestInfo();
        if (requestInfo == null) {
            return false;
        }

        return Boolean.TRUE.equals(requestInfo.getRequestAttributes().get(LAST_DITCH_RESPONSE_SET_REQ_ATTR_KEY));
    }

    protected void doSendResponse(ChannelHandlerContext ctx, Object msg) throws JsonProcessingException {
        // Only bother trying to send the response if the channel is active.
        if (!ctx.channel().isActive()) {
            if (logger.isDebugEnabled()) {
                runnableWithTracingAndMdc(
                    () -> logger.debug(
                        "The channel is closed. Ignoring this method call to send response."
                    ),
                    ctx
                ).run();
            }
            return;
        }

        // The HttpProcessingState will never be null thanks to ExceptionHandlingHandler
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();

        if (state.isResponseSendingLastChunkSent()) {
            if (logger.isDebugEnabled()) {
                runnableWithTracingAndMdc(
                    () -> logger.debug("A response has already been sent. "
                                       + "Ignoring this method call to send response."),
                    ctx
                ).run();
            }
            return;
        }

        RequestInfo<?> requestInfo = state.getRequestInfo();
        if (requestInfo == null)
            requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        ResponseInfo<?> responseInfo = state.getResponseInfo();
        Endpoint<?> endpointExecuted = state.getEndpointForExecution();
        ObjectMapper customSerializer = (endpointExecuted == null)
                                        ? null
                                        : endpointExecuted.customResponseContentSerializer(requestInfo);

        if (msg instanceof ChunkedOutboundMessage) {
            // Chunked message. Stream it out.
            responseSender.sendResponseChunk(ctx, requestInfo, responseInfo, (ChunkedOutboundMessage) msg);
        }
        else {
            // Full message. Send it.
            if (getErrorResponseBodyIfPossible(responseInfo) != null) {
                //noinspection unchecked
                responseSender.sendErrorResponse(ctx, requestInfo, (ResponseInfo<ErrorResponseBody>) responseInfo);
            }
            else {
                responseSender.sendFullResponse(ctx, requestInfo, responseInfo, customSerializer);
            }
        }
    }

    protected @Nullable ErrorResponseBody getErrorResponseBodyIfPossible(@Nullable ResponseInfo<?> responseInfo) {
        if (responseInfo == null || responseInfo.isChunkedResponse()) {
            return null;
        }

        Object contentForFullResponse = responseInfo.getContentForFullResponse();
        //noinspection SimplifiableIfStatement
        if (contentForFullResponse == null) {
            return null;
        }

        if (contentForFullResponse instanceof ErrorResponseBody) {
            return (ErrorResponseBody)contentForFullResponse;
        }

        return null;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        sendResponse(ctx, msg, false);

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    public PipelineContinuationBehavior doExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        try {
            sendResponse(ctx, null, true);
        }
        catch (Exception ex) {
            runnableWithTracingAndMdc(
                () -> logger.error(
                    "Unexpected error occurred while trying to send a response for an exception. There's nothing "
                    + "we can do at this point, so the channel will be closed.",
                    ex
                ),
                ctx
            ).run();
            ctx.channel().close();
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // To save on extraneous linking/unlinking, we'll do it as-necessary in this class.
        return false;
    }
}
