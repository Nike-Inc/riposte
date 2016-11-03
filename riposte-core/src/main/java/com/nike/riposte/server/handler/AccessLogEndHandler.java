package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessage;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.logging.AccessLogger;
import com.nike.riposte.util.asynchelperwrapper.ChannelFutureListenerWithTracingAndMdc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponse;

import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Writes to the access log. We MUST have the {@link ResponseInfo} object populated in the {@link HttpProcessingState}
 * before this handler is called.
 *
 * This handler should be placed as late in the pipeline as possible so we have a relatively accurate value for the
 * elapsed time, however it must be BEFORE the channel state is finalized/cleaned by {@link
 * com.nike.riposte.server.handler.ChannelPipelineFinalizerHandler}, and ideally it would be before distributed tracing
 * is finished.
 */
@SuppressWarnings("WeakerAccess")
public class AccessLogEndHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final AccessLogger accessLogger;

    public AccessLogEndHandler(AccessLogger accessLogger) {
        super();
        this.accessLogger = accessLogger;
    }

    protected void doAccessLogging(ChannelHandlerContext ctx) throws Exception {
        if (accessLogger == null)
            return;

        HttpProcessingState httpProcessingState = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();

        if (httpProcessingState == null) {
            runnableWithTracingAndMdc(
                () -> logger.warn("HttpProcessingState is null. This shouldn't happen."), ctx
            ).run();
        }

        // Due to multiple messages and exception possibilities/interactions it's possible we've already done the access
        //      logging for this request, so make sure we only do it if appropriate
        if (httpProcessingState != null && !httpProcessingState.isAccessLogCompletedOrScheduled()) {
            Instant startTime = httpProcessingState.getRequestStartTime();
            ResponseInfo responseInfo = httpProcessingState.getResponseInfo();
            HttpResponse actualResponseObject = httpProcessingState.getActualResponseObject();
            RequestInfo requestInfo = httpProcessingState.getRequestInfo();

            ChannelFutureListener doTheAccessLoggingOperation = new ChannelFutureListenerWithTracingAndMdc(
                (channelFuture) -> accessLogger.log(
                    requestInfo, actualResponseObject, responseInfo,
                    Instant.now().minusMillis(startTime.toEpochMilli()).toEpochMilli()
                ),
                ctx
            );

            // If there was no response sent then do the access logging now (should only happen under rare error
            //      conditions), otherwise do it when the response finishes.
            if (!httpProcessingState.isResponseSendingLastChunkSent())
                doTheAccessLoggingOperation.operationComplete(null);
            else
                httpProcessingState.getResponseWriterFinalChunkChannelFuture().addListener(doTheAccessLoggingOperation);

            httpProcessingState.setAccessLogCompletedOrScheduled(true);
        }
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LastOutboundMessage) {
            doAccessLogging(ctx);
        }
        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    public PipelineContinuationBehavior doExceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // TODO: Catch the case where the response was started earlier, and then this exception was thrown before the
        //       response was fully sent. This indicates a broken response (an extremely rare case) and we don't want to
        //       do a normal access log for it. What should we do? Or should the detection be in AccessLogger itself? If
        //       so, what should it do?
        doAccessLogging(ctx);

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
