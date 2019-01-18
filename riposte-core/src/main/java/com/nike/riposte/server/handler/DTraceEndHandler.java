package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessage;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.util.asynchelperwrapper.ChannelFutureListenerWithTracingAndMdc;
import com.nike.wingtips.Tracer;

import java.util.function.Consumer;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;

import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Completes the distributed tracing for the incoming request.
 * <p/>
 * This handler should come directly before {@link ChannelPipelineFinalizerHandler} in the pipeline so that tracing
 * exists for as long as possible for each request.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class DTraceEndHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Consumer<ChannelFuture> postResponseSentOperation = (channelFuture) -> completeCurrentSpan();

    protected void endDtrace(ChannelHandlerContext ctx) {
        HttpProcessingState httpProcessingState = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();

        // Due to multiple messages and exception possibilities/interactions it's possible we've already ended the trace
        //      for this request, so make sure we only complete it if appropriate.
        if (httpProcessingState == null) {
            // How did we get here?? Something major blew up. Oh well, attempt to complete the trace.
            runnableWithTracingAndMdc(
                this::completeCurrentSpan,
                ctx
            ).run();
        }
        else if (!httpProcessingState.isTraceCompletedOrScheduled()) {
            // We have a state and the trace has not been completed yet. If there was no response sent then complete the
            //      trace now (should only happen under rare error conditions), otherwise complete it when the response
            //      finishes. In either case we perform response tagging and final span name if it hasn't already
            //      been done (which it should be, but just in case...)
            httpProcessingState.setTraceCompletedOrScheduled(true);
            httpProcessingState.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone();

            if (!httpProcessingState.isResponseSendingLastChunkSent()) {
                runnableWithTracingAndMdc(
                    this::completeCurrentSpan,
                    ctx
                ).run();
            }
            else {
                httpProcessingState.getResponseWriterFinalChunkChannelFuture().addListener(
                    new ChannelFutureListenerWithTracingAndMdc(postResponseSentOperation, ctx));
            }
        }
    }

    protected void completeCurrentSpan() {
        Tracer.getInstance().completeRequestSpan();
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) {
        if (shouldHandleDoChannelReadMessage(msg))
            endDtrace(ctx);

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    public PipelineContinuationBehavior doExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        endDtrace(ctx);

        return PipelineContinuationBehavior.CONTINUE;
    }

    protected boolean shouldHandleDoChannelReadMessage(Object msg) {
        return (msg instanceof LastOutboundMessage);
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // To save on extraneous linking/unlinking, we'll do it as-necessary in this class.
        return false;
    }
}
