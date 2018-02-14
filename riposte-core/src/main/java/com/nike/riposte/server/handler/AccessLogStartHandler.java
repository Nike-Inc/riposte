package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Performs initialization logic necessary to write to the access logs. You must include {@link
 * com.nike.riposte.server.handler.AccessLogEndHandler} later in the pipeline to actually write to the access log.
 *
 * <p>NOTE: This sets the {@link HttpProcessingState#getRequestStartTime()}, which is also used by {@link
 * com.nike.riposte.metrics.MetricsListener} to calculate elapsed request time. So if you remove this handler you'll be
 * breaking metrics as well.
 *
 * <p>This should be placed near the beginning of the channel pipeline so that we have an accurate elapsed time however
 * it must be placed AFTER the request state has been cleaned by {@link
 * com.nike.riposte.server.handler.RequestStateCleanerHandler}, and ideally it would be placed after distributed tracing
 * has started.
 */
public class AccessLogStartHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public static final String REQUEST_PAYLOAD_TRANSFER_TIME_NANOS_REQUEST_ATTR_KEY =
        AccessLogStartHandler.class.getName() + "-RequestPayloadTransferTimeNanos";

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            HttpProcessingState httpProcessingState = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            if (httpProcessingState != null) {
                httpProcessingState.setRequestStartTime(Instant.now());
                httpProcessingState.setRequestStartTimeNanos(System.nanoTime());
            }
            else {
                runnableWithTracingAndMdc(
                    () -> logger.warn("HttpProcessingState is null reading HttpRequest. This shouldn't happen."),
                    ctx
                ).run();
            }
        }

        if (msg instanceof LastHttpContent) {
            HttpProcessingState httpProcessingState = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            if (httpProcessingState != null) {
                // Capture the current nano time as the time when the last chunk of the payload was received.
                long lastChunkArrivedTimeNanos = System.nanoTime();
                httpProcessingState.setRequestLastChunkArrivedTimeNanos(lastChunkArrivedTimeNanos);

                // Calculate and set a request attribute for the payload transfer time from the caller.
                RequestInfo<?> requestInfo = httpProcessingState.getRequestInfo();
                if (requestInfo != null) {
                    Long reqStartTimeNanos = httpProcessingState.getRequestStartTimeNanos();
                    if (reqStartTimeNanos != null) {
                        requestInfo.addRequestAttribute(REQUEST_PAYLOAD_TRANSFER_TIME_NANOS_REQUEST_ATTR_KEY,
                                                        (lastChunkArrivedTimeNanos - reqStartTimeNanos));
                    }
                    else {
                        runnableWithTracingAndMdc(
                            () -> logger.warn("HttpProcessingState.getRequestStartTimeNanos() is null. "
                                              + "This shouldn't happen."),
                            ctx
                        ).run();
                    }

                }
                else {
                    runnableWithTracingAndMdc(
                        () -> logger.warn("RequestInfo is null. This shouldn't happen."),
                        ctx
                    ).run();
                }
            }
            else {
                runnableWithTracingAndMdc(
                    () -> logger.warn("HttpProcessingState is null reading LastHttpContent. This shouldn't happen."),
                    ctx
                ).run();
            }
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // This class only logs in very rare circumstances, and nothing that happens in this class should cause logging
        //      to happen elsewhere. Therefore we will default to *not* linking/unlinking tracing info to save on
        //      processing, and will manually attach tracing info in the rare case where we need to log anything.
        return false;
    }
}
