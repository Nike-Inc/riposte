package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.ChunkedOutboundMessage;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessageSendFullResponseInfo;
import com.nike.riposte.server.channelpipeline.message.OutboundMessage;
import com.nike.riposte.server.error.exception.InvalidRipostePipelineException;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ResponseInfo;

import io.netty.channel.ChannelHandlerContext;

import static com.nike.riposte.server.handler.base.PipelineContinuationBehavior.CONTINUE;

/**
 * Simple handler that makes sure any message being fired through {@code channelRead} is an instance of {@link
 * OutboundMessage}. By the time this handler is run, the request should have been handled by one of our endpoint
 * handlers, which only fire {@link OutboundMessage} down the pipeline, therefore if the msg received is anything else
 * it indicates that the message was not handled and the pipeline is broken (in the normal/expected case of a 404 where
 * there are no matching endpoints, an exception should be thrown which would go through the {@code exceptionCaught}
 * methods rather than {@code channelRead}). If this handler detects a non-allowed message type then it will throw a
 * {@link InvalidRipostePipelineException}.
 * <p/>
 * This handler also verifies that there is not a mismatch between the {@link OutboundMessage} type and {@link
 * ResponseInfo#isChunkedResponse()}. Only chunked message types like {@link ChunkedOutboundMessage} are allowed if the
 * response info indicates a chunked response, and vice versa for {@link LastOutboundMessageSendFullResponseInfo} and
 * full responses.
 *
 * @author Nic Munroe
 */
public class RequestHasBeenHandledVerificationHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg == null) {
            throw new InvalidRipostePipelineException(
                "msg cannot be null at this stage of the pipeline. An endpoint handler should have fired a valid "
                + "OutboundMessage. invalid_riposte_pipeline=true"
            );
        }

        if (!(msg instanceof OutboundMessage)) {
            throw new InvalidRipostePipelineException(
                "Expected msg to be a OutboundMessage, but instead found: " + msg.getClass().getName()
                + ". invalid_riposte_pipeline=true"
            );
        }

        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state == null) {
            throw new InvalidRipostePipelineException(
                "Found null HttpProcessingState in the channel, which is not allowed at this point. "
                + "invalid_riposte_pipeline=true"
            );
        }

        ResponseInfo<?> responseInfo = state.getResponseInfo();
        if (responseInfo == null) {
            throw new InvalidRipostePipelineException(
                "Found null ResponseInfo in the channel state, which is not allowed at this point. " +
                "An endpoint handler should have set a ResponseInfo on the state. invalid_riposte_pipeline=true"
            );
        }

        if (responseInfo.isChunkedResponse() && !(msg instanceof ChunkedOutboundMessage)) {
            throw new InvalidRipostePipelineException(
                "ResponseInfo.isChunkedResponse() indicates a chunked response, but the message was not a "
                + "ChunkedOutboundMessage. msg_type=" + msg.getClass().getName() + ", invalid_riposte_pipeline=true"
            );
        }

        if (!responseInfo.isChunkedResponse() && !(msg instanceof LastOutboundMessageSendFullResponseInfo)) {
            throw new InvalidRipostePipelineException(
                "ResponseInfo.isChunkedResponse() indicates a full response, but the message was not a "
                + "LastOutboundMessageSendFullResponseInfo. msg_type=" + msg.getClass().getName()
                + ", invalid_riposte_pipeline=true"
            );
        }

        return CONTINUE;
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
