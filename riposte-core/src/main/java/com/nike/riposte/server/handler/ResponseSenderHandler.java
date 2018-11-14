package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.ChunkedOutboundMessage;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;

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

    public ResponseSenderHandler(ResponseSender responseSender) {
        if (responseSender == null)
            throw new IllegalArgumentException("responseSender cannot be null");

        this.responseSender = responseSender;
    }

    protected void sendResponse(ChannelHandlerContext ctx, Object msg) throws JsonProcessingException {
        try {
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
                if (containsErrorResponseBody(responseInfo)) {
                    //noinspection unchecked
                    responseSender.sendErrorResponse(ctx, requestInfo, (ResponseInfo<ErrorResponseBody>) responseInfo);
                }
                else
                    responseSender.sendFullResponse(ctx, requestInfo, responseInfo, customSerializer);
            }
        }
        catch (Throwable t) {
            runnableWithTracingAndMdc(
                () -> logger.error("An unexpected error occurred while attempting to send a response.", t),
                ctx
            ).run();
            throw t;
        }
    }

    protected boolean containsErrorResponseBody(ResponseInfo<?> responseInfo) {
        if (responseInfo.isChunkedResponse())
            return false;

        Object contentForFullResponse = responseInfo.getContentForFullResponse();
        //noinspection SimplifiableIfStatement
        if (contentForFullResponse == null)
            return false;

        return contentForFullResponse instanceof ErrorResponseBody;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        sendResponse(ctx, msg);

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    public PipelineContinuationBehavior doExceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        sendResponse(ctx, null);

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
