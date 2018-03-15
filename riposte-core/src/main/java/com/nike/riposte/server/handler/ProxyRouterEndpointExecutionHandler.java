package com.nike.riposte.server.handler;

import com.nike.backstopper.exception.WrapperException;
import com.nike.fastbreak.CircuitBreaker;
import com.nike.fastbreak.CircuitBreaker.ManualModeTask;
import com.nike.fastbreak.CircuitBreakerDelegate;
import com.nike.internal.util.Pair;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.StreamingCallback;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.StreamingChannel;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessageSendLastContentChunk;
import com.nike.riposte.server.channelpipeline.message.OutboundMessageSendContentChunk;
import com.nike.riposte.server.channelpipeline.message.OutboundMessageSendHeadersChunkFromResponseInfo;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.ProxyRouterEndpoint.DownstreamRequestFirstChunkInfo;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.impl.RiposteInternalRequestInfo;
import com.nike.riposte.util.AsyncNettyHelper;
import com.nike.riposte.util.HttpUtils;
import com.nike.wingtips.Span;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;
import io.netty.util.concurrent.EventExecutor;

import static com.nike.fastbreak.CircuitBreakerForHttpStatusCode.getDefaultHttpStatusCodeCircuitBreakerForKey;
import static com.nike.riposte.util.AsyncNettyHelper.executeOnlyIfChannelIsActive;
import static com.nike.riposte.util.AsyncNettyHelper.functionWithTracingAndMdc;
import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Executes proxy/router endpoints. Similar to {@link StreamingAsyncHttpClient} this class is one of those
 * proof-of-concepts that morphed into production code through a slow series of feature additions, bandaids, and duct
 * tape. It is fairly complex and difficult to understand and follow. It will be refactored and cleaned up at some
 * point, likely as part of the {@link StreamingAsyncHttpClient} replacement/refactor. Until then this code is actually
 * fairly well battle tested and proven reliable in high throughput production edgerouter and domain router type
 * scenarios.
 */
@SuppressWarnings("WeakerAccess")
public class ProxyRouterEndpointExecutionHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Executor longRunningTaskExecutor;
    private final StreamingAsyncHttpClient streamingAsyncHttpClient;
    private final long defaultCompletableFutureTimeoutMillis;

    public static final String DOWNSTREAM_CALL_TIME_NANOS_REQUEST_ATTR_KEY = "proxyRouterDownstreamCallTimeNanos";
    public static final String DOWNSTREAM_CALL_PATH_REQUEST_ATTR_KEY = "proxyRouterDownstreamCallPath";
    public static final String DOWNSTREAM_CALL_CONNECTION_SETUP_TIME_NANOS_REQUEST_ATTR_KEY =
        ProxyRouterEndpointExecutionHandler.class + "-ProxyRouterDownstreamConnectionSetupTimeNanos";

    public ProxyRouterEndpointExecutionHandler(Executor longRunningTaskExecutor,
                                               StreamingAsyncHttpClient streamingAsyncHttpClient,
                                               long defaultCompletableFutureTimeoutMillis) {
        this.longRunningTaskExecutor = longRunningTaskExecutor;
        this.streamingAsyncHttpClient = streamingAsyncHttpClient;
        this.defaultCompletableFutureTimeoutMillis = defaultCompletableFutureTimeoutMillis;
    }

    protected ProxyRouterProcessingState getOrCreateProxyRouterProcessingState(ChannelHandlerContext ctx) {
        Attribute<ProxyRouterProcessingState> proxyRouterStateAttribute =
            ChannelAttributes.getProxyRouterProcessingStateForChannel(ctx);

        ProxyRouterProcessingState proxyRouterState = proxyRouterStateAttribute.get();
        if (proxyRouterState == null) {
            proxyRouterState = new ProxyRouterProcessingState();
            proxyRouterStateAttribute.set(proxyRouterState);
        }

        return proxyRouterState;
    }

    protected boolean shouldHandleDoChannelReadMessage(Object msg, Endpoint<?> endpoint) {
        // This handler should only do something if the endpoint is a ProxyRouterEndpoint.
        //      Additionally, this handler should only pay attention to Netty HTTP messages. Other messages (e.g. user
        //      event messages) should be ignored.
        return (msg instanceof HttpObject)
               && (endpoint != null)
               && (endpoint instanceof ProxyRouterEndpoint);
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        Endpoint<?> endpoint = state.getEndpointForExecution();

        if (shouldHandleDoChannelReadMessage(msg, endpoint)) {
            ProxyRouterProcessingState proxyRouterState = getOrCreateProxyRouterProcessingState(ctx);
            ProxyRouterEndpoint endpointProxyRouter = ((ProxyRouterEndpoint) endpoint);
            RequestInfo<?> requestInfo = state.getRequestInfo();

            if (msg instanceof HttpRequest) {
                if (requestInfo instanceof RiposteInternalRequestInfo) {
                    // Tell this RequestInfo that we'll be managing the release of content chunks, so that when
                    //      RequestInfo.releaseAllResources() is called we don't have extra reference count removals.
                    ((RiposteInternalRequestInfo)requestInfo).contentChunksWillBeReleasedExternally();
                }

                // We're supposed to start streaming. There may be pre-endpoint-execution validation logic or other work
                //      that needs to happen before the endpoint is executed, so set up the CompletableFuture for the
                //      endpoint call to only execute if the pre-endpoint-execution validation/work chain is successful.
                CompletableFuture<DownstreamRequestFirstChunkInfo> firstChunkFuture =
                    state.getPreEndpointExecutionWorkChain()
                         .thenCompose(functionWithTracingAndMdc(
                             aVoid -> endpointProxyRouter
                                 .getDownstreamRequestFirstChunkInfo(requestInfo, longRunningTaskExecutor, ctx),
                             ctx)
                         );

                Long endpointTimeoutOverride = endpointProxyRouter.completableFutureTimeoutOverrideMillis();
                long callTimeoutValueToUse = (endpointTimeoutOverride == null)
                                             ? defaultCompletableFutureTimeoutMillis
                                             : endpointTimeoutOverride;

                // When the first chunk is ready, stream it downstream and set up what happens afterward.
                firstChunkFuture.whenComplete((downstreamRequestFirstChunkInfo, throwable) -> {

                    Optional<ManualModeTask<HttpResponse>> circuitBreakerManualTask =
                        getCircuitBreaker(downstreamRequestFirstChunkInfo, ctx).map(CircuitBreaker::newManualModeTask);

                    StreamingCallback callback = new StreamingCallbackForCtx(
                        ctx, circuitBreakerManualTask, endpointProxyRouter, requestInfo, proxyRouterState
                    );
                    if (throwable != null) {
                        // Something blew up trying to determine the first chunk info.
                        callback.unrecoverableErrorOccurred(throwable, true);
                    }
                    else if (!ctx.channel().isOpen()) {
                        // The channel was closed for some reason before we were able to start streaming.
                        String errorMsg = "The channel from the original caller was closed before we could begin the "
                                          + "downstream call.";
                        Exception channelClosedException = new RuntimeException(errorMsg);
                        runnableWithTracingAndMdc(
                            () -> logger.warn(errorMsg),
                            ctx
                        ).run();
                        callback.unrecoverableErrorOccurred(channelClosedException, true);
                    }
                    else {
                        try {
                            // Ok we have the first chunk info. Start by setting the downstream call info in the request
                            //      info (i.e. for access logs if desired)
                            requestInfo.addRequestAttribute(
                                DOWNSTREAM_CALL_PATH_REQUEST_ATTR_KEY,
                                HttpUtils.extractPath(downstreamRequestFirstChunkInfo.firstChunk.uri())
                            );

                            // Try our circuit breaker (if we have one).
                            Throwable circuitBreakerException = null;
                            try {
                                circuitBreakerManualTask.ifPresent(ManualModeTask::throwExceptionIfCircuitBreakerIsOpen);
                            }
                            catch (Throwable t) {
                                circuitBreakerException = t;
                            }

                            if (circuitBreakerException == null) {
                                // No circuit breaker, or the breaker is closed. We can now stream the first chunk info.
                                String downstreamHost = downstreamRequestFirstChunkInfo.host;
                                int downstreamPort = downstreamRequestFirstChunkInfo.port;
                                HttpRequest downstreamRequestFirstChunk = downstreamRequestFirstChunkInfo.firstChunk;
                                boolean isSecureHttpsCall = downstreamRequestFirstChunkInfo.isHttps;
                                boolean relaxedHttpsValidation = downstreamRequestFirstChunkInfo.relaxedHttpsValidation;
                                boolean performSubSpanAroundDownstreamCall = downstreamRequestFirstChunkInfo.performSubSpanAroundDownstreamCall;
                                boolean addTracingHeadersToDownstreamCall = downstreamRequestFirstChunkInfo.addTracingHeadersToDownstreamCall;

                                // Tell the proxyRouterState about the streaming callback so that
                                //      callback.unrecoverableErrorOccurred(...) can be called in the case of an error
                                //      on subsequent chunks.
                                proxyRouterState.setStreamingCallback(callback);

                                // Setup the streaming channel future with everything it needs to kick off the
                                //      downstream request.
                                proxyRouterState.setStreamingStartTimeNanos(System.nanoTime());
                                CompletableFuture<StreamingChannel> streamingChannel =
                                    streamingAsyncHttpClient.streamDownstreamCall(
                                        downstreamHost, downstreamPort, downstreamRequestFirstChunk, isSecureHttpsCall,
                                        relaxedHttpsValidation, callback, callTimeoutValueToUse,
                                            performSubSpanAroundDownstreamCall, addTracingHeadersToDownstreamCall,
                                            ctx
                                    );

                                // Tell the streaming channel future what to do when it completes.
                                streamingChannel = streamingChannel.whenComplete((sc, cause) -> {
                                    if (cause == null) {
                                        // Successfully connected and sent the first chunk. We can now safely let
                                        //      the remaining content chunks through for streaming.
                                        proxyRouterState.triggerChunkProcessing(sc);
                                    }
                                    else {
                                        // Something blew up while connecting to the downstream server.
                                        callback.unrecoverableErrorOccurred(cause, true);
                                    }
                                });
                                // Set the streaming channel future on the state so it can be connected to.
                                proxyRouterState.setStreamingChannelCompletableFuture(streamingChannel);
                            }
                            else {
                                // Circuit breaker is tripped (or otherwise threw an unexpected exception). Immediately
                                //      short circuit the error back to the client.
                                callback.unrecoverableErrorOccurred(circuitBreakerException, true);
                            }
                        }
                        catch (Throwable t) {
                            callback.unrecoverableErrorOccurred(t, true);
                        }
                    }
                });
            }
            else if (msg instanceof HttpContent) {
                HttpContent msgContent = (HttpContent)msg;

                // releaseContentChunkIfStreamAlreadyFailed() will check if we already know that the downstream call
                //      has already failed. If so then it will release the reference counts on the given HttpContent
                //      and we're done. If not, then we call registerChunkStreamingAction() to set up the
                //      chunk-streaming behavior and subsequent cleanup for the given HttpContent.
                if (!releaseContentChunkIfStreamAlreadyFailed(msgContent, proxyRouterState)) {
                    registerChunkStreamingAction(proxyRouterState, msgContent, ctx);
                }

            }

            return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    protected void registerChunkStreamingAction(
        ProxyRouterProcessingState proxyRouterState,
        HttpContent msgContent,
        ChannelHandlerContext ctx
    ) {
        // We have a content chunk to stream downstream. Attach the chunk processing to the proxyRouterState and
        //      tell it to stream itself when that future says everything is ready.
        proxyRouterState.registerStreamingChannelChunkProcessingAction((sc, cause) -> {
            if (releaseContentChunkIfStreamAlreadyFailed(msgContent, proxyRouterState)) {
                // An error occurred that means the downstream call failed and cannot continue. The error that caused
                //      the failure will have been passed through the system and a response sent to the caller, and the
                //      content has been released. Nothing left for us to do.
                return;
            }

            if (cause == null) {
                // Nothing has blown up yet, so stream this next chunk downstream. Calling streamChunk() will decrement
                //      the chunk's reference count (at some point in the future), allowing it to be destroyed since
                //      this should be the last handle on the chunk's memory.
                ChannelFuture writeFuture = sc.streamChunk(msgContent);
                writeFuture.addListener(future -> {
                    // The chunk streaming is complete, one way or another. React appropriately if there was
                    //      a problem.
                    if (!future.isSuccess()) {
                        try {
                            String errorMsg = "Chunk streaming ChannelFuture came back as being unsuccessful. "
                                              + "downstream_channel_id=" + sc.getChannel().toString();
                            Throwable errorToFire = new WrapperException(errorMsg, future.cause());
                            StreamingCallback callback = proxyRouterState.getStreamingCallback();
                            if (callback != null) {
                                // This doesn't necessarily guarantee a broken downstream response in the case where
                                //      the downstream system returned a response before receiving all request chunks
                                //      (e.g. short circuit error response), so we'll call unrecoverableErrorOccurred()
                                //      with false for the guaranteesBrokenDownstreamResponse argument. This will give
                                //      the downstream system a chance to fully send its response if it had started
                                //      but not yet completed by the time we hit this code on the request chunk.
                                callback.unrecoverableErrorOccurred(errorToFire, false);
                            }
                            else {
                                // We have to call proxyRouterState.cancelRequestStreaming() here since we couldn't
                                //      call callback.unrecoverableErrorOccurred(...);
                                proxyRouterState.cancelRequestStreaming(errorToFire, ctx);
                                runnableWithTracingAndMdc(
                                    () -> logger.error(
                                        "Unrecoverable error occurred and somehow the StreamingCallback was "
                                        + "not available. This should not be possible. Firing the following "
                                        + "error down the pipeline manually: " + errorMsg,
                                        errorToFire),
                                    ctx
                                ).run();
                                executeOnlyIfChannelIsActive(
                                    ctx,
                                    "ProxyRouterEndpointExecutionHandler-streamchunk-writefuture-unsuccessful",
                                    () -> ctx.fireExceptionCaught(errorToFire)
                                );
                            }
                        }
                        finally {
                            // Close down the StreamingChannel so its Channel can be released back to the pool.
                            sc.closeChannelDueToUnrecoverableError(future.cause());
                        }
                    }
                    else if (msgContent instanceof LastHttpContent) {
                        // This msgContent was the last chunk and it was streamed successfully, so mark the proxy router
                        //      state as having completed successfully.
                        proxyRouterState.setRequestStreamingCompletedSuccessfully();
                    }
                });
            }
            else {
                StreamingChannel scToNotify = sc;
                try {
                    // Something blew up while attempting to send a chunk to the downstream server.
                    if (scToNotify == null) {
                        // No StreamingChannel from the registration future. Try to extract it from the
                        //      proxyRouterState directly if possible.
                        CompletableFuture<StreamingChannel> scFuture =
                            proxyRouterState.getStreamingChannelCompletableFuture();
                        if (scFuture.isDone() && !scFuture.isCompletedExceptionally()) {
                            try {
                                scToNotify = scFuture.join();
                            }
                            catch (Throwable t) {
                                runnableWithTracingAndMdc(
                                    () -> logger.error("What? This should never happen. Swallowing.", t),
                                    ctx
                                ).run();
                            }
                        }
                    }

                    String downstreamChannelId = (scToNotify == null) ? "UNKNOWN" : scToNotify.getChannel().toString();
                    String errorMsg = "Chunk streaming future came back as being unsuccessful. "
                                      + "downstream_channel_id=" + downstreamChannelId;
                    Throwable errorToFire = new WrapperException(errorMsg, cause);

                    StreamingCallback callback = proxyRouterState.getStreamingCallback();
                    if (callback != null) {
                        // This doesn't necessarily guarantee a broken downstream response in the case where
                        //      the downstream system returned a response before receiving all request chunks
                        //      (e.g. short circuit error response), so we'll call unrecoverableErrorOccurred()
                        //      with false for the guaranteesBrokenDownstreamResponse argument. This will give
                        //      the downstream system a chance to fully send its response if it had started
                        //      but not yet completed by the time we hit this code on the request chunk.
                        callback.unrecoverableErrorOccurred(errorToFire, false);
                    }
                    else {
                        runnableWithTracingAndMdc(
                            () -> logger.error(
                                "Unrecoverable error occurred and somehow the StreamingCallback was not "
                                + "available. This should not be possible. Firing the following error down the "
                                + "pipeline manually: " + errorMsg,
                                errorToFire),
                            ctx
                        ).run();
                        executeOnlyIfChannelIsActive(
                            ctx, "ProxyRouterEndpointExecutionHandler-streamchunk-unsuccessful",
                            () -> ctx.fireExceptionCaught(errorToFire)
                        );
                    }
                }
                finally {
                    // We were never able to call StreamingChannel.streamChunk() on this chunk, so it still has a
                    //      dangling reference count handle that needs cleaning up. Since there's nothing left to
                    //      do with this chunk, we can release it now.
                    msgContent.release();

                    // Close down the StreamingChannel so its Channel can be released back to the pool.
                    if (scToNotify != null) {
                        scToNotify.closeChannelDueToUnrecoverableError(cause);
                    }
                    else {
                        @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
                        Throwable actualCause = unwrapAsyncExceptions(cause);
                        if (!(actualCause instanceof WrapperException)) {
                            runnableWithTracingAndMdc(
                                () -> logger.error(
                                    "Unable to extract StreamingChannel during error handling and the error that "
                                    + "caused it was not a WrapperException, meaning "
                                    + "StreamingAsyncHttpClient.streamDownstreamCall(...) did not properly handle it. "
                                    + "This should likely never happen and might leave things in a bad state - it "
                                    + "should be investigated and fixed! The error that caused this is: ",
                                    cause
                                ),
                                ctx
                            ).run();
                        }
                    }
                }
            }
        });
    }

    /**
     * @return true if the stream has failed and the given content chunk released, false if the stream has *not*
     * yet failed.
     */
    protected boolean releaseContentChunkIfStreamAlreadyFailed(
        HttpContent content, ProxyRouterProcessingState proxyRouterState
    ) {
        if (proxyRouterState.isRequestStreamingCancelled()) {
            // A previous chunk failed to stream, and we marked the proxyRouterState as having failed.
            //      The previous chunk would have already caused the failure response to be sent to the
            //      caller, therefore we don't need to do anything else for *this* chunk except release it.
            content.release();
            return true;
        }

        return false;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // To save on extraneous linking/unlinking, we'll do it as-necessary in this class.
        return false;
    }

    protected Throwable unwrapAsyncExceptions(Throwable error) {
        if (error == null || error.getCause() == null)
            return error;

        if (error instanceof CompletionException || error instanceof ExecutionException) {
            error = error.getCause();
            // Recursively unwrap until we get something that is not unwrappable
            error = unwrapAsyncExceptions(error);
        }

        return error;
    }

    protected Optional<CircuitBreaker<HttpResponse>> getCircuitBreaker(
        DownstreamRequestFirstChunkInfo downstreamReqFirstChunkInfo, ChannelHandlerContext ctx
    ) {
        if (downstreamReqFirstChunkInfo == null || downstreamReqFirstChunkInfo.disableCircuitBreaker)
            return Optional.empty();

        // Circuit breaking is enabled for this call. So we return the custom one specified or use the default one if a
        //      custom one is not specified.
        if (downstreamReqFirstChunkInfo.customCircuitBreaker.isPresent())
            return downstreamReqFirstChunkInfo.customCircuitBreaker;

        // No custom circuit breaker. Use the default for the given request's host.
        EventLoop nettyEventLoop = ctx.channel().eventLoop();
        CircuitBreaker<Integer> defaultStatusCodeCircuitBreaker = getDefaultHttpStatusCodeCircuitBreakerForKey(
            downstreamReqFirstChunkInfo.host, Optional.ofNullable(nettyEventLoop), Optional.ofNullable(nettyEventLoop)
        );
        return Optional.of(
            new CircuitBreakerDelegate<>(
                defaultStatusCodeCircuitBreaker,
                httpResponse -> (httpResponse == null ? null : httpResponse.status().code())
            )
        );
    }

    protected static ResponseInfo<?> createChunkedResponseInfoFromHttpResponse(HttpResponse httpResponse) {
        return ResponseInfo.newChunkedResponseBuilder()
                           .withHttpStatusCode(httpResponse.status().code())
                           .withHeaders(httpResponse.headers())
                           .withPreventCompressedOutput(true)
                           .build();
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    protected static class StreamingCallbackForCtx implements StreamingCallback {

        public final ChannelHandlerContext ctx;
        private final Optional<ManualModeTask<HttpResponse>> circuitBreakerManualModeTask;
        private final ProxyRouterEndpoint endpoint;
        private final RequestInfo<?> requestInfo;
        private final ProxyRouterProcessingState proxyRouterProcessingState;
        private boolean channelIsActive = true;
        private boolean firstChunkSent = false;
        private boolean lastChunkSent = false;
        private boolean downstreamCallTimeSet = false;
        private boolean cancelStreamingToOriginalCaller = false;

        private final Logger logger = LoggerFactory.getLogger(this.getClass());

        StreamingCallbackForCtx(
            ChannelHandlerContext ctx,
            Optional<ManualModeTask<HttpResponse>> circuitBreakerManualModeTask,
            ProxyRouterEndpoint endpoint,
            RequestInfo<?> requestInfo,
            ProxyRouterProcessingState proxyRouterProcessingState
        ) {
            this.ctx = ctx;
            this.circuitBreakerManualModeTask = circuitBreakerManualModeTask;
            this.endpoint = endpoint;
            this.requestInfo = requestInfo;
            this.proxyRouterProcessingState = proxyRouterProcessingState;
        }

        protected void logResponseFirstChunk(HttpResponse response) {
            if (logger.isDebugEnabled()) {
                runnableWithTracingAndMdc(
                    () -> {
                        StringBuilder sb = new StringBuilder();
                        for (String headerName : response.headers().names()) {
                            if (sb.length() > 0)
                                sb.append(", ");
                            sb.append(headerName).append("=\"")
                              .append(String.join(",", response.headers().getAll(headerName))).append("\"");
                        }
                        logger.debug("STREAMING RESPONSE HEADERS: " + sb.toString());
                        logger.debug("STREAMING RESPONSE HTTP STATUS: " + response.status().code());
                        logger.debug("STREAMING RESPONSE PROTOCOL: " + response.protocolVersion().text());
                    },
                    ctx
                ).run();
            }
        }

        protected void logResponseContentChunk(HttpContent chunk) {
            if (logger.isDebugEnabled()) {
                runnableWithTracingAndMdc(
                    () -> logger.debug("STREAMING RESPONSE CHUNK: " + chunk.getClass().getName() + ", size: "
                                       + chunk.content().readableBytes()),
                    ctx
                ).run();
            }
        }

        protected void setDownstreamCallTimeOnRequestAttributesIfNotAlreadyDone() {
            if (downstreamCallTimeSet)
                return;

            long startTimeNanos = proxyRouterProcessingState.getStreamingStartTimeNanos();
            if (startTimeNanos > 0) { // 0 means we never started the call - likely due to an error/bad connection/etc.
                long downstreamCallTimeNanos =
                    System.nanoTime() - proxyRouterProcessingState.getStreamingStartTimeNanos();
                requestInfo.addRequestAttribute(DOWNSTREAM_CALL_TIME_NANOS_REQUEST_ATTR_KEY, downstreamCallTimeNanos);
            }

            downstreamCallTimeSet = true;
        }

        @Override
        public void cancelStreamingToOriginalCaller() {
            this.cancelStreamingToOriginalCaller = true;
        }

        @Override
        public void messageReceived(HttpObject msg) {
            if (!channelIsActive || cancelStreamingToOriginalCaller)
                return;

            // NOTE: We do *not* want to short circuit on proxyRouterProcessingState.isRequestStreamingCancelled(). The
            //      only thing that should prevent us from attempting to send a chunk from the downstream call back to
            //      the original caller is if an error response was sent prior to us receiving anything from the
            //      downstream call, and that logic is handled further down.

            channelIsActive = executeOnlyIfChannelIsActive(ctx, "StreamingCallbackForCtx-messageReceived", () -> {
                HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();

                if (msg instanceof FullHttpResponse) {
                    String errorMsg = "FullHttpResponse is not supported. Expected HttpResponse or HttpContent";
                    runnableWithTracingAndMdc(() -> logger.error(errorMsg), ctx).run();
                    throw new IllegalArgumentException(errorMsg);
                }

                if (msg instanceof HttpResponse) {
                    runnableWithTracingAndMdc(
                        () -> {
                            HttpResponse httpResponse = (HttpResponse) msg;
                            // Do debug logging on the original HttpResponse
                            logResponseFirstChunk(httpResponse);

                            try {
                                // Give the endpoint the option of handling the HttpResponse
                                //      before sending it on to the client.
                                endpoint.handleDownstreamResponseFirstChunk(httpResponse, requestInfo);
                            }
                            catch(Throwable t) {
                                logger.error(
                                    "An error occurred while calling the endpoint's handleDownstreamResponseFirstChunk() "
                                    + "method. This will be ignored, but represents a bug in the endpoint code that needs "
                                    + "to be fixed. endpoint_class={}",
                                    endpoint.getClass().getName(), t
                                );
                            }

                            // Convert the HttpResponse to a ResponseInfo, set the ResponseInfo on our
                            //      HttpProcessingState so it can be streamed back to the client, and fire a pipeline
                            //      event to kick off sending the response back to the client.
                            ResponseInfo<?> responseInfo = createChunkedResponseInfoFromHttpResponse(httpResponse);

                            try {
                                // Notify the circuit breaker of the response event.
                                circuitBreakerManualModeTask.ifPresent(cb -> cb.handleEvent(httpResponse));
                            }
                            catch (Throwable t) {
                                logger.error(
                                    "Circuit breaker threw an exception during handleEvent. This should never happen "
                                    + "and means the CircuitBreaker is malfunctioning. Ignoring exception.", t
                                );
                            }
                            finally {
                                // We have to set the ResponseInfo on the state and fire the event while in the
                                //      channel's EventLoop. Otherwise there could be a race condition with an error
                                //      that was fired down the pipe that sets the ResponseInfo on the state first, then
                                //      this comes along and replaces the ResponseInfo (or vice versa).
                                EventExecutor executor = ctx.executor();
                                if (executor.inEventLoop()) {
                                    sendFirstChunkDownPipeline(state, responseInfo);
                                }
                                else {
                                    executor.execute(() -> sendFirstChunkDownPipeline(state, responseInfo));
                                }
                            }
                        },
                        ctx
                    ).run();
                }
                else if (msg instanceof HttpContent) {
                    HttpContent contentChunk = (HttpContent) msg;
                    logResponseContentChunk(contentChunk);

                    // This time we do need to retain the msg since we don't want the data blasted away before we can
                    //      send it back to the user.
                    contentChunk.retain();
                    OutboundMessageSendContentChunk contentChunkToSend =
                        (contentChunk instanceof LastHttpContent)
                        ? new LastOutboundMessageSendLastContentChunk((LastHttpContent) contentChunk)
                        : new OutboundMessageSendContentChunk(contentChunk);

                    if (contentChunk instanceof LastHttpContent) {
                        setDownstreamCallTimeOnRequestAttributesIfNotAlreadyDone();
                    }

                    EventExecutor executor = ctx.executor();
                    if (executor.inEventLoop()) {
                        sendContentChunkDownPipeline(contentChunk, contentChunkToSend, state);
                    }
                    else {
                        executor.execute(() -> sendContentChunkDownPipeline(contentChunk, contentChunkToSend, state));
                    }
                }
                else {
                    String errorMsg = "Expected msg to be a HttpResponse or HttpContent, instead received: "
                                      + msg.getClass().getName();
                    runnableWithTracingAndMdc(() -> logger.error(errorMsg), ctx).run();
                    throw new IllegalArgumentException(errorMsg);
                }
            });
        }

        protected void sendFirstChunkDownPipeline(HttpProcessingState state, ResponseInfo<?> responseInfo) {
            if (state.isRequestHandled() || cancelStreamingToOriginalCaller) {
                runnableWithTracingAndMdc(
                    () -> logger.warn("The request has already been handled, likely due to an error, so the downstream "
                                      + "call's response will be ignored."),
                    ctx
                ).run();

                Throwable requestAlreadyHandledException = new RuntimeException(
                    "A response has already been sent to the original caller (likely an error response) - "
                    + "ignoring proxied response first chunk."
                );
                // Stop streaming request chunks downstream.
                proxyRouterProcessingState.cancelRequestStreaming(requestAlreadyHandledException, ctx);

                // And since we know we will never be able to use anything from the downstream call,
                //      cancel response streaming from the downstream call as well.
                proxyRouterProcessingState.cancelDownstreamRequest(requestAlreadyHandledException);
            }
            else {
                state.setResponseInfo(responseInfo);
                // No need to retain the msg, we already grabbed everything we need from it and stuffed it into the
                //      ResponseInfo. Just send the appropriate OutboundMessage.
                firstChunkSent = true;
                ctx.fireChannelRead(OutboundMessageSendHeadersChunkFromResponseInfo.INSTANCE);
            }
        }

        protected void sendContentChunkDownPipeline(HttpContent contentChunk,
                                                    OutboundMessageSendContentChunk contentChunkToSend,
                                                    HttpProcessingState state) {
            boolean stateResponseSendingLastChunkSent = state != null && state.isResponseSendingLastChunkSent();
            if (lastChunkSent || stateResponseSendingLastChunkSent || cancelStreamingToOriginalCaller) {
                // A full response has already been sent to the user, so there's no point in firing this content chunk
                //      down the pipeline. The response was likely an error response due to an error that occurred
                //      before any of the downstream proxied response was received. We'll log a message here for
                //      debugging purposes, but otherwise ignore this content chunk.
                runnableWithTracingAndMdc(
                    () -> logger.warn(
                        "Ignoring response content chunk from the downstream call because a full response was already "
                        + "sent to the user (likely an error response)."
                    ),
                    ctx
                ).run();
                // Since this chunk won't be written and flushed down the channel we have to release the message
                //      here to avoid a memory leak.
                contentChunk.release();

                Throwable requestAlreadyHandledException = new RuntimeException(
                    "A response has already been sent to the original caller (likely an error response) - "
                    + "ignoring proxied response content chunk."
                );
                // Stop streaming request chunks downstream.
                proxyRouterProcessingState.cancelRequestStreaming(requestAlreadyHandledException, ctx);

                // And since we know we will never be able to use anything from the downstream call,
                //      cancel response streaming from the downstream call as well.
                proxyRouterProcessingState.cancelDownstreamRequest(requestAlreadyHandledException);
            }
            else {
                // We haven't already sent a last chunk to the user, which means no error response has occurred.
                //      Therefore the in-progress response is still the downstream call's response, and we are free to
                //      send this response chunk from the downstream call back to the original caller.
                if (contentChunk instanceof LastHttpContent) 
                    lastChunkSent = true;

                ctx.fireChannelRead(contentChunkToSend);
            }
        }

        @Override
        public void unrecoverableErrorOccurred(Throwable error, boolean guaranteesBrokenDownstreamResponse) {
            // Cancel request streaming so it stops trying to send data downstream and releases any chunks we've been
            //      holding onto. This holds true no matter the value of guaranteesBrokenDownstreamResponse
            //      (i.e. we want to stop sending data downstream no matter what). Note that this does not stop the
            //      downstream call's response, and that is intentional to support use cases where the downstream
            //      system can still successfully send a full response even though the request wasn't fully sent.
            proxyRouterProcessingState.cancelRequestStreaming(error, ctx);

            setDownstreamCallTimeOnRequestAttributesIfNotAlreadyDone();

            EventExecutor executor = ctx.executor();
            if (executor.inEventLoop()) {
                sendUnrecoverableErrorDownPipeline(error, guaranteesBrokenDownstreamResponse);
            }
            else {
                executor.execute(() -> sendUnrecoverableErrorDownPipeline(error, guaranteesBrokenDownstreamResponse));
            }
        }

        protected void sendUnrecoverableErrorDownPipeline(Throwable error, boolean guaranteesBrokenDownstreamResponse) {
            if (!channelIsActive || cancelStreamingToOriginalCaller)
                return;

            channelIsActive =
                executeOnlyIfChannelIsActive(ctx, "StreamingCallbackForCtx-unrecoverableErrorOccurred", () -> {
                    Pair<Deque<Span>, Map<String, String>> originalThreadInfo = null;
                    try {
                        // Setup tracing and MDC so our log messages have the correct distributed trace info, etc.
                        originalThreadInfo = AsyncNettyHelper.linkTracingAndMdcToCurrentThread(ctx);

                        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
                        boolean stateResponseSendingStarted = state != null && state.isResponseSendingStarted();
                        boolean stateResponseSendingLastChunkSent = state != null && state.isResponseSendingLastChunkSent();
                        if (lastChunkSent || stateResponseSendingLastChunkSent) {
                            // A full response has already been sent to the user, so there's no point in firing an error
                            //      down the pipeline. This is likely a secondary symptom type error, so we'll log a
                            //      little info about it for debugging purposes but otherwise ignore it - no circuit
                            //      breaker since it would have already been updated by the response already sent to the
                            //      user and we don't want double counting.
                            logger.warn(
                                "A secondary exception occurred after the response had already been sent to the user. "
                                + "Not necessarily anything to worry about but in case it helps debugging: "
                                + "secondary_exception=\"{}\", secondary_exception_cause=\"{}\"",
                                error.toString(), String.valueOf(error.getCause())
                            );
                        }
                        else if (firstChunkSent || stateResponseSendingStarted) {
                            // A partial response from the downstream call has been sent to the user, but something
                            //      unexpected happened before the full response was sent. This may or may not be
                            //      recoverable - we use the guaranteesBrokenDownstreamResponse arg to determine what
                            //      happens at this point.
                            if (guaranteesBrokenDownstreamResponse) {
                                logger.warn(
                                    "The downstream system failed with an unrecoverable error after a partial response was "
                                    + "sent to the original caller. The downstream system will not be sending any more "
                                    + "data so the caller will be left with an incomplete response. "
                                    + "downstream_unrecoverable_error=\"{}\"", error.toString()
                                );
                                // Nothing we can do - this channel is unrecoverable, so close it.
                                ctx.close();
                            }
                            else {
                                // There's still a chance the downstream system might send the full response. Nothing to
                                //      do now except log for debugging purposes.
                                logger.info(
                                    "An error occurred after a partial response was sent to the original caller. The "
                                    + "downstream system might still successfully send a full response however, so we "
                                    + "will allow it to finish. error=\"{}\"", error.toString()
                                );
                            }
                        }
                        else {
                            // The caller has not received any response yet, so we can send this error down the pipeline
                            //      to be translated into an appropriate error response.
                            try {
                                // Notify the circuit breaker of the error
                                circuitBreakerManualModeTask.ifPresent(cb -> cb.handleException(error));
                            }
                            catch (Throwable t) {
                                logger.error(
                                    "Circuit breaker threw an exception during handleException. This should never "
                                    + "happen and means the CircuitBreaker is malfunctioning. Ignoring exception.",
                                    t
                                );
                            }
                            finally {
                                lastChunkSent = true;
                                
                                // Propagate the error down the pipeline, which will send the appropriate error response
                                //      to the client.
                                ctx.fireExceptionCaught(error);
                            }
                        }
                    }
                    finally {
                        // Unhook the tracing and MDC stuff from this thread now that we're done.
                        AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread(originalThreadInfo);
                    }
                });
        }
    }
}