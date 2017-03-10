package com.nike.riposte.server.http;

import com.nike.fastbreak.CircuitBreaker;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;

/**
 * Extension of {@link Endpoint} for handling reverse proxy/domain router/edge router style requests.
 *
 * @author Nic Munroe
 */
public abstract class ProxyRouterEndpoint implements Endpoint {

    /**
     * Returns a {@link CompletableFuture} that supplies the information for the downstream request's first chunk. That
     * info contains the host/port of the server to hit, whether or not HTTPS should be used, and the {@link
     * HttpRequest} to stream. That {@link ProxyRouterEndpoint.DownstreamRequestFirstChunkInfo#firstChunk} will in turn
     * contain the URI path and headers, etc, that should be streamed in the first chunk.
     * <p/>
     * This method returns a {@link CompletableFuture} because depending on the requirements of the proxy routing
     * endpoint it may not know what downstream system to connect to or what to send until an unspecified amount of time
     * has passed (e.g. DNS or Eureka lookups), and we can't allow this method to block.
     */
    public abstract CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(
        RequestInfo<?> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx);

    /**
     * This method allows for the inspection and optional alteration of the first chunk of the downstream system's
     * response back to the caller. Note that this method is only called for the first chunk of the response which
     * contains HTTP status code and headers - the payload is not available, so the HTTP status code and/or headers are
     * the only things you can modify. <br/> If you need to inspect and/or alter the response *payload* then you
     * shouldn't be using a {@link ProxyRouterEndpoint}, you should be using a normal {@link StandardEndpoint} instead
     * and making your downstream call with an HTTP client (ideally an async nonblocking client like {@code
     * AsyncHttpClientHelper}) so you can do whatever you want with the response payload before returning to the
     * caller.
     * <p/>
     * By default this method does nothing. Override this method if you need alternate behavior.
     * <p/>
     * <b>IMPORTANT NOTE:</b> This method is powerful - be careful with it. In particular if you do something expensive
     * in here you could be blocking Netty I/O worker threads, severely bottlenecking your application. If you need to
     * do something expensive like make an HTTP call to another service to report on the result of this call, then do
     * that asynchronously so that this method returns almost instantly.
     *
     * TODO: Investigate having this return a {@code CompletableFuture} instead so we don't have to worry about blocking
     * worker threads. That change would need careful testing to make sure we don't break exception handling (what
     * happens if an error occurs in the CompletableFuture?), and would need performance testing to make sure it doesn't
     * do much to affect throughput in the normal case where this method does nothing. Seems like an unlikely use case
     * though, so maybe wait until it's asked for?
     *
     * @param downstreamResponseFirstChunk
     *     The first chunk data from the downstream system's response - includes HTTP status code and response headers.
     * @param origRequestInfo
     *     The original request info that was passed into {@link #getDownstreamRequestFirstChunkInfo(RequestInfo,
     *     Executor, ChannelHandlerContext)} before the downstream system was called. If you need to calculate something
     *     in {@link #getDownstreamRequestFirstChunkInfo(RequestInfo, Executor, ChannelHandlerContext)} and want to pass
     *     it into this method you can pass it via the {@link RequestInfo#getRequestAttributes()} map and it will be
     *     available in {@code origRequestInfo} here when this method is called.
     */
    @SuppressWarnings("UnusedParameters")
    public void handleDownstreamResponseFirstChunk(HttpResponse downstreamResponseFirstChunk,
                                                   RequestInfo<?> origRequestInfo) {
        // Do nothing by default
    }

    /**
     * Proxy router endpoints don't generally do anything with content, so return null by default.
     */
    @Override
    public TypeReference requestContentType() {
        return null;
    }

    /**
     * Proxy router endpoints don't generally validate content, so return false by default.
     */
    @Override
    public boolean isValidateRequestContent(@SuppressWarnings("UnusedParameters") RequestInfo request) {
        return false;
    }

    /**
     * Helper method that generates a {@link HttpRequest} for the downstream call's first chunk that uses the given
     * downstreamPath and downstreamMethod, and the query string and headers from the incomingRequest will be added and
     * passed through without modification.
     */
    @SuppressWarnings("UnusedParameters")
    protected HttpRequest generateSimplePassthroughRequest(RequestInfo<?> incomingRequest, String downstreamPath,
                                                           HttpMethod downstreamMethod, ChannelHandlerContext ctx) {
        String queryString = extractQueryString(incomingRequest.getUri());
        String downstreamUri = downstreamPath;
        // TODO: Add logic to support when downstreamPath already has a query string on it. The two query strings should be combined
        if (queryString != null)
            downstreamUri += queryString;
        HttpRequest downstreamRequestInfo =
            new DefaultHttpRequest(HttpVersion.HTTP_1_1, downstreamMethod, downstreamUri);
        downstreamRequestInfo.headers().set(incomingRequest.getHeaders());
        return downstreamRequestInfo;
    }

    /**
     * Helper methdod that returns the query string of the given URI (including the question mark), or null if the given
     * URI does not have a query string. You can use this to extract the query string from {@link
     * com.nike.riposte.server.http.RequestInfo#getUri()} - potentially useful depending on how you need to setup the
     * return value for {@link #getDownstreamRequestFirstChunkInfo(RequestInfo, java.util.concurrent.Executor,
     * io.netty.channel.ChannelHandlerContext)} .
     * <p/>
     * e.g. Passing in {@code "/some/path?foo=bar"} will result in this method returning {@code "?foo=bar"}.
     * <p/>
     * e.g. Passing in {@code "/some/path"} will result in this method returning {@code null}.
     * <p/>
     * e.g. Passing in {@code "/some/path?"} will result in this method returning {@code "?"}.
     */
    @SuppressWarnings("WeakerAccess")
    protected String extractQueryString(String uri) {
        int questionMarkIndex = uri.indexOf('?');
        if (questionMarkIndex == -1)
            return null;

        return uri.substring(questionMarkIndex);
    }

    /**
     * Contains the info necessary for the proxy router handler to route the request downstream appropriately.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public static class DownstreamRequestFirstChunkInfo {

        /**
         * The host to call for the downstream request.
         */
        public final String host;
        /**
         * The port to use for the downstream request.
         */
        public final int port;
        /**
         * True if the downstream request should use HTTPS, false if it should use HTTP.
         */
        public final boolean isHttps;
        /**
         * The first chunk info for the downstream request - contains uri/path, HTTP method, headers, and HTTP protocol
         * that should be used.
         */
        public final HttpRequest firstChunk;
        /**
         * An Optional containing a custom circuit breaker if a custom one should be used, or empty if the router
         * handler should use a default circuit breaker. If you don't want *any* circuit breaker to be used, set {@link
         * #disableCircuitBreaker} to true. The default circuit breaker will be based on the {@link #host} value (i.e.
         * all calls to the same host will use the same circuit breaker). If you need something more (or less) fine
         * grained than that then you'll need to provide a custom circuit breaker.
         */
        public final Optional<CircuitBreaker<HttpResponse>> customCircuitBreaker;
        /**
         * Set this to true if you don't want *any* circuit breaker to be used - if this is false then {@link
         * #customCircuitBreaker} will be used to determine which circuit breaker to use (custom vs. default).
         */
        public final boolean disableCircuitBreaker;
        /**
         * Set this to true if you are hitting a service with HTTPS that has an invalid cert, or a cert that the JVM
         * doesn't recognize as trusted, and you want to trust it anyway. WARNING: Setting this to true opens you up to
         * security vulnerabilities - make sure you know what you're doing.
         */
        public boolean relaxedHttpsValidation = false;

        /**
         * Creates a new instance with the given values, and defaults {@link #customCircuitBreaker} to {@link
         * Optional#empty()} and {@link #disableCircuitBreaker} to false - telling the router handler to use the default
         * circuit breaker for the given {@link #host}.
         *
         * @param host
         *     the value for {@link #host}
         * @param port
         *     the value for {@link #port}
         * @param isHttps
         *     the value for {@link #isHttps}
         * @param firstChunk
         *     the value for {@link #firstChunk}
         */
        public DownstreamRequestFirstChunkInfo(String host, int port, boolean isHttps, HttpRequest firstChunk) {
            this(host, port, isHttps, firstChunk, Optional.empty(), false);
        }

        /**
         * Creates a new instance with the given values.
         *
         * @param host
         *     the value for {@link #host}
         * @param port
         *     the value for {@link #port}
         * @param isHttps
         *     the value for {@link #isHttps}
         * @param firstChunk
         *     the value for {@link #firstChunk}
         * @param customCircuitBreaker
         *     the value for {@link #customCircuitBreaker}
         * @param disableCircuitBreaker
         *     the value for {@link #disableCircuitBreaker}
         */
        public DownstreamRequestFirstChunkInfo(String host, int port, boolean isHttps, HttpRequest firstChunk,
                                               Optional<CircuitBreaker<HttpResponse>> customCircuitBreaker,
                                               boolean disableCircuitBreaker) {
            this.host = host;
            this.port = port;
            this.isHttps = isHttps;
            this.firstChunk = firstChunk;
            this.customCircuitBreaker = (customCircuitBreaker == null) ? Optional.empty() : customCircuitBreaker;
            this.disableCircuitBreaker = disableCircuitBreaker;
        }

        /**
         * Pass in true if you are hitting a service with HTTPS that has an invalid cert, or a cert that the JVM doesn't
         * recognize as trusted, and you want to trust it anyway. Defaults to false.
         * <p/>
         * WARNING: Setting this to true opens you up to security vulnerabilities - make sure you know what you're
         * doing.
         */
        public DownstreamRequestFirstChunkInfo withRelaxedHttpsValidation(boolean relaxedHttpsValidation) {
            this.relaxedHttpsValidation = relaxedHttpsValidation;
            return this;
        }
    }

    /**
     * Proxy router endpoints don't generally want to limit the request size they are proxying, so return 0 to disable
     */
    @Override
    public Integer maxRequestSizeInBytesOverride() {
        return 0;
    }
}
