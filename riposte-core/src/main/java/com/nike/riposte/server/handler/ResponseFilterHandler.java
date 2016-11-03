package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessageSendFullResponseInfo;
import com.nike.riposte.server.channelpipeline.message.OutboundMessageSendHeadersChunkFromResponseInfo;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.filter.RequestAndResponseFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_EXCEPTION_CAUGHT;

/**
 * Handler for executing the response filtering side of the {@link RequestAndResponseFilter}s associated with this
 * application. See the javadocs on {@link RequestAndResponseFilter} for more information about the rules this handler
 * follows.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
@ChannelHandler.Sharable
public class ResponseFilterHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final List<RequestAndResponseFilter> filtersInResponseProcessingOrder;

    public ResponseFilterHandler(List<RequestAndResponseFilter> filtersInRequestProcessingOrder) {
        this.filtersInResponseProcessingOrder =
            (filtersInRequestProcessingOrder == null)
            ? Collections.emptyList()
            : reverseList(new ArrayList<>(filtersInRequestProcessingOrder));
    }

    protected <T> List<T> reverseList(List<T> listToReverse) {
        Collections.reverse(listToReverse);
        return listToReverse;
    }

    protected ResponseInfo<?> responseInfoUpdateNoNulls(RequestAndResponseFilter filter, ResponseInfo<?> orig,
                                                        ResponseInfo<?> updated) {
        if (updated == null)
            return orig;

        if (!orig.getClass().equals(updated.getClass())) {
            logger.error(
                "The class of ResponseInfo returned by a RequestAndResponseFilter does not match the original "
                + "ResponseInfo's class. This is not allowed - full responses must filter to full responses and "
                + "chunked responses must filter to chunked responses. The result of this specific filter will be "
                + "ignored in favor of the original response. "
                + "filter_class={}, orig_response_info_class={}, updated_response_info_class={}",
                filter.getClass().getName(), orig.getClass().getName(), updated.getClass().getName());
            return orig;
        }

        return updated;
    }

    protected void executeResponseFilters(ChannelHandlerContext ctx) {
        try {
            HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            // If response sending has already started then there's no point in doing any filters - this may happen if
            //      doExceptionCaught() catches secondary exceptions after the response has started sending
            //      (for example).
            if (state.isResponseSendingStarted())
                return;

            // RequestHasBeenHandledVerificationHandler should have made sure that state.getResponseInfo() is not null.
            ResponseInfo<?> currentResponseInfo = state.getResponseInfo();
            for (RequestAndResponseFilter filter : filtersInResponseProcessingOrder) {
                try {
                    currentResponseInfo = responseInfoUpdateNoNulls(
                        filter,
                        currentResponseInfo,
                        filter.filterResponse(currentResponseInfo, state.getRequestInfo(), ctx)
                    );
                }
                catch (Throwable ex) {
                    logger.error(
                        "An error occurred while processing a request filter. This error will be ignored and the "
                        + "filtering/processing will continue normally, however this error should be fixed (filters "
                        + "should never throw errors). filter_class={}", filter.getClass().getName(), ex);
                }
            }
            state.setResponseInfo(currentResponseInfo);
        }
        catch (Throwable ex) {
            logger.error(
                "An error occurred while setting up to process response filters. This error will be ignored and the "
                + "pipeline will continue normally without any filtering having occurred, however this error should be "
                + "fixed (it should be impossible to reach here).", ex);
        }
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (shouldHandleDoChannelReadMessage(msg))
            executeResponseFilters(ctx);

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    public PipelineContinuationBehavior doExceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // Exceptions should always execute filters.
        executeResponseFilters(ctx);

        return PipelineContinuationBehavior.CONTINUE;
    }

    protected boolean shouldHandleDoChannelReadMessage(Object msg) {
        // We only want to do response filtering on the first chunk of a response. At this point in the netty pipeline
        //      we should only ever see extensions of OutboundMessage.
        return (msg instanceof OutboundMessageSendHeadersChunkFromResponseInfo
                || msg instanceof LastOutboundMessageSendFullResponseInfo);
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        return shouldHandleDoChannelReadMessage(msgOrEvt) || (methodToExecute == DO_EXCEPTION_CAUGHT);
    }
}
