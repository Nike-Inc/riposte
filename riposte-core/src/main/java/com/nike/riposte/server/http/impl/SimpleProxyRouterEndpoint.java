package com.nike.riposte.server.http.impl;

import com.nike.fastbreak.CircuitBreaker;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.util.HttpUtils;
import com.nike.riposte.util.Matcher;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Basic extension of {@link ProxyRouterEndpoint} that simply pipes all requests that match {@link
 * #incomingRequestMatcher} to the downstream service specified by the rest of the fields in this class (e.g. {@link
 * #downstreamDestinationHost}, {@link #downstreamDestinationPort}, etc).
 *
 * @author Nic Munroe
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class SimpleProxyRouterEndpoint extends ProxyRouterEndpoint {

    private final Matcher incomingRequestMatcher;
    private final String downstreamDestinationHost;
    private final int downstreamDestinationPort;
    private final String downstreamDestinationUriPath;
    private final boolean isDownstreamCallHttps;
    private final Optional<CircuitBreaker<HttpResponse>> customCircuitBreaker;
    private final boolean disableCircuitBreaker;

    /**
     * Creates a new instance with the given arguments.
     *
     * @param incomingRequestMatcher
     *     Specifies the incoming requests that this endpoint should match.
     * @param downstreamDestinationHost
     *     The downstream host that should get called when an incoming request matches this endpoint.
     * @param downstreamDestinationPort
     *     The port of the downstream host that should get called when an incoming request matches this endpoint.
     * @param downstreamDestinationUriPath
     *     The URI path that should get called when an incoming request matches this endpoint.
     * @param isDownstreamCallHttps
     *     Whether or not HTTPS should be used when making the downstream call. If this is true then HTTPS will be used,
     *     otherwise standard HTTP will be used.
     * @param customCircuitBreaker
     *     An Optional containing a custom circuit breaker if a custom one should be used, or empty if the router
     *     handler should use a default circuit breaker. If you don't want *any* circuit breaker to be used, set {@link
     *     #disableCircuitBreaker} to true. The default circuit breaker will be based on the {@link
     *     #downstreamDestinationHost} value (i.e. all calls to the same host will use the same circuit breaker). If you
     *     need something more (or less) fine grained than that then you'll need to provide a custom circuit breaker.
     * @param disableCircuitBreaker
     *     Set this to true if you don't want *any* circuit breaker to be used - if this is false then {@link
     *     #customCircuitBreaker} will be used to determine which circuit breaker to use (custom vs. default).
     */
    public SimpleProxyRouterEndpoint(Matcher incomingRequestMatcher,
                                     String downstreamDestinationHost,
                                     int downstreamDestinationPort,
                                     String downstreamDestinationUriPath,
                                     boolean isDownstreamCallHttps,
                                     Optional<CircuitBreaker<HttpResponse>> customCircuitBreaker,
                                     boolean disableCircuitBreaker) {
        this.incomingRequestMatcher = incomingRequestMatcher;
        this.downstreamDestinationHost = downstreamDestinationHost;
        this.downstreamDestinationPort = downstreamDestinationPort;
        this.downstreamDestinationUriPath = downstreamDestinationUriPath;
        this.isDownstreamCallHttps = isDownstreamCallHttps;
        this.customCircuitBreaker = customCircuitBreaker;
        this.disableCircuitBreaker = disableCircuitBreaker;
    }

    /**
     * Creates a new instance with the given arguments.
     *
     * @param incomingRequestMatcher
     *     Specifies the incoming requests that this endpoint should match.
     * @param downstreamDestinationHost
     *     The downstream host that should get called when an incoming request matches this endpoint.
     * @param downstreamDestinationPort
     *     The port of the downstream host that should get called when an incoming request matches this endpoint.
     * @param downstreamDestinationUriPath
     *     The URI path that should get called when an incoming request matches this endpoint.
     * @param isDownstreamCallHttps
     *     Whether or not HTTPS should be used when making the downstream call. If this is true then HTTPS will be used,
     *     otherwise standard HTTP will be used.
     */
    public SimpleProxyRouterEndpoint(Matcher incomingRequestMatcher,
                                     String downstreamDestinationHost,
                                     int downstreamDestinationPort,
                                     String downstreamDestinationUriPath,
                                     boolean isDownstreamCallHttps) {
        this(
            incomingRequestMatcher, downstreamDestinationHost, downstreamDestinationPort, downstreamDestinationUriPath,
            isDownstreamCallHttps, Optional.empty(), false
        );
    }

    @Override
    public Matcher requestMatcher() {
        return incomingRequestMatcher;
    }

    @Override
    public CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(
        RequestInfo<?> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx
    ) {
        return CompletableFuture.completedFuture(
            new DownstreamRequestFirstChunkInfo(
                downstreamDestinationHost,
                downstreamDestinationPort,
                isDownstreamCallHttps,
                generateSimplePassthroughRequest(
                    request, HttpUtils.replaceUriPathVariables(request, downstreamDestinationUriPath),
                    request.getMethod(), ctx
                ),
                customCircuitBreaker,
                disableCircuitBreaker
            )
        );
    }
}
