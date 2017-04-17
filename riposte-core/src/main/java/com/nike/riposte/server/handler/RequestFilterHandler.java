package com.nike.riposte.server.handler;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessageSendFullResponseInfo;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.filter.RequestAndResponseFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Handler for executing the request filtering side of the {@link RequestAndResponseFilter}s associated with this
 * application. See the javadocs on {@link RequestAndResponseFilter} for more information about the rules this handler
 * follows.
 *
 * @author Nic Munroe
 */
@ChannelHandler.Sharable
@SuppressWarnings("WeakerAccess")
public class RequestFilterHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final List<RequestAndResponseFilter> filters;

    public RequestFilterHandler(List<RequestAndResponseFilter> filters) {
        this.filters = (filters == null) ? Collections.emptyList() : filters;
    }

    protected RequestInfo<?> requestInfoUpdateNoNulls(RequestInfo<?> orig, RequestInfo<?> updated) {
        if (updated == null)
            return orig;

        return updated;
    }

    protected PipelineContinuationBehavior handleFilterLogic(
        ChannelHandlerContext ctx,
        Object msg,
        BiFunction<RequestAndResponseFilter, RequestInfo, RequestInfo> normalFilterCall,
        BiFunction<RequestAndResponseFilter, RequestInfo, Pair<RequestInfo, Optional<ResponseInfo<?>>>> shortCircuitFilterCall
    ) {
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        RequestInfo<?> currentReqInfo = state.getRequestInfo();

        // Run through each filter.
        for (RequestAndResponseFilter filter : filters) {
            try {
                // See if we're supposed to do short circuit call or not
                if (filter.isShortCircuitRequestFilter()) {
                    Pair<RequestInfo, Optional<ResponseInfo<?>>> result =
                        shortCircuitFilterCall.apply(filter, currentReqInfo);

                    if (result != null) {
                        currentReqInfo = requestInfoUpdateNoNulls(currentReqInfo, result.getLeft());
                        // See if we need to short circuit.
                        ResponseInfo<?> responseInfo = (result.getRight() == null)
                                                       ? null
                                                       : result.getRight().orElse(null);
                        if (responseInfo != null) {
                            // Yep, short circuit. Set the request and response on the state based on the results, fire
                            //      a short circuit "we're all done" event down the pipeline, and return
                            //      "do not continue" for *this* event. Also make sure the ResponseInfo we got back was
                            //      full, not chunked.
                            if (responseInfo.isChunkedResponse()) {
                                throw new IllegalStateException("RequestAndResponseFilter should never return a "
                                                                + "chunked ResponseInfo when short circuiting.");
                            }

                            state.setRequestInfo(currentReqInfo);
                            state.setResponseInfo(responseInfo);

                            // Fire the short-circuit event that will get the desired response info sent to the caller.
                            ctx.fireChannelRead(LastOutboundMessageSendFullResponseInfo.INSTANCE);

                            // Tell this event to stop where it is.
                            return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
                        }
                    }
                }
                else {
                    currentReqInfo =
                        requestInfoUpdateNoNulls(currentReqInfo, normalFilterCall.apply(filter, currentReqInfo));
                }
            }
            catch (Throwable ex) {
                logger.error(
                    "An error occurred while processing a request filter. This error will be ignored and the "
                    + "filtering/processing will continue normally, however this error should be fixed (filters should "
                    + "never throw errors). filter_class={}", filter.getClass().getName(), ex
                );
            }
        }

        // All the filters have been processed, so set the state to whatever the current request info says.
        state.setRequestInfo(currentReqInfo);

        // No short circuit if we reach here, so continue normally.
        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            BiFunction<RequestAndResponseFilter, RequestInfo, RequestInfo> normalFilterCall =
                (filter, request) -> filter.filterRequestFirstChunkNoPayload(request, ctx);

            BiFunction<RequestAndResponseFilter, RequestInfo, Pair<RequestInfo, Optional<ResponseInfo<?>>>>
                shortCircuitFilterCall =
                (filter, request) -> filter.filterRequestFirstChunkWithOptionalShortCircuitResponse(request, ctx);

            return handleFilterLogic(ctx, msg, normalFilterCall, shortCircuitFilterCall);
        }

        if (msg instanceof LastHttpContent) {
            BiFunction<RequestAndResponseFilter, RequestInfo, RequestInfo> normalFilterCall =
                (filter, request) -> filter.filterRequestLastChunkWithFullPayload(request, ctx);

            BiFunction<RequestAndResponseFilter, RequestInfo, Pair<RequestInfo, Optional<ResponseInfo<?>>>>
                shortCircuitFilterCall =
                (filter, request) -> filter.filterRequestLastChunkWithOptionalShortCircuitResponse(request, ctx);

            return handleFilterLogic(ctx, msg, normalFilterCall, shortCircuitFilterCall);
        }

        // Not the first or last chunk. No filters were executed, so continue normally.
        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        return (msgOrEvt instanceof HttpRequest) || (msgOrEvt instanceof LastHttpContent);
    }
}
