package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;

/**
 * Monitors the incoming messages - when it sees a {@link HttpRequest} then it creates a new {@link RequestInfo} from it
 * and sets it on the channel's current request state via {@link HttpProcessingState#setRequestInfo(RequestInfo)}. If
 * the incoming request is chunked then the later chunks are added to the stored request info via {@link
 * RequestInfo#addContentChunk(HttpContent)}.
 * <p/>
 * This handler should come after {@link io.netty.handler.codec.http.HttpRequestDecoder} and {@link
 * SmartHttpContentCompressor} in the pipeline.
 *
 * @author Nic Munroe
 */
public class RequestInfoSetterHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) {
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state != null && state.isResponseSendingLastChunkSent()) {
            // A response has already been sent for this request, likely due to an error being thrown from an
            //      earlier msg. We can therefore ignore this msg chunk and not process anything further.
            ReferenceCountUtil.release(msg);
            return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
        }

        if (state != null) {
            if (msg instanceof HttpRequest) {
                RequestInfo<?> requestInfo = new RequestInfoImpl<>((HttpRequest) msg);
                state.setRequestInfo(requestInfo);
            }
            else if (msg instanceof HttpContent) {
                RequestInfo<?> requestInfo = state.getRequestInfo();
                if (requestInfo == null) {
                    throw new IllegalStateException("Found a HttpContent msg without a RequestInfo stored in the "
                                                    + "HttpProcessingState. This should be impossible");
                }

                requestInfo.addContentChunk((HttpContent) msg);
            }
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // This class does not log, and nothing that happens in this class should cause logging to happen elsewhere.
        //      Therefore we should never bother with linking/unlinking tracing info to save on the extra processing.
        //      (Especially since we would otherwise need to enable it for *every* incoming chunk).
        return false;
    }
}
