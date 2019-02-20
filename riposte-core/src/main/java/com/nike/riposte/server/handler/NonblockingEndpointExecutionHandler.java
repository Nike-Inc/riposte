package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessageSendFullResponseInfo;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.ServerSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.error.exception.NonblockingEndpointCompletableFutureTimedOut;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.NonblockingEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.wingtips.Span;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

import static com.nike.riposte.util.AsyncNettyHelper.executeOnlyIfChannelIsActive;
import static com.nike.riposte.util.AsyncNettyHelper.functionWithTracingAndMdc;
import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Inspects the current channel state's {@link HttpProcessingState#getEndpointForExecution()} to see if it is a {@link
 * NonblockingEndpoint}. If so, that endpoint will be executed and the returned {@link CompletableFuture} will be
 * adjusted so that we are notified via callback when it is done. The resulting {@link ResponseInfo} will be placed in
 * the channel's state and a Netty event will be fired to get back on the Netty worker thread and complete the
 * pipeline.
 * <p/>
 * This handler should come after {@link RoutingHandler} in the chain to make sure that {@link
 * HttpProcessingState#getEndpointForExecution()} has been populated. It should also come after {@link
 * RequestContentDeserializerHandler} and {@link RequestContentValidationHandler} to make sure the {@link RequestInfo}
 * is fully setup before executing the endpoint.
 */
@SuppressWarnings("WeakerAccess")
public class NonblockingEndpointExecutionHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final @NotNull Executor longRunningTaskExecutor;
    private final long defaultCompletableFutureTimeoutMillis;

    private final @NotNull ServerSpanNamingAndTaggingStrategy<Span> spanTaggingStrategy;

    @SuppressWarnings("ConstantConditions")
    public NonblockingEndpointExecutionHandler(
        @NotNull Executor longRunningTaskExecutor,
        long defaultCompletableFutureTimeoutMillis,
        @NotNull DistributedTracingConfig<Span> distributedTracingConfig
    ) {
        if (longRunningTaskExecutor == null) {
            throw new IllegalArgumentException("longRunningTaskExecutor cannot be null");
        }

        if (distributedTracingConfig == null) {
            throw new IllegalArgumentException("distributedTracingConfig cannot be null");
        }

        this.longRunningTaskExecutor = longRunningTaskExecutor;
        this.defaultCompletableFutureTimeoutMillis = defaultCompletableFutureTimeoutMillis;
        this.spanTaggingStrategy = distributedTracingConfig.getServerSpanNamingAndTaggingStrategy();
    }

    protected boolean shouldHandleDoChannelReadMessage(Object msg, Endpoint<?> endpoint) {
        // This handler should only do something if the endpoint is a NonblockingEndpoint.
        //      Additionally, this handler should only pay attention to Netty HTTP messages. Other messages (e.g. user
        //      event messages) should be ignored.
        return (msg instanceof HttpObject) && (endpoint instanceof NonblockingEndpoint);
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) {
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        Endpoint<?> endpoint = state.getEndpointForExecution();

        if (shouldHandleDoChannelReadMessage(msg, endpoint)) {
            // We only do something when the last chunk of content has arrived.
            if (msg instanceof LastHttpContent) {
                NonblockingEndpoint nonblockingEndpoint = ((NonblockingEndpoint) endpoint);

                // We're supposed to execute the endpoint. There may be pre-endpoint-execution validation logic or
                //      other work that needs to happen before the endpoint is executed, so set up the
                //      CompletableFuture for the endpoint call to only execute if the pre-endpoint-execution
                //      validation/work chain is successful.
                RequestInfo<?> requestInfo = state.getRequestInfo();
                Span endpointExecutionSpan = findEndpointExecutionSpan(state);

                CompletableFuture<ResponseInfo<?>> responseFuture = state
                    .getPreEndpointExecutionWorkChain()
                    .thenCompose(
                        doExecuteEndpointFunction(requestInfo, nonblockingEndpoint, endpointExecutionSpan, ctx)
                    );

                // Register an on-completion callback so we can be notified when the CompletableFuture finishes.
                responseFuture.whenComplete((responseInfo, throwable) -> {
                    // TODO: If something in the state.getPreEndpointExecutionWorkChain() CompletableFuture throws
                    //      an exception before the doExecuteEndpointFunction() can run, then we'll have a situation
                    //      where there's no endpoint.start annotation, but we do get endpoint.finish. This seems odd,
                    //      but also seems to requires some annoying workarounds to prevent (passing some object into
                    //      doExecuteEndpointFunction() to track whether the endpoint was executed, or putting a
                    //      endpointWasExecuted variable into the HttpProcessingState, or etc. Do we care? Is it worth
                    //      the extra hassle?

                    // Add the endpoint.finish span annotation if desired. We have to do this here, because of
                    //      annoying CompletableFuture reasons. See the javadocs for doExecuteEndpointFunction() for
                    //      full details on why this needs to be done here.
                    if (endpointExecutionSpan != null && spanTaggingStrategy.shouldAddEndpointFinishAnnotation()) {
                        addEndpointFinishAnnotation(endpointExecutionSpan, spanTaggingStrategy);
                    }

                    // Kick off the response processing, depending on whether the result is an error or not.
                    if (throwable != null)
                        asyncErrorCallback(ctx, throwable);
                    else
                        asyncCallback(ctx, responseInfo);
                });

                // TODO: We might be able to put the timeout future in an if block in the case that the endpoint
                //      returned an already-completed future (i.e. if responseFuture.isDone() returns true at this
                //      point).

                // Also schedule a timeout check with our Netty event loop to make sure we kill the
                //      CompletableFuture if it goes on too long.
                Long endpointTimeoutOverride = nonblockingEndpoint.completableFutureTimeoutOverrideMillis();
                long timeoutValueToUse = (endpointTimeoutOverride == null)
                                         ? defaultCompletableFutureTimeoutMillis
                                         : endpointTimeoutOverride;
                ScheduledFuture<?> responseTimeoutScheduledFuture = ctx.channel().eventLoop().schedule(() -> {
                    if (!responseFuture.isDone()) {
                        runnableWithTracingAndMdc(
                            () -> logger.error("A non-blocking endpoint's CompletableFuture did not finish within "
                                               + "the allotted timeout ({} milliseconds). Forcibly cancelling it.",
                                               timeoutValueToUse), ctx
                        ).run();
                        @SuppressWarnings("unchecked")
                        Throwable errorToUse = nonblockingEndpoint.getCustomTimeoutExceptionCause(requestInfo, ctx);
                        if (errorToUse == null)
                            errorToUse = new NonblockingEndpointCompletableFutureTimedOut(timeoutValueToUse);
                        responseFuture.completeExceptionally(errorToUse);
                    }
                }, timeoutValueToUse, TimeUnit.MILLISECONDS);

                /*
                    The problem with the scheduled timeout check is that it holds on to the RequestInfo,
                    ChannelHandlerContext, and a bunch of other stuff that *should* become garbage the instant the
                    request finishes, but because of the timeout check it has to wait until the check executes
                    before the garbage is collectible. In high volume servers the default 60 second timeout is way
                    too long and acts like a memory leak and results in garbage collection thrashing if the
                    available memory can be filled within the 60 second timeout. To combat this we cancel the
                    timeout future when the endpoint future finishes. Netty will remove the cancelled timeout future
                    from its scheduled list within a short time, thus letting the garbage be collected.
                */
                responseFuture.whenComplete((responseInfo, throwable) -> {
                    if (!responseTimeoutScheduledFuture.isDone())
                        responseTimeoutScheduledFuture.cancel(false);
                });
            }

            // Whether it was the last chunk or not, we don't want the pipeline to continue since the endpoint was a
            //      NonblockingEndpoint and we need to wait for the CompletableFuture to complete. When the
            //      NonblockingEndpoint processes the request then the pipeline will continue when the CompletableFuture
            //      completes (see asyncCallback() and asyncErrorCallback()).
            return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
        }

        // Not a NonblockingEndpoint, so continue the pipeline in case another endpoint handler is in the pipeline and
        //      wants to deal with it. If no such endpoint handler exists then ExceptionHandlingHandler will cause an
        //      error to be returned to the client.
        return PipelineContinuationBehavior.CONTINUE;
    }

    protected @Nullable Span findEndpointExecutionSpan(@NotNull HttpProcessingState state) {
        Deque<Span> spanStack = state.getDistributedTraceStack();
        return (spanStack == null) ? null : spanStack.peek();
    }

    /**
     * Adds the endpoint.start span annotation to the given span if desired by {@link #spanTaggingStrategy}, and
     * then returns the {@link CompletableFuture} from {@link
     * NonblockingEndpoint#execute(RequestInfo, Executor, ChannelHandlerContext)}.
     *
     * <p>NOTE: Although we'd like to do the endpoint.finish span annotation here in this function for symmetry and
     * encapsulation reasons, we can't. We have two options for attaching a whenComplete() to the result of
     * nonblockingEndpoint.execute() for the purposes of adding the endpoint.finish annotation, but each has problems:
     * <ol>
     *     <li>
     *         If we `return executionFuture.whenComplete()`, and it gets cancelled due to a timeout (for example),
     *         then the original endpoint's future won't be completed with that exception. That's not ok, because we
     *         want users to be able to attach their own whenComplete() in their execution method so they can be
     *         notified of errors like that.
     *     </li>
     *     <li>
     *         If we grab the result of the execution method, append the endpoint.finish whenComplete() separately,
     *         but return the original execution method's CompletableFuture, then there is a race condition in some
     *         circumstances where the response is funneled through {@link #asyncCallback(ChannelHandlerContext,
     *         ResponseInfo)} or {@link #asyncErrorCallback(ChannelHandlerContext, Throwable)}, and the span is
     *         completed before endpoint.finish can be added.
     *     </li>
     * </ol>
     * Therefore we can't do the endpoint.finish annotation here - it will have to be done separately in the code that
     * performs the asyncCallback() / asyncErrorCallback() stuff.
     * 
     * @return The result of calling {@link NonblockingEndpoint#execute(RequestInfo, Executor, ChannelHandlerContext)}
     * on the given {@link NonblockingEndpoint}, after possibly adding the endpoint.start annotation (depending on
     * whether the given {@link Span} is null and {@link #spanTaggingStrategy} is configured to return true for
     * {@link ServerSpanNamingAndTaggingStrategy#shouldAddEndpointStartAnnotation()}).
     */
    protected Function<Void, CompletableFuture<ResponseInfo<?>>> doExecuteEndpointFunction(
        @NotNull RequestInfo<?> requestInfo,
        @NotNull NonblockingEndpoint nonblockingEndpoint,
        @Nullable Span endpointExecutionSpan,
        @NotNull ChannelHandlerContext ctx
    ) {
        return functionWithTracingAndMdc(
            aVoid -> {
                // Add an endpoint.start annotation to the current Span (if desired), to mark when endpoint processing
                //      starts. Surround this annotation stuff with a try/catch to make sure a failure doesn't blow up
                //      endpoint execution (it should never fail in practice, but better safe than sorry).
                try {
                    if (endpointExecutionSpan != null && spanTaggingStrategy.shouldAddEndpointStartAnnotation()) {
                        endpointExecutionSpan.addTimestampedAnnotationForCurrentTime(
                            spanTaggingStrategy.endpointStartAnnotationName()
                        );
                    }
                }
                catch (Throwable t) {
                    logger.error("Unexpected error while annotating Span with endpoint start timestamp.", t);
                }

                // Kick off the endpoint execution.
                //noinspection unchecked
                CompletableFuture<ResponseInfo<?>> executionResult = nonblockingEndpoint.execute(
                    requestInfo, longRunningTaskExecutor, ctx
                );

                //noinspection ConstantConditions
                if (executionResult == null) {
                    throw new NullPointerException("NonblockingEndpoint.execute() cannot return null.");
                }

                return executionResult;
            },
            ctx
        );
    }

    protected void addEndpointFinishAnnotation(Span span, ServerSpanNamingAndTaggingStrategy<Span> strategy) {
        // Don't allow the annotation addition to cause the endpoint execution future to fail if it
        //      fails, by surrounding with try/catch. This should never actually happen, but better
        //      safe than sorry.
        try {
            span.addTimestampedAnnotationForCurrentTime(
                strategy.endpointFinishAnnotationName()
            );
        }
        catch(Throwable t) {
            logger.error(
                "Unexpected error while annotating Span with endpoint finish timestamp.", t
            );
        }
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // To save on extraneous linking/unlinking, we'll do it as-necessary in this class.
        return false;
    }

    protected void asyncCallback(ChannelHandlerContext ctx, ResponseInfo<?> responseInfo) {
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();

        if (responseInfo.isChunkedResponse()) {
            // Whoops, chunked responses are not allowed for this endpoint type.
            asyncErrorCallback(
                ctx,
                new Exception("NonblockingEndpoint execution resulted in a chunked ResponseInfo, when only full "
                              + "ResponseInfos are allowed. offending_endpoint_class=" +
                              state.getEndpointForExecution().getClass().getName())
            );
        }
        else {
            executeOnlyIfChannelIsActive(
                ctx, "NonblockingEndpointExecutionHandler-asyncCallback",
                () -> {
                    // We have to set the ResponseInfo on the state and fire the event while in the
                    //      channel's EventLoop. Otherwise there could be a race condition with an error
                    //      that was fired down the pipe that sets the ResponseInfo on the state first, then
                    //      this comes along and replaces the ResponseInfo (or vice versa).
                    EventExecutor executor = ctx.executor();
                    if (executor.inEventLoop()) {
                        setResponseInfoAndActivatePipelineForResponse(state, responseInfo, ctx);
                    }
                    else {
                        executor.execute(() -> setResponseInfoAndActivatePipelineForResponse(state, responseInfo, ctx));
                    }
                }
            );
        }
    }

    protected void setResponseInfoAndActivatePipelineForResponse(HttpProcessingState state,
                                                                 ResponseInfo<?> responseInfo,
                                                                 ChannelHandlerContext ctx) {
        if (state.isRequestHandled()) {
            logger.warn("The request has already been handled, likely due to an error, so "
                        + "the endpoint's response will be ignored.");
        }
        else {
            state.setResponseInfo(responseInfo, null);
            ctx.fireChannelRead(LastOutboundMessageSendFullResponseInfo.INSTANCE);
        }
    }

    protected void asyncErrorCallback(ChannelHandlerContext ctx, Throwable error) {
        executeOnlyIfChannelIsActive(
            ctx, "NonblockingEndpointExecutionHandler-asyncErrorCallback",
            () -> ctx.fireExceptionCaught(error)
        );
    }
}