package com.nike.riposte.client.asynchttp;

import com.nike.fastbreak.CircuitBreaker;
import com.nike.internal.util.Pair;
import com.nike.internal.util.StringUtils;
import com.nike.riposte.server.config.distributedtracing.SpanNamingAndTaggingStrategy;
import com.nike.riposte.util.AsyncNettyHelper;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.http.HttpRequestTracingUtils;

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.Response;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.nike.riposte.util.AsyncNettyHelper.linkTracingAndMdcToCurrentThread;

/**
 * Extension of {@link org.asynchttpclient.AsyncCompletionHandler} that handles distributed tracing and MDC issues so
 * that the dtrace and MDC info you want are attached to the thread performing the work for the downstream call. The
 * {@link #completableFutureResponse} you pass in will be completed or completed exceptionally depending on the result
 * of the downstream call, and it will be completed with the result of the {@link #responseHandlerFunction} you pass
 * in.
 * <p/>
 * Used by {@link AsyncHttpClientHelper}
 *
 * @author Nic Munroe
 */
@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType"})
class AsyncCompletionHandlerWithTracingAndMdcSupport<O> extends AsyncCompletionHandler<Response> {

    private static final Logger logger = LoggerFactory.getLogger(AsyncCompletionHandlerWithTracingAndMdcSupport.class);

    /**
     * The {@link CompletableFuture} that should be completed with {@link #responseHandlerFunction}'s value (or
     * completed exceptionally if an error occurs) when the downstream call returns.
     */
    protected final CompletableFuture<O> completableFutureResponse;
    /**
     * The handler that will get notified with the downstream call's response. The value of {@link
     * AsyncResponseHandler#handleResponse(Response)} will be used to complete {@link #completableFutureResponse}.
     */
    protected final AsyncResponseHandler<O> responseHandlerFunction;
    /**
     * Whether or not the downstream call should be surrounded with a subspan. If true then {@link
     * #distributedTraceStackToUse} will have a subspan placed on top, otherwise it will be used as-is.
     */
    protected final boolean performSubSpanAroundDownstreamCalls;
    /**
     * The distributed tracing span stack to use for the downstream call. If {@link
     * #performSubSpanAroundDownstreamCalls} is true then a new subspan will be placed on top of this, otherwise it will
     * be used as-is.
     */
    protected final Deque<Span> distributedTraceStackToUse;
    /**
     * The MDC context to associate with the downstream call.
     */
    protected final Map<String, String> mdcContextToUse;
    /**
     * The circuit breaker manual mode task to notify of response events or exceptions, or empty if circuit breaking has
     * been disabled for this call.
     */
    protected final Optional<CircuitBreaker.ManualModeTask<Response>> circuitBreakerManualTask;
    /**
     * A copy of the {@link RequestBuilderWrapper} that will be used to execute the HTTP client call, with ony the
     * HTTP method and URL populated. This copy performs two purposes - it keeps us from holding onto the original,
     * which may have a large body that we don't want to hold onto, and it lets the original be reused (i.e. adjusting
     * HTTP method and/or URL, etc) without affecting this class' referencing of the original values.
     *
     */
    protected final RequestBuilderWrapper rbwCopyWithHttpMethodAndUrlOnly;
    /**
     * The {@link SpanNamingAndTaggingStrategy} to use when creating subspan names and response/error tagging for the
     * subspan (only used if {@link #performSubSpanAroundDownstreamCalls} is true).
     */
    protected final SpanNamingAndTaggingStrategy<RequestBuilderWrapper, Response, Span> tagAndNamingStrategy;

    /**
     * @param completableFutureResponse
     *     The {@link CompletableFuture} that should be completed with {@code responseHandlerFunction}'s value (or
     *     completed exceptionally if an error occurs) when the downstream call returns.
     * @param responseHandlerFunction
     *     The handler that will get notified with the downstream call's response. The value of {@link
     *     AsyncResponseHandler#handleResponse(Response)} will be used to complete {@code completableFutureResponse}.
     * @param performSubSpanAroundDownstreamCalls
     *     Whether or not the downstream call should be surrounded with a subspan. If true then {@code
     *     distributedTraceStackToUse} will have a subspan placed on top, otherwise it will be used as-is.
     * @param requestBuilderWrapper The {@link RequestBuilderWrapper} that will be used to execute the HTTP client call.
     * @param circuitBreakerManualTask
     *     The circuit breaker manual mode task to notify of response events or exceptions, or empty if circuit breaking
     *     has been disabled for this call.
     * @param distributedTraceStackToUse
     *     The distributed trace stack to use for the downstream call. If {@code performSubSpanAroundDownstreamCalls} is
     *     true then a new subspan will be placed on top of this, otherwise it will be used as-is.
     * @param mdcContextToUse The MDC context to associate with the downstream call.
     * @param tagAndNamingStrategy The {@link SpanNamingAndTaggingStrategy} to use when creating subspan names and
     * response/error tagging for the subspan (only used if {@link #performSubSpanAroundDownstreamCalls} is true).
     */
    AsyncCompletionHandlerWithTracingAndMdcSupport(
            CompletableFuture<O> completableFutureResponse,
            AsyncResponseHandler<O> responseHandlerFunction,
            boolean performSubSpanAroundDownstreamCalls,
            RequestBuilderWrapper requestBuilderWrapper,
            Optional<CircuitBreaker.ManualModeTask<Response>> circuitBreakerManualTask,
            Deque<Span> distributedTraceStackToUse,
            Map<String, String> mdcContextToUse,
            SpanNamingAndTaggingStrategy<RequestBuilderWrapper, Response, Span> tagAndNamingStrategy
    ) {
        this.completableFutureResponse = completableFutureResponse;
        this.responseHandlerFunction = responseHandlerFunction;
        this.performSubSpanAroundDownstreamCalls = performSubSpanAroundDownstreamCalls;
        this.circuitBreakerManualTask = circuitBreakerManualTask;
        this.rbwCopyWithHttpMethodAndUrlOnly = new RequestBuilderWrapper(
                requestBuilderWrapper.getUrl(),
                requestBuilderWrapper.getHttpMethod(),
                null,
                null,
                true
        );
        this.tagAndNamingStrategy = tagAndNamingStrategy;

        // Grab the calling thread's dtrace stack and MDC info so we can set it back when this constructor completes.
        Pair<Deque<Span>, Map<String, String>> originalThreadInfo = null;

        try {
            // Do a subspan around the downstream call if desired.
            if (performSubSpanAroundDownstreamCalls) {
                // Start by setting up the distributed trace stack and MDC for the call as specified in the method
                //      arguments, and grab the return value so we have the original calling thread's dtrace stack and
                //      MDC info (used to set everything back to original state when this constructor completes).
                originalThreadInfo = linkTracingAndMdcToCurrentThread(distributedTraceStackToUse, mdcContextToUse);

                // Then add the subspan.
                String spanName = getSubspanSpanName(requestBuilderWrapper, tagAndNamingStrategy);
                // Start a new child/subspan for this call if possible, falling back to a new trace (rather
                //      than child/subspan) if there's no current span on the thread. The
                //      startSpanInCurrentContext() method will do the right thing here in either case.
                Tracer.getInstance().startSpanInCurrentContext(spanName, Span.SpanPurpose.CLIENT);

                // Since we modified the stack/MDC we need to update the args that will be used for the downstream call.
                distributedTraceStackToUse = Tracer.getInstance().getCurrentSpanStackCopy();
                mdcContextToUse = MDC.getCopyOfContextMap();
            }

            this.distributedTraceStackToUse = distributedTraceStackToUse;
            this.mdcContextToUse = mdcContextToUse;
        } finally {
            // Reset the tracing and MDC info to what it was when the constructor was called if we messed around with
            //      stuff. If originalThreadInfo is null then nothing needs to be done.
            if (originalThreadInfo != null)
                AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread(originalThreadInfo);
        }
    }

    /**
     * @return The span that will be used for the downstream call, or null if no span will be used.
     */
    public Span getSpanForCall() {
        if (distributedTraceStackToUse == null || distributedTraceStackToUse.isEmpty())
            return null;

        return distributedTraceStackToUse.peek();
    }

    /**
     * Returns the name that should be used for the subspan surrounding the call. Defaults to whatever {@link
     * SpanNamingAndTaggingStrategy#getInitialSpanName(Object)} returns, with a fallback
     * of {@link HttpRequestTracingUtils#getFallbackSpanNameForHttpRequest(String, String)} if the naming strategy
     * returned null or blank string. You can override this method to return something else if you want different
     * behavior and you don't want to adjust the naming strategy or adapter.
     *
     * @param request        The request that is about to be executed.
     * @param namingStrategy The {@link SpanNamingAndTaggingStrategy} being used.
     * @return The name that should be used for the subspan surrounding the call.
     */
    protected @NotNull String getSubspanSpanName(
            @NotNull RequestBuilderWrapper request,
            @NotNull SpanNamingAndTaggingStrategy<RequestBuilderWrapper, ?, ?> namingStrategy
    ) {
        // Try the naming strategy first.
        String subspanNameFromStrategy = namingStrategy.getInitialSpanName(request);

        if (StringUtils.isNotBlank(subspanNameFromStrategy)) {
            return subspanNameFromStrategy;
        }

        // The naming strategy didn't have anything for us. Fall back to something reasonable.
        return HttpRequestTracingUtils.getFallbackSpanNameForHttpRequest(
                "async_downstream_call", request.httpMethod
        );
    }

    @Override
    public Response onCompleted(Response response) {
        Pair<Deque<Span>, Map<String, String>> originalThreadInfo = null;

        try {
            // Link up the distributed tracing and MDC information to the current thread
            originalThreadInfo = linkTracingAndMdcToCurrentThread(distributedTraceStackToUse, mdcContextToUse);

            // Notify the circuit breaker of an event.
            try {
                circuitBreakerManualTask.ifPresent(cb -> cb.handleEvent(response));
            } catch (Throwable t) {
                logger.error(
                        "Circuit breaker threw an exception during handleEvent. This should never happen and means the "
                                + "CircuitBreaker is malfunctioning. Ignoring exception.", t
                );
            }

            // If a subspan was started for the downstream call, it should now be completed
            if (performSubSpanAroundDownstreamCalls) {
                Span spanAroundCall = Tracer.getInstance().getCurrentSpan();

                // Handle the final span naming and response tagging.
                tagAndNamingStrategy.handleResponseTaggingAndFinalSpanName(
                        spanAroundCall, rbwCopyWithHttpMethodAndUrlOnly, response, null
                );

                // The Span.close() method will do the right thing whether or not this is an overall request span or
                //      subspan.
                spanAroundCall.close();
            }

            // If the completableFutureResponse is already done it means we were cancelled or some other error occurred,
            //      and we should not do any more processing here.
            if (completableFutureResponse.isDone())
                return response;

            // Pass the response to our responseHandlerFunction to get the resulting object to complete the
            //      completableFutureResponse with.
            try {
                O responseInfo = responseHandlerFunction.handleResponse(response);
                completableFutureResponse.complete(responseInfo);
            } catch (Throwable throwable) {
                // responseHandlerFunction threw an error. Complete completableFutureResponse exceptionally.
                completableFutureResponse.completeExceptionally(throwable);
            }

            return response;
        } finally {
            AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread(originalThreadInfo);
        }
    }

    @Override
    public void onThrowable(Throwable t) {
        Pair<Deque<Span>, Map<String, String>> originalThreadInfo = null;

        try {
            // Link up the distributed trace and MDC information to the current thread
            originalThreadInfo =
                    linkTracingAndMdcToCurrentThread(distributedTraceStackToUse, mdcContextToUse);

            // Notify the circuit breaker of an exception.
            try {
                circuitBreakerManualTask.ifPresent(cb -> cb.handleException(t));
            } catch (Throwable cbError) {
                logger.error(
                        "Circuit breaker threw an exception during handleException. This should never happen and means the "
                                + "CircuitBreaker is malfunctioning. Ignoring exception.", cbError
                );
            }

            // If a subspan was started for the downstream call, it should now be completed
            if (performSubSpanAroundDownstreamCalls) {
                Span spanAroundCall = Tracer.getInstance().getCurrentSpan();

                // Handle the final span naming and response tagging.
                tagAndNamingStrategy.handleResponseTaggingAndFinalSpanName(
                        spanAroundCall, rbwCopyWithHttpMethodAndUrlOnly, null, t
                );

                // The Span.close() method will do the right thing whether or not this is an overall request span or
                //      subspan.
                spanAroundCall.close();
            }

            // If the completableFutureResponse is already done it means we were cancelled or some other error occurred,
            //      and we should not do any more processing here.
            if (completableFutureResponse.isDone())
                return;

            // Complete the completableFutureResponse with the exception.
            completableFutureResponse.completeExceptionally(t);
        } finally {
            AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread(originalThreadInfo);
        }
    }
}
