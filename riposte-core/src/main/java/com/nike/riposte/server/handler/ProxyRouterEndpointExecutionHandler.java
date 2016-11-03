package com.nike.riposte.server.handler;

import com.nike.backstopper.exception.WrapperException;
import com.nike.fastbreak.CircuitBreaker;
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
import io.netty.util.ReferenceCountUtil;

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
                    Optional<CircuitBreaker<HttpResponse>> circuitBreaker =
                        getCircuitBreaker(downstreamRequestFirstChunkInfo, ctx);

                    StreamingCallback callback = new StreamingCallbackForCtx(
                        ctx, circuitBreaker, endpointProxyRouter, requestInfo, proxyRouterState
                    );
                    if (throwable != null) {
                        // Something blew up trying to determine the first chunk info.
                        try {
                            callback.unrecoverableErrorOccurred(throwable);
                        }
                        finally {
                            // Since something exploded before we could stream the first chunk, we're now done with it.
                            ReferenceCountUtil.release(msg);
                        }
                    }
                    else {
                        try {
                            // Ok we have the first chunk info. Start by setting the downstream call info in the request
                            //      info (i.e. for access logs if desired)
                            requestInfo.addRequestAttribute(
                                DOWNSTREAM_CALL_PATH_REQUEST_ATTR_KEY,
                                HttpUtils.extractPath(downstreamRequestFirstChunkInfo.firstChunk.getUri())
                            );

                            // Try our circuit breaker (if we have one).
                            Throwable circuitBreakerException = null;
                            try {
                                circuitBreaker.ifPresent(CircuitBreaker::throwExceptionIfCircuitBreakerIsOpen);
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
                                        relaxedHttpsValidation, callback,
                                        callTimeoutValueToUse, ctx
                                    );

                                // Tell the streaming channel future what to do when it completes.
                                streamingChannel = streamingChannel.whenComplete((sc, cause) -> {
                                    try {
                                        if (cause == null) {
                                            // Successfully connected and sent the first chunk. We can now safely let
                                            //      the remaining content chunks through for streaming.
                                            proxyRouterState.triggerChunkProcessing(sc);
                                        }
                                        else {
                                            // Something blew up while connecting to the downstream server.
                                            callback.unrecoverableErrorOccurred(cause);
                                            // We still have to finish the future for the chunks so that they can
                                            //      release themselves.
                                            proxyRouterState.triggerStreamingChannelErrorForChunks(cause);
                                        }
                                    }
                                    finally {
                                        // At this point we're done with the first chunk whether the
                                        //      connect-and-stream-first-chunk was successful or not. Release it, but
                                        //      only if the streamingChannel was not successful. This is because on a
                                        //      successful write the reference count is automatically reduced.
                                        if (cause != null)
                                            ReferenceCountUtil.release(msg);
                                    }
                                });
                                // Set the streaming channel future on the state so it can be connected to.
                                proxyRouterState.setStreamingChannelCompletableFuture(streamingChannel);
                            }
                            else {
                                // Circuit breaker is tripped (or otherwise threw an unexpected exception). Immediately
                                //      short circuit the error back to the client.
                                try {
                                    callback.unrecoverableErrorOccurred(circuitBreakerException);
                                }
                                finally {
                                    // Since we exploded before we could stream the first chunk, we're now done with it.
                                    ReferenceCountUtil.release(msg);
                                }
                            }
                        }
                        catch (Throwable t) {
                            callback.unrecoverableErrorOccurred(t);
                        }
                    }
                });
            }
            else if (msg instanceof HttpContent) {
                // We have a content chunk to stream downstream. Attach the chunk processing to the proxyRouterState and
                //      tell it to stream itself when that future says everything is ready.
                proxyRouterState.registerStreamingChannelChunkProcessingAction((sc, cause) -> {
                    if (cause == null) {
                        // Nothing has blown up yet, so stream this next chunk downstream.
                        ChannelFuture writeFuture = sc.streamChunks((HttpContent) msg);
                        writeFuture.addListener(future -> {
                            try {
                                // The chunk streaming is complete, one way or another. React appropriately if there was
                                //      a problem.
                                if (!future.isSuccess()) {
                                    String errorMsg = "Chunk streaming future came back as being unsuccessful.";
                                    Throwable errorToFire = new WrapperException(errorMsg, future.cause());
                                    sc.closeChannelDueToUnrecoverableError();
                                    StreamingCallback callback = proxyRouterState.getStreamingCallback();
                                    if (callback != null)
                                        callback.unrecoverableErrorOccurred(errorToFire);
                                    else {
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
                            }
                            finally {
                                // No matter what, at this point we're done with the chunk. Release it, but only if the
                                //      writeFuture was not successful. This is because on a successful write the
                                //      reference count is automatically reduced.
                                if (!future.isSuccess())
                                    ReferenceCountUtil.release(msg);
                            }
                        });
                    }
                    else {
                        try {
                            // Something blew up while attempting to send a chunk to the downstream server.
                            StreamingChannel scToNotify = sc;
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
                                            () -> logger.error("What? This should never happen. Swallowing.", t), ctx
                                        ).run();
                                    }
                                }
                            }

                            if (scToNotify != null) {
                                scToNotify.closeChannelDueToUnrecoverableError();
                            }
                            else {
                                @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
                                Throwable actualCause = unwrapAsyncExceptions(cause);
                                if (!(actualCause instanceof WrapperException)) {
                                    runnableWithTracingAndMdc(
                                        () -> logger.error(
                                            "Unable to extract StreamingChannel during error handling and the error "
                                            + "that caused it was not a WrapperException, meaning "
                                            + "StreamingAsyncHttpClient.streamDownstreamCall(...) did not properly "
                                            + "handle it. This should likely never happen and might leave things in a "
                                            + "bad state - it should be investigated and fixed! The error that caused "
                                            + "this is: ",
                                            cause),
                                        ctx
                                    ).run();
                                }
                            }

                            String errorMsg = "Chunk streaming future came back as being unsuccessful.";
                            Throwable errorToFire = new WrapperException(errorMsg, cause);

                            StreamingCallback callback = proxyRouterState.getStreamingCallback();
                            if (callback != null)
                                callback.unrecoverableErrorOccurred(errorToFire);
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
                            // We no longer need the chunk so it can be released.
                            ReferenceCountUtil.release(msg);
                        }
                    }
                });
            }

            return PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
        }

        return PipelineContinuationBehavior.CONTINUE;
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
                httpResponse -> (httpResponse == null ? null : httpResponse.getStatus().code())
            )
        );
    }

    protected static ResponseInfo<?> createChunkedResponseInfoFromHttpResponse(HttpResponse httpResponse) {
        return ResponseInfo.newChunkedResponseBuilder()
                           .withHttpStatusCode(httpResponse.getStatus().code())
                           .withHeaders(httpResponse.headers())
                           .withPreventCompressedOutput(true)
                           .build();
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    protected static class StreamingCallbackForCtx implements StreamingCallback {

        public final ChannelHandlerContext ctx;
        private final Optional<CircuitBreaker<HttpResponse>> circuitBreaker;
        private final ProxyRouterEndpoint endpoint;
        private final RequestInfo<?> requestInfo;
        private final ProxyRouterProcessingState proxyRouterProcessingState;
        private boolean channelIsActive = true;
        private boolean lastChunkSent = false;
        private boolean downstreamCallTimeSet = false;

        private final Logger logger = LoggerFactory.getLogger(this.getClass());

        StreamingCallbackForCtx(ChannelHandlerContext ctx, Optional<CircuitBreaker<HttpResponse>> circuitBreaker,
                                ProxyRouterEndpoint endpoint, RequestInfo<?> requestInfo,
                                ProxyRouterProcessingState proxyRouterProcessingState) {
            this.ctx = ctx;
            this.circuitBreaker = circuitBreaker;
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
                        logger.debug("STREAMING RESPONSE HTTP STATUS: " + response.getStatus().code());
                        logger.debug("STREAMING RESPONSE PROTOCOL: " + response.getProtocolVersion().text());
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
        public void messageReceived(HttpObject msg) {
            if (!channelIsActive)
                return;

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

                            // Give the endpoint the option of handling the HttpResponse
                            //      before sending it on to the client.
                            endpoint.handleDownstreamResponseFirstChunk(httpResponse, requestInfo);
                            // Convert the HttpResponse to a ResponseInfo, set the ResponseInfo on our
                            //      HttpProcessingState so it can be streamed back to the client, and fire a pipeline
                            //      event to kick off sending the response back to the client.
                            ResponseInfo<?> responseInfo = createChunkedResponseInfoFromHttpResponse(httpResponse);
                            state.setResponseInfo(responseInfo);

                            try {
                                // Notify the circuit breaker of the response event.
                                circuitBreaker.ifPresent(cb -> cb.handleEvent(httpResponse));
                            }
                            catch (Throwable t) {
                                logger.error(
                                    "Circuit breaker threw an exception during handleEvent. This should never happen "
                                    + "and means the CircuitBreaker is malfunctioning. Ignoring exception.", t
                                );
                            }
                            finally {
                                // No need to retain the msg, we already grabbed everything we need from it and stuffed
                                //      it into the ResponseInfo. Just send the appropriate OutboundMessage
                                ctx.fireChannelRead(OutboundMessageSendHeadersChunkFromResponseInfo.INSTANCE);
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
                    ReferenceCountUtil.retain(msg);
                    OutboundMessageSendContentChunk contentChunkToSend =
                        (contentChunk instanceof LastHttpContent)
                        ? new LastOutboundMessageSendLastContentChunk((LastHttpContent) contentChunk)
                        : new OutboundMessageSendContentChunk(contentChunk);

                    ctx.fireChannelRead(contentChunkToSend);

                    if (contentChunk instanceof LastHttpContent) {
                        lastChunkSent = true;
                        setDownstreamCallTimeOnRequestAttributesIfNotAlreadyDone();
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

        @Override
        public void unrecoverableErrorOccurred(Throwable error) {
            setDownstreamCallTimeOnRequestAttributesIfNotAlreadyDone();

            if (!channelIsActive)
                return;

            channelIsActive =
                executeOnlyIfChannelIsActive(ctx, "StreamingCallbackForCtx-unrecoverableErrorOccurred", () -> {
                    Pair<Deque<Span>, Map<String, String>> originalThreadInfo = null;
                    try {
                        // Setup tracing and MDC so our log messages have the correct distributed trace info, etc.
                        originalThreadInfo = AsyncNettyHelper.linkTracingAndMdcToCurrentThread(ctx);

                        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
                        if ((state != null && state.isResponseSendingLastChunkSent()) || lastChunkSent) {
                            // A full response has already been sent to the user, so there's no point in firing an error
                            //      down the pipeline. This is likely a secondary symptom type error, so we'll log a
                            //      little info about it for debugging purposes but otherwise ignore it - no circuit
                            //      breaker since it would have already been updated by the response already sent to the
                            //      user and we don't want double counting.
                            logger.warn(
                                "A secondary exception occurred after the response had already been sent to the user. "
                                + "Not necessarily anything to worry about but in case it helps debugging the exception "
                                + "was: {}", error.toString()
                            );
                        }
                        else {
                            try {
                                // Notify the circuit breaker of the error
                                circuitBreaker.ifPresent(cb -> cb.handleException(error));
                            }
                            catch (Throwable t) {
                                logger.error(
                                    "Circuit breaker threw an exception during handleException. This should never "
                                    + "happen and means the CircuitBreaker is malfunctioning. Ignoring exception.",
                                    t
                                );
                            }
                            finally {
                                // Propagate the error down the pipeline, which will send the appropriate error response
                                //      to the client.
                                ctx.fireExceptionCaught(error);
                                lastChunkSent = true;
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