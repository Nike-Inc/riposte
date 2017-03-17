package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.exception.InvalidHttpRequestException;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
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
 * The request size is tracked and if it exceeds the configured global or a given endpoint's override, an exception
 * will be thrown.
 *
 * @author Nic Munroe
 */
public class RequestInfoSetterHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final int globalConfiguredMaxRequestSizeInBytes;

    public RequestInfoSetterHandler(int globalConfiguredMaxRequestSizeInBytes) {
        this.globalConfiguredMaxRequestSizeInBytes = globalConfiguredMaxRequestSizeInBytes;
    }

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
                throwExceptionIfNotSuccessfullyDecoded((HttpRequest) msg);
                RequestInfo<?> requestInfo = new RequestInfoImpl<>((HttpRequest) msg);
                state.setRequestInfo(requestInfo);
            }
            else if (msg instanceof HttpContent) {
                throwExceptionIfNotSuccessfullyDecoded((HttpContent) msg);
                RequestInfo<?> requestInfo = state.getRequestInfo();
                if (requestInfo == null) {
                    throw new IllegalStateException("Found a HttpContent msg without a RequestInfo stored in the "
                                                    + "HttpProcessingState. This should be impossible");
                }

                int currentRequestLengthInBytes = requestInfo.addContentChunk((HttpContent) msg);
                int configuredMaxRequestSize = getConfiguredMaxRequestSize(state);

                if (!isMaxRequestSizeValidationDisabled(configuredMaxRequestSize) && currentRequestLengthInBytes > configuredMaxRequestSize) {
                    throw new TooLongFrameException("Request raw content length exceeded configured max request size of " + configuredMaxRequestSize);
                }
            }
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    private void throwExceptionIfNotSuccessfullyDecoded(HttpObject httpObject) {
        if (httpObject.getDecoderResult() != null && httpObject.getDecoderResult().isFailure()) {
            throw new InvalidHttpRequestException("Detected HttpObject that was not successfully decoded.", httpObject.getDecoderResult().cause());
        }
    }

    private boolean isMaxRequestSizeValidationDisabled(int configuredMaxRequestSize) {
        return configuredMaxRequestSize <= 0;
    }

    private int getConfiguredMaxRequestSize(HttpProcessingState state) {
        Endpoint<?> endpoint = state.getEndpointForExecution();

        //if the endpoint is null or the endpoint is not overriding, we should return the globally configured value
        if (endpoint == null || endpoint.maxRequestSizeInBytesOverride() == null) {
            return globalConfiguredMaxRequestSizeInBytes;
        }

        return endpoint.maxRequestSizeInBytesOverride();
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
