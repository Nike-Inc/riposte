package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.exception.RequestTooBigException;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;

import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;
import static com.nike.riposte.util.HttpUtils.getConfiguredMaxRequestSize;
import static com.nike.riposte.util.HttpUtils.isMaxRequestSizeValidationDisabled;

/**
 * Monitors the incoming messages - when it sees a {@link HttpRequest} then it creates a new {@link RequestInfo} from it
 * and sets it on the channel's current request state via {@link HttpProcessingState#setRequestInfo(RequestInfo)}. If
 * the incoming request is chunked then the later chunks are added to the stored request info via {@link
 * RequestInfo#addContentChunk(HttpContent)}.
 * <p/>
 * This handler should come after {@link io.netty.handler.codec.http.HttpServerCodec}, {@link
 * SmartHttpContentCompressor}, and {@link SmartHttpContentDecompressor} in the pipeline.
 *
 * The request size is tracked and if it exceeds the configured global or a given endpoint's override, an exception
 * will be thrown.
 *
 * @author Nic Munroe
 */
public class RequestInfoSetterHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private static final Logger logger = LoggerFactory.getLogger(RequestInfoSetterHandler.class);

    protected final RiposteHandlerInternalUtil handlerUtils = RiposteHandlerInternalUtil.DEFAULT_IMPL;
    protected final int globalConfiguredMaxRequestSizeInBytes;

    public RequestInfoSetterHandler(int globalConfiguredMaxRequestSizeInBytes) {
        this.globalConfiguredMaxRequestSizeInBytes = globalConfiguredMaxRequestSizeInBytes;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            if (state == null || state.isResponseSendingLastChunkSent()) {
                if (state == null)
                    logger.error("HttpProcessingState is null for this request. This should not be possible.");

                // A response has already been sent for this request (likely due to an error being thrown from an
                //      earlier msg) or the state is null. We can therefore ignore this msg chunk and not process
                //      anything further.
                return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
            }

            // We have a HttpProcessingState. Process the message and continue the pipeline processing.
            if (msg instanceof HttpRequest) {
                // This should be done by RoutingHandler already but it doesn't hurt to double check here, and it
                //      keeps this handler independent in case things get refactored again in the future.
                handlerUtils.createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(
                    (HttpRequest)msg, state
                );
            }
            else if (msg instanceof HttpContent) {
                HttpContent httpContentMsg = (HttpContent) msg;

                handlerUtils.throwExceptionIfNotSuccessfullyDecoded(httpContentMsg);
                RequestInfo<?> requestInfo = state.getRequestInfo();
                if (requestInfo == null) {
                    throw new IllegalStateException(
                        "Found a HttpContent msg without a RequestInfo stored in the HttpProcessingState. "
                        + "This should be impossible"
                    );
                }

                int currentRequestLengthInBytes = requestInfo.addContentChunk(httpContentMsg);
                int configuredMaxRequestSize = getConfiguredMaxRequestSize(state.getEndpointForExecution(), globalConfiguredMaxRequestSizeInBytes);

                if (!isMaxRequestSizeValidationDisabled(configuredMaxRequestSize)
                    && currentRequestLengthInBytes > configuredMaxRequestSize) {
                    throw new RequestTooBigException(
                        "Request raw content length exceeded configured max request size of "
                        + configuredMaxRequestSize
                    );
                }
            }

            return PipelineContinuationBehavior.CONTINUE;
        }
        finally {
            // For HttpContent messages, either requestInfo.addContentChunk() has been called and the reference count
            //      increased (i.e. the RequestInfo is now responsible for releasing the content when
            //      requestInfo.releaseAllResources() is called), or an exception has been thrown. In any case, we
            //      are done with any message from a pipeline perspective and can reduce its reference count.
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public PipelineContinuationBehavior doExceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // If this method is called, there's a chance that the HttpProcessingState does not have a RequestInfo set on it
        //      (i.e. if this exception was caused by a bad HTTP call and throwExceptionIfNotSuccessfullyDecoded()
        //      threw a InvalidHttpRequestException). Things like access logger and metrics listener need a RequestInfo
        //      in order to function, so we should create a synthetic one.
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state != null && state.getRequestInfo() == null) {
            state.setRequestInfo(RequestInfoImpl.dummyInstanceForUnknownRequests());
            runnableWithTracingAndMdc(
                () -> logger.warn(
                    "An error occurred before a RequestInfo could be created, so a synthetic RequestInfo indicating an "
                    + "error will be used instead. This can happen when the request cannot be decoded as a valid HTTP "
                    + "request.", cause
                ),
                ctx
            ).run();
        }

        return super.doExceptionCaught(ctx, cause);
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
