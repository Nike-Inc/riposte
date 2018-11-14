package com.nike.riposte.server.handler;

import com.nike.internal.util.StringUtils;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.ServerSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.trace.netty.RequestWithHeadersNettyAdapter;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.http.HttpRequestTracingUtils;
import com.nike.wingtips.http.RequestWithHeaders;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Sets up distributed tracing for the incoming request. This will find and use trace information on the incoming
 * request as parent trace information, or it will start a new trace if no parent trace info exists in the headers.
 * <p/>
 * This handler should come directly after {@link RequestStateCleanerHandler} in the pipeline so that tracing exists for
 * as long as possible for each request.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class DTraceStartHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final List<String> userIdHeaderKeys;

    private final @NotNull ServerSpanNamingAndTaggingStrategy<Span> spanNamingAndTaggingStrategy;

    protected final RiposteHandlerInternalUtil handlerUtils = RiposteHandlerInternalUtil.DEFAULT_IMPL;

    public DTraceStartHandler(
        List<String> userIdHeaderKeys,
        @NotNull DistributedTracingConfig<Span> distributedTracingConfig
    ) {
        if (distributedTracingConfig == null) {
            throw new IllegalArgumentException("distributedTracingConfig cannot be null");
        }
        
        this.userIdHeaderKeys = userIdHeaderKeys;
        this.spanNamingAndTaggingStrategy = distributedTracingConfig.getServerSpanNamingAndTaggingStrategy();
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) {
        // We may get multiple messages for a single HTTP request since riposte handles each individual HTTP message
        //      object (including individual payload content messages).
        //      We only do the processing for HttpRequest which is the first message in a request chain.
        if (shouldHandleDoChannelReadMessage(msg)) {
            try {
                startTrace((HttpRequest) msg, ctx);
            }
            catch (Throwable t) {
                logger.error(
                    "An unexpected error occurred while starting the distributed tracing overall request span. This "
                    + "exception will be swallowed to avoid breaking the Netty pipeline, but it should be "
                    + "investigated as it shouldn't ever happen.", t
                );
            }
        }

        if (msg instanceof LastHttpContent) {
            HttpProcessingState httpProcessingState = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            // Add the "we received the last bytes of the request on the wire" annotation to the span if possible
            //      and desired.
            Span requestSpan = handlerUtils.getOverallRequestSpan(httpProcessingState);
            if (requestSpan != null && spanNamingAndTaggingStrategy.shouldAddWireReceiveFinishAnnotation()) {
                requestSpan.addTimestampedAnnotationForCurrentTime(
                    spanNamingAndTaggingStrategy.wireReceiveFinishAnnotationName()
                );
            }
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    protected boolean shouldHandleDoChannelReadMessage(Object msg) {
        return (msg instanceof HttpRequest);
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        return shouldHandleDoChannelReadMessage(msgOrEvt);
    }

    protected void startTrace(HttpRequest nettyRequest, ChannelHandlerContext ctx) {
        Tracer tracer = Tracer.getInstance();

        // Start the distributed trace.
        RequestWithHeaders requestWrapper = new RequestWithHeadersNettyAdapter(nettyRequest);
        final Span parentSpan = HttpRequestTracingUtils.fromRequestWithHeaders(requestWrapper, userIdHeaderKeys);

        HttpProcessingState httpProcessingState = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();

        // Create the new trace or child span, depending on what info came through via the Netty HttpRequest.
        //      We'll use the fallback span name to start with, because it's possible to throw an exception when
        //      creating the Riposte RequestInfo from the HttpRequest (which can happen when we call
        //      handlerUtils.createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(...) for certain
        //      bad requests), and we want the log message for *that* to include trace ID.
        Span newSpan;
        if (parentSpan != null) {
            logger.debug("Found Parent Span {}", parentSpan.toString());
            newSpan = tracer.startRequestWithChildSpan(
                parentSpan,
                getFallbackSpanName(nettyRequest)
            );
        }
        else {
            newSpan = tracer.startRequestWithRootSpan(
                getFallbackSpanName(nettyRequest),
                HttpRequestTracingUtils.getUserIdFromRequestWithHeaders(requestWrapper, userIdHeaderKeys)
            );
            logger.debug("Parent Span not found, starting a new trace with root span {}", newSpan);
        }

        // Add the "we received the first bytes of the request on the wire" annotation to the span if desired.
        if (spanNamingAndTaggingStrategy.shouldAddWireReceiveStartAnnotation()) {
            newSpan.addTimestampedAnnotationForCurrentTime(
                spanNamingAndTaggingStrategy.wireReceiveStartAnnotationName()
            );
        }

        // Get the RequestInfo (generating it from the given Netty HttpRequest if necessary), so that
        //      getSpanName() can use it to come up with a better initial span name.
        RequestInfo<?> riposteRequestInfo =
            (httpProcessingState == null)
            ? null
            : handlerUtils.createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(
                nettyRequest, httpProcessingState
            );

        if (riposteRequestInfo != null) {
            // Change the span name based on what the tag strategy wants now that we have a Riposte RequestInfo.
            spanNamingAndTaggingStrategy.changeSpanName(
                newSpan,
                getSpanName(nettyRequest, riposteRequestInfo, spanNamingAndTaggingStrategy)
            );

            // Add request tagging.
            spanNamingAndTaggingStrategy.handleRequestTagging(newSpan, riposteRequestInfo);
        }
    }

    /**
     * Returns the name that should be used for the span surrounding the request. Defaults to whatever {@link
     * ServerSpanNamingAndTaggingStrategy#getInitialSpanName(RequestInfo)} returns, with a fallback
     * of {@link HttpRequestTracingUtils#getFallbackSpanNameForHttpRequest(String, String)} if the naming strategy
     * returned null or blank string.
     *
     * @param nettyRequest The Netty {@link HttpRequest}.
     * @param riposteRequestInfo The Riposte {@link RequestInfo} (if this is null, then the tag strategy and adapter
     * will be ignored, and we'll fallback to basic behavior using the {@code nettyRequest}).
     * @param namingStrategy The {@link ServerSpanNamingAndTaggingStrategy} being used.
     * @return The name that should be used for the span surrounding the request.
     */
    protected @NotNull String getSpanName(
        @NotNull HttpRequest nettyRequest,
        @Nullable RequestInfo<?> riposteRequestInfo,
        @NotNull ServerSpanNamingAndTaggingStrategy<Span> namingStrategy
    ) {
        // Immediately go to the fallback span name if the Riposte RequestInfo is null.
        if (riposteRequestInfo == null) {
            return getFallbackSpanName(nettyRequest);
        }

        // We have a Riposte RequestInfo. Try the naming strategy first.
        String spanNameFromStrategy = namingStrategy.getInitialSpanName(riposteRequestInfo);

        if (StringUtils.isNotBlank(spanNameFromStrategy)) {
            return spanNameFromStrategy;
        }

        // The naming strategy didn't have anything for us. Fall back to something reasonable.
        return getFallbackSpanName(nettyRequest);
    }

    protected @NotNull String getFallbackSpanName(@NotNull HttpRequest nettyRequest) {
        try {
            HttpMethod method = nettyRequest.method();
            String methodName = (method == null) ? null : method.name();
            return HttpRequestTracingUtils.getFallbackSpanNameForHttpRequest(null, methodName);
        }
        catch (Throwable t) {
            logger.error(
                "An unexpected error occurred while trying to extract fallback span name from Netty HttpRequest. "
                + "A hardcoded fallback name will be used as a last resort, however this error should be investigated "
                + "as it shouldn't be possible.", t
            );
            return "UNKNOWN_HTTP_METHOD";
        }
    }

}
