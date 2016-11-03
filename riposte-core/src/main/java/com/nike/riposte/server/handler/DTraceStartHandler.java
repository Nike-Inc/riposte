package com.nike.riposte.server.handler;

import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.util.HttpUtils;
import com.nike.trace.netty.RequestWithHeadersNettyAdapter;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.http.HttpRequestTracingUtils;
import com.nike.wingtips.http.RequestWithHeaders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

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

    public DTraceStartHandler(List<String> userIdHeaderKeys) {
        this.userIdHeaderKeys = userIdHeaderKeys;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Since this handler comes before HttpObjectAggregator in the pipeline we may get multiple messages for a
        //      single HTTP request. We only do the processing for HttpRequest which is the first message in a request
        //      chain.
        if (shouldHandleDoChannelReadMessage(msg))
            startTrace((HttpRequest) msg);

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

    protected void startTrace(HttpRequest request) {
        Tracer tracer = Tracer.getInstance();

        // Start the distributed trace.
        RequestWithHeaders requestWrapper = new RequestWithHeadersNettyAdapter(request);
        final Span parentSpan = HttpRequestTracingUtils.fromRequestWithHeaders(requestWrapper, userIdHeaderKeys);

        if (parentSpan != null) {
            logger.debug("Found Parent Span {}", parentSpan.toString());
            tracer.startRequestWithChildSpan(parentSpan, getSpanName(request));
        }
        else {
            Span newSpan = tracer.startRequestWithRootSpan(
                getSpanName(request),
                HttpRequestTracingUtils.getUserIdFromRequestWithHeaders(requestWrapper, userIdHeaderKeys)
            );
            logger.debug("Parent Span not found, starting a new trace with root span {}", newSpan);
        }
    }

    /**
     * @return Span name appropriate for a new trace span for this request
     */
    protected String getSpanName(HttpRequest request) {
        // Try the servlet path first, and fall back to the raw request URI.
        String uri = request.getUri();
        String path = HttpUtils.extractPath(uri);

        // Include the HTTP method in the returned value to help delineate which endpoint this request represents.
        return request.getMethod().name() + '_' + path;
    }

}
