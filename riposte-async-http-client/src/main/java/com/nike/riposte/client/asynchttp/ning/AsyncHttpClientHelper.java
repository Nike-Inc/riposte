package com.nike.riposte.client.asynchttp.ning;

import com.nike.fastbreak.CircuitBreaker;
import com.nike.fastbreak.CircuitBreakerDelegate;
import com.nike.fastbreak.exception.CircuitBreakerOpenException;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.wingtips.Span;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.NameResolver;
import com.ning.http.client.Response;
import com.ning.http.client.uri.Uri;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.HttpMethod;

import static com.nike.fastbreak.CircuitBreakerForHttpStatusCode.getDefaultHttpStatusCodeCircuitBreakerForKey;

/**
 * <b>WARNING: This class should be used as a singleton (or a small set number per app, depending on if you have calls
 * with different connection pooling/TTL requirements, etc)!</b> Each new instance of this class will create a new
 * threadpool under the hood for handling the HTTP calls and responses, so if you create a new instance of this class
 * for every call your app may eventually fall over due to a thread explosion.
 * <p/>
 * {@code AsyncHttpClientHelper} is a helper class to make it easy to perform asynchronous downstream HTTP requests that
 * map to a {@link CompletableFuture}. This is a wrapper around <a
 * href="https://github.com/AsyncHttpClient/async-http-client">com.ning's Async HTTP Client</a> that takes care of all
 * the distributed tracing and MDC issues for you, and fully supports connection keep-alive and connection pooling. The
 * only drawback to using this library is that it doesn't link up to your Netty 4 server's worker I/O threads so there
 * will be a handful of extra threads (2 * CPU cores) for the library to do its work. The overhead for this number of
 * extra threads is negligible since it's a small fixed number that does not increase no matter how much traffic it
 * handles (but again, see the warning above about using this as a singleton).
 * <p/>
 * USAGE:
 * <ol>
 *      <li>
 *          As stated previously in the warning at the top of this javadoc, you generally should only create *one*
 *          instance of this {@code AsyncHttpClientHelper} class and reuse it for *all* calls. If you have calls with
 *          different connection pooling/TTL type requirements then you can create one instance for each category, but
 *          do *not* create new instances of this class frequently - there should only be a small handful (at most) per
 *          application.
 *      </li>
 *      <li>
 *          Call {@link #getRequestBuilder(String, HttpMethod)} or {@link
 *          #getRequestBuilder(String, HttpMethod, Optional, boolean)} to get your hands on a request builder.
 *      </li>
 *      <li>
 *          Call the builder methods on the returned {@link RequestBuilderWrapper#requestBuilder} to set all the query
 *          params, headers, request body, etc that you want in the request.
 *      </li>
 *      <li>
 *          Call one of the {@code executeAsyncHttpRequest(...)} methods with the finished request builder to execute
 *          the async HTTP call and return a {@link CompletableFuture} that will be completed when the async HTTP call
 *          returns.
 *      </li>
 * </ol>
 *
 * @author Nic Munroe
 */
@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType"})
public class AsyncHttpClientHelper {

    /**
     * The default amount of time in milliseconds that pooled downstream connections are eligible to be reused. If you
     * want a different value make sure you call the {@link
     * AsyncHttpClientHelper#AsyncHttpClientHelper(AsyncHttpClientConfig.Builder, boolean)} constructor and set {@link
     * AsyncHttpClientConfig.Builder#setConnectionTTL(int)} to your desired value. Pass in -1 to disable TTL, causing
     * connections to be held onto and reused forever (as long as they are open and valid). <b>This is not recommended,
     * however - see below for the reason why you should have a reasonable TTL.</b>
     * <p/>
     * It's generally a good idea to have a TTL of some sort because the IP addresses associated with a DNS address can
     * change, and if you never TTL your connections you could have requests fail when a pooled connection associated
     * with a domain tries to hit an IP address that is no longer valid for that DNS. In particular, Amazon ELBs scale
     * up by (among other things) swapping in bigger boxes and changing the DNS to point to the new instance IPs, so if
     * you never TTL your connections you could have your service end up in a bad state trying to talk to stale ELB IPs.
     * A TTL of a few minutes is generally a good tradeoff between the cost of creating new connections vs. hitting
     * stale IPs.
     * <p/>
     * NOTE: When this TTL is passed a connection will *not* be severed if it is actively serving a request, but the
     * next time it is returned to the pool it will be discarded.
     */
    public static final int DEFAULT_POOLED_DOWNSTREAM_CONNECTION_TTL_MILLIS =
        ((Long) TimeUnit.MINUTES.toMillis(3)).intValue();

    /**
     * The default amount of time in milliseconds that a request sent by {@link AsyncHttpClientHelper} will wait before
     * giving up. This default is set to just under the default Amazon ELB timeout of 60 seconds so that the error you
     * receive will be a much more informative request timeout error rather than the opaque 504 the ELB would throw.
     */
    public static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = ((Long) TimeUnit.SECONDS.toMillis(58)).intValue();

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final AsyncHttpClient asyncHttpClient;
    protected final boolean performSubSpanAroundDownstreamCalls;

    /**
     * Constructor that gives you maximum control over configuration and behavior.
     *
     * @param builder
     *     The builder that will create the {@link #asyncHttpClient} and execute all the async downstream HTTP
     *     requests.
     * @param performSubSpanAroundDownstreamCalls
     *     Pass in true to have a distributed tracing subspan added to each downstream call to measure the time spent
     *     on the downstream call, false if you do not want subspans performed. The subspans can be used to determine
     *     how much time is spent processing in your app vs. waiting for downstream requests.
     */
    public AsyncHttpClientHelper(AsyncHttpClientConfig.Builder builder, boolean performSubSpanAroundDownstreamCalls) {
        this.performSubSpanAroundDownstreamCalls = performSubSpanAroundDownstreamCalls;

        Map<String, String> mdcContextMap = MDC.getCopyOfContextMap();
        Deque<Span> distributedTraceStack = null;

        try {
            // We have to unlink tracing and MDC from the current thread before we setup the async http client library,
            //      otherwise all the internal threads it uses to do its job will be attached to the current thread's
            //      trace/MDC info forever and always.
            distributedTraceStack = Tracer.getInstance().unregisterFromThread();
            MDC.clear();
            AsyncHttpClientConfig cf = builder.build();
            asyncHttpClient = new AsyncHttpClient(cf);
        }
        finally {
            // Reattach the original tracing and MDC before we leave
            if (mdcContextMap == null)
                MDC.clear();
            else
                MDC.setContextMap(mdcContextMap);

            Tracer.getInstance().registerWithThread(distributedTraceStack);
        }
    }

    /**
     * Constructor that uses default settings for {@link #asyncHttpClient} but allows you to specify whether or not you
     * want subspans for each downstream request.
     *
     * @param performSubSpanAroundDownstreamCalls
     *     Pass in true to have a distributed tracing subspan added to each downstream call to measure the time spent
     *     on the downstream call, false if you do not want subspans performed. The subspans can be used to determine
     *     how much time is spent processing in your app vs. waiting for downstream requests.
     */
    public AsyncHttpClientHelper(boolean performSubSpanAroundDownstreamCalls) {
        this(
            new AsyncHttpClientConfig.Builder()
                .setAllowPoolingConnections(true)
                .setMaxRequestRetry(0)
                .setRequestTimeout(DEFAULT_REQUEST_TIMEOUT_MILLIS)
                .setConnectionTTL(DEFAULT_POOLED_DOWNSTREAM_CONNECTION_TTL_MILLIS),
            performSubSpanAroundDownstreamCalls
        );
    }

    /**
     * Default constructor that uses default settings for {@link #asyncHttpClient} and sets {@link
     * #performSubSpanAroundDownstreamCalls} to true (so all downstream requests will be tagged with subspans).
     */
    public AsyncHttpClientHelper() {
        this(true);
    }

    /**
     * Call this before one of the {@code executeAsyncHttpRequest(...)} methods in order to get a request builder you
     * can populate with query params, headers, body, etc. If you want to specify a custom circuit breaker (or disable
     * circuit breaking entirely) for this call then use {@link #getRequestBuilder(String, HttpMethod, Optional,
     * boolean)} instead. This method tells the HTTP client to use a default circuit breaker based on the host being
     * called.
     */
    public RequestBuilderWrapper getRequestBuilder(String url, HttpMethod method) {
        return getRequestBuilder(url, method, Optional.empty(), false);
    }

    /**
     * Call this before one of the {@code executeAsyncHttpRequest(...)} methods in order to get a request builder you
     * can populate with query params, headers, body, etc. Pass in a non-empty {@code customCircuitBreaker} argument to
     * specify the exact circuit breaker you want to use, pass in an empty {@code customCircuitBreaker} if you want the
     * HTTP client to use a default one based on the host being called, and pass in true for the {@code
     * disableCircuitBreaker} argument if you want to disable circuit breaking entirely for this call.
     */
    public RequestBuilderWrapper getRequestBuilder(String url, HttpMethod method,
                                                   Optional<CircuitBreaker<Response>> customCircuitBreaker,
                                                   boolean disableCircuitBreaker) {
        RequestBuilderWrapper wrapper = generateRequestBuilderWrapper(
            url, method, customCircuitBreaker, disableCircuitBreaker
        );

        // The AsyncHttpClient doesn't properly split traffic when a DNS has multiple IP addresses associated with it,
        //      so we have to hack it ourselves.
        wrapper.requestBuilder.setNameResolver(getMultiIpAwareNameResolver());

        return wrapper;
    }

    protected MultiIpAwareNameResolver getMultiIpAwareNameResolver() {
        return MultiIpAwareNameResolver.INSTANCE;
    }

    protected RequestBuilderWrapper generateRequestBuilderWrapper(String url, HttpMethod method,
                                                                  Optional<CircuitBreaker<Response>> customCircuitBreaker,
                                                                  boolean disableCircuitBreaker) {
        String httpMethod = method.name();
        switch (httpMethod) {
            case "CONNECT":
                return new RequestBuilderWrapper(url, httpMethod, asyncHttpClient.prepareConnect(url),
                                                 customCircuitBreaker, disableCircuitBreaker);
            case "DELETE":
                return new RequestBuilderWrapper(url, httpMethod, asyncHttpClient.prepareDelete(url),
                                                 customCircuitBreaker, disableCircuitBreaker);
            case "GET":
                return new RequestBuilderWrapper(url, httpMethod, asyncHttpClient.prepareGet(url), customCircuitBreaker,
                                                 disableCircuitBreaker);
            case "HEAD":
                return new RequestBuilderWrapper(url, httpMethod, asyncHttpClient.prepareHead(url),
                                                 customCircuitBreaker, disableCircuitBreaker);
            case "POST":
                return new RequestBuilderWrapper(url, httpMethod, asyncHttpClient.preparePost(url),
                                                 customCircuitBreaker, disableCircuitBreaker);
            case "OPTIONS":
                return new RequestBuilderWrapper(url, httpMethod, asyncHttpClient.prepareOptions(url),
                                                 customCircuitBreaker, disableCircuitBreaker);
            case "PUT":
                return new RequestBuilderWrapper(url, httpMethod, asyncHttpClient.preparePut(url), customCircuitBreaker,
                                                 disableCircuitBreaker);
            case "PATCH":
                return new RequestBuilderWrapper(url, httpMethod, asyncHttpClient.preparePatch(url),
                                                 customCircuitBreaker, disableCircuitBreaker);
            case "TRACE":
                return new RequestBuilderWrapper(url, httpMethod, asyncHttpClient.prepareTrace(url),
                                                 customCircuitBreaker, disableCircuitBreaker);
            default:
                logger.warn(
                    "The given method {} is not directly supported. We will try to force it anyway. The returned request builder may or may not work.",
                    httpMethod);
                return new RequestBuilderWrapper(url, httpMethod,
                                                 asyncHttpClient.preparePost(url).setMethod(httpMethod),
                                                 customCircuitBreaker,
                                                 disableCircuitBreaker);
        }
    }

    /**
     * Executes the given request asynchronously, handling the response with the given responseHandlerFunction, and
     * returns a {@link CompletableFuture} that represents the result of executing the
     * responseHandlerFunction on the downstream response. Any error anywhere along the way will cause the returned
     * future to be completed with {@link CompletableFuture#completeExceptionally(Throwable)}.
     * <p/>
     * NOTE: This is a helper method for calling {@link #executeAsyncHttpRequest(RequestBuilderWrapper,
     * AsyncResponseHandler, java.util.Deque, java.util.Map)} that uses the current thread's {@link
     * Tracer#getCurrentSpanStackCopy()} and {@link org.slf4j.MDC#getCopyOfContextMap()} for the for the distributed
     * trace stack and MDC info for the downstream call.
     */
    public <O> CompletableFuture<O> executeAsyncHttpRequest(RequestBuilderWrapper requestBuilderWrapper,
                                                            AsyncResponseHandler<O> responseHandlerFunction) {
        Map<String, String> mdcContextMap = MDC.getCopyOfContextMap();
        Deque<Span> distributedTraceStack = Tracer.getInstance().getCurrentSpanStackCopy();

        return executeAsyncHttpRequest(requestBuilderWrapper, responseHandlerFunction, distributedTraceStack,
                                       mdcContextMap);
    }

    /**
     * Executes the given request asynchronously, handling the response with the given responseHandlerFunction, and
     * returns a {@link CompletableFuture} that represents the result of executing the
     * responseHandlerFunction on the downstream response. Any error anywhere along the way will cause the returned
     * future to be completed with {@link CompletableFuture#completeExceptionally(Throwable)}.
     * <p/>
     * NOTE: This is a helper method for calling {@link #executeAsyncHttpRequest(RequestBuilderWrapper,
     * AsyncResponseHandler, java.util.Deque, java.util.Map)} that uses {@link
     * ChannelAttributes#getHttpProcessingStateForChannel(ChannelHandlerContext)} to extract the {@link
     * HttpProcessingState} from the given ctx argument, and then grabs {@link
     * HttpProcessingState#getDistributedTraceStack()} and {@link HttpProcessingState#getLoggerMdcContextMap()} to use
     * as the distributed trace stack and MDC info for the downstream call.
     */
    public <O> CompletableFuture<O> executeAsyncHttpRequest(RequestBuilderWrapper requestBuilderWrapper,
                                                            AsyncResponseHandler<O> responseHandlerFunction,
                                                            ChannelHandlerContext ctx) {

        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state == null)
            throw new IllegalStateException("state cannot be null");

        Map<String, String> mdcContextMap = state.getLoggerMdcContextMap();
        Deque<Span> distributedTraceStack = state.getDistributedTraceStack();

        requestBuilderWrapper.setCtx(ctx);

        return executeAsyncHttpRequest(requestBuilderWrapper, responseHandlerFunction, distributedTraceStack,
                                       mdcContextMap);
    }

    /**
     * Executes the given request asynchronously, handling the response with the given responseHandlerFunction, and
     * returns a {@link CompletableFuture} that represents the result of executing the
     * responseHandlerFunction on the downstream response. Any error anywhere along the way will cause the returned
     * future to be completed with {@link CompletableFuture#completeExceptionally(Throwable)}.
     * <p/>
     * <b>Distributed Tracing and MDC for the downstream call:</b> The given {@code distributedTraceStackForCall} and
     * {@code mdcContextForCall} arguments are used to setup distributed trace and MDC info for the downstream call so
     * that the callback will be performed with that data attached to whatever thread the callback is done on.
     */
    public <O> CompletableFuture<O> executeAsyncHttpRequest(RequestBuilderWrapper requestBuilderWrapper,
                                                            AsyncResponseHandler<O> responseHandlerFunction,
                                                            Deque<Span> distributedTraceStackForCall,
                                                            Map<String, String> mdcContextForCall) {
        CompletableFuture<O> completableFutureResponse = new CompletableFuture<>();

        try {
            Optional<CircuitBreaker<Response>> circuitBreaker = getCircuitBreaker(requestBuilderWrapper);
            // If we have a circuit breaker, give it a chance to throw an exception if the circuit is open/tripped
            circuitBreaker.ifPresent(CircuitBreaker::throwExceptionIfCircuitBreakerIsOpen);

            // Setup the async completion handler for the call.
            AsyncCompletionHandlerWithTracingAndMdcSupport<O> asyncCompletionHandler =
                new AsyncCompletionHandlerWithTracingAndMdcSupport<>(
                    completableFutureResponse, responseHandlerFunction, performSubSpanAroundDownstreamCalls,
                    requestBuilderWrapper.httpMethod,
                    requestBuilderWrapper.url, circuitBreaker, distributedTraceStackForCall, mdcContextForCall);

            // Add distributed trace headers to the downstream call if we have a span.
            Span spanForCall = asyncCompletionHandler.getSpanForCall();
            if (spanForCall != null) {
                requestBuilderWrapper.requestBuilder.setHeader(TraceHeaders.TRACE_SAMPLED,
                                                               String.valueOf(spanForCall.isSampleable()));
                requestBuilderWrapper.requestBuilder.setHeader(TraceHeaders.TRACE_ID, spanForCall.getTraceId());
                requestBuilderWrapper.requestBuilder.setHeader(TraceHeaders.SPAN_ID, spanForCall.getSpanId());
                requestBuilderWrapper.requestBuilder.setHeader(TraceHeaders.PARENT_SPAN_ID,
                                                               spanForCall.getParentSpanId());
                requestBuilderWrapper.requestBuilder.setHeader(TraceHeaders.SPAN_NAME, spanForCall.getSpanName());
            }
            // Execute the downstream call. The completableFutureResponse will be completed or completed exceptionally
            //      depending on the result of the call.
            requestBuilderWrapper.requestBuilder.execute(asyncCompletionHandler);
        }
        catch (Throwable t) {
            // Log the error for later debugging, unless it's a CircuitBreakerOpenException, which is expected and
            //      normal when the circuit breaker associated with this request has been tripped.
            if (!(t instanceof CircuitBreakerOpenException)) {
                logger.error(
                    "An error occurred while trying to set up an async HTTP call for method {} and URL {}. "
                    + "The CompletableFuture will be instantly failed with this error",
                    requestBuilderWrapper.httpMethod, requestBuilderWrapper.url, t
                );
            }
            completableFutureResponse.completeExceptionally(t);
        }

        return completableFutureResponse;
    }

    protected Optional<CircuitBreaker<Response>> getCircuitBreaker(RequestBuilderWrapper requestBuilderWrapper) {
        if (requestBuilderWrapper.disableCircuitBreaker)
            return Optional.empty();

        // Circuit breaking is enabled for this call. So we return the custom one specified or use the default one if a
        //      custom one is not specified.
        if (requestBuilderWrapper.customCircuitBreaker.isPresent())
            return requestBuilderWrapper.customCircuitBreaker;

        // No custom circuit breaker. Use the default for the given request's host.
        Uri uri = Uri.create(requestBuilderWrapper.url);
        String host = uri.getHost();
        EventLoop nettyEventLoop = requestBuilderWrapper.getCtx() == null
                                   ? null
                                   : requestBuilderWrapper.getCtx().channel().eventLoop();
        CircuitBreaker<Integer> defaultStatusCodeCircuitBreaker = getDefaultHttpStatusCodeCircuitBreakerForKey(
            host, Optional.ofNullable(nettyEventLoop), Optional.ofNullable(nettyEventLoop)
        );
        return Optional.of(
            new CircuitBreakerDelegate<>(
                defaultStatusCodeCircuitBreaker, response -> (response == null ? null : response.getStatusCode())
            )
        );
    }

    protected static class MultiIpAwareNameResolver implements NameResolver {

        public static MultiIpAwareNameResolver INSTANCE = new MultiIpAwareNameResolver();
        protected static final ConcurrentMap<String, AtomicInteger> HOST_ROUND_ROBIN_COUNTER_MAP =
            new ConcurrentHashMap<>();

        @Override
        public InetAddress resolve(String hostName) throws UnknownHostException {
            // Get ALL IP addresses associated with the requested host.
            InetAddress[] ipAddresses = getAllAddressesForHost(hostName);
            // Get-and-increment the atomic int associated with this host, then mod it against the number of instances
            //      available. This effectively round robins the use of all the instances associated with the host.
            AtomicInteger roundRobinCounter =
                HOST_ROUND_ROBIN_COUNTER_MAP.computeIfAbsent(hostName, host -> new AtomicInteger(0));
            int instanceIndexToUse = roundRobinCounter.getAndIncrement() % ipAddresses.length;

            if (instanceIndexToUse < 0) {
                // The counter went high enough to do an integer overflow. Fix the index so we don't blow up this call,
                //      and reset the counter to 0.
                instanceIndexToUse = Math.abs(instanceIndexToUse);
                roundRobinCounter.set(0);
            }

            return ipAddresses[instanceIndexToUse];
        }

        protected InetAddress[] getAllAddressesForHost(String host) throws UnknownHostException {
            return InetAddress.getAllByName(host);
        }
    }
}