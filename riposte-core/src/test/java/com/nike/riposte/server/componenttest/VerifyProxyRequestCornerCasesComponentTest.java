package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.exception.ApiException;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.hooks.PipelineCreateHook;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.http.impl.SimpleProxyRouterEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils.NettyHttpClientRequestBuilder;
import com.nike.riposte.server.testutils.ComponentTestUtils.NettyHttpClientResponse;
import com.nike.riposte.util.Matcher;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.LastHttpContent;

import static com.nike.riposte.server.channelpipeline.HttpChannelInitializer.PROXY_ROUTER_ENDPOINT_EXECUTION_HANDLER_NAME;
import static com.nike.riposte.server.componenttest.VerifyProxyRequestCornerCasesComponentTest.IntentionalExplosionHandler.INTENTIONAL_EXPLOSION_AFTER_LAST_CHUNK_API_ERROR;
import static com.nike.riposte.server.componenttest.VerifyProxyRequestCornerCasesComponentTest.IntentionalExplosionHandler.INTENTIONAL_EXPLOSION_AFTER_LAST_CHUNK_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyProxyRequestCornerCasesComponentTest.ShortCircuitingEndpoint.FAIL_FAST_API_ERROR;
import static com.nike.riposte.server.testutils.ComponentTestUtils.connectNettyHttpClientToLocalServer;
import static com.nike.riposte.server.testutils.ComponentTestUtils.createNettyHttpClientBootstrap;
import static com.nike.riposte.server.testutils.ComponentTestUtils.findFreePort;
import static com.nike.riposte.server.testutils.ComponentTestUtils.generatePayload;
import static com.nike.riposte.server.testutils.ComponentTestUtils.request;
import static com.nike.riposte.server.testutils.ComponentTestUtils.verifyErrorReceived;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

public class VerifyProxyRequestCornerCasesComponentTest {

    private static Server proxyServer;
    private static ServerConfig proxyServerConfig;
    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        downstreamServerConfig = new DownstreamServerTestConfig();
        downstreamServer = new Server(downstreamServerConfig);
        downstreamServer.startup();

        proxyServerConfig = new RouterServerTestConfig();
        proxyServer = new Server(proxyServerConfig);
        proxyServer.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        proxyServer.shutdown();
        downstreamServer.shutdown();
    }

    /**
     * This test verifies the corner case where the downstream system short circuits and returns a response before
     * it receives the full request (i.e. the request chunks are still streaming when the response is returned).
     * This should cause request chunks to fail to stream with an error, but the downstream response should still
     * be returned to the original caller successfully.
     */
    @Test
    public void proxy_endpoints_should_successfully_return_short_circuited_downstream_response() throws Exception {
        // Do this test a bunch of times to try and catch all the race condition possibilities.
        for (int i = 0; i < 20; i++) {
            // given
            int payloadSize = 1024 * 1000;
            String payload = generatePayload(payloadSize);

            NettyHttpClientRequestBuilder request =
                request()
                    .withMethod(HttpMethod.POST)
                    .withUri(RouterEndpointForwardingToShortCircuitError.MATCHING_PATH)
                    .withPaylod(payload)
                    .withHeader(HttpHeaders.Names.CONTENT_LENGTH, payloadSize);

            // when
            NettyHttpClientResponse serverResponse = request.execute(proxyServerConfig.endpointsPort(), 3000);

            // then
            verifyErrorReceived(serverResponse.payload, serverResponse.statusCode, FAIL_FAST_API_ERROR);
        }
    }

    /**
     * This test verifies the corner case where a proxy/router call fails on the request side due to an exception, and
     * then a new request is fired immediately on the same channel while the downstream call from the original
     * request is still active. When the exception occurred on the original request, it should shut down and disable
     * anything from the original downstream call from proceeding, otherwise the original downstream call's response
     * data might get fired down the pipeline for the second response (most likely triggering a HTTP state error in
     * Netty's HttpResponseEncoder and causing the channel to be closed, breaking the second response).
     */
    @Test
    public void proxy_router_endpoints_should_immediately_stop_downstream_call_on_unexpected_request_side_error()
        throws IOException, InterruptedException, TimeoutException, ExecutionException {

        Bootstrap bootstrap = createNettyHttpClientBootstrap();
        // We reuse the channel to guarantee the requests travel over the same keep-alive connection.
        Channel proxyServerChannel = connectNettyHttpClientToLocalServer(bootstrap, proxyServerConfig.endpointsPort());

        long maxRequestTimeout = LongerDelayEndpoint.DELAY_MILLIS + 1000;

        try {
            // Execute a request that routes to the normal-delay endpoint that triggers the request-side-short-circuit-error.
            {
                NettyHttpClientRequestBuilder request =
                    request()
                        .withMethod(HttpMethod.POST)
                        .withUri(RouterEndpointForwardingToDelayEndpoint.MATCHING_PATH)
                        .withHeader(INTENTIONAL_EXPLOSION_AFTER_LAST_CHUNK_HEADER_KEY, "true");

                NettyHttpClientResponse response = request.execute(proxyServerChannel, maxRequestTimeout);

                verifyErrorReceived(response.payload, response.statusCode,
                                    INTENTIONAL_EXPLOSION_AFTER_LAST_CHUNK_API_ERROR);
            }

            // Then immediately make another call that routes to the longer-delay endpoint. The response should be
            //      from the longer-delay endpoint, *not* the normal-delay endpoint.
            {
                NettyHttpClientRequestBuilder request =
                    request()
                        .withMethod(HttpMethod.POST)
                        .withUri(RouterEndpointForwardingToLongerDelayEndpoint.MATCHING_PATH);

                long beforeMillis = System.currentTimeMillis();
                NettyHttpClientResponse response = request.execute(proxyServerChannel, maxRequestTimeout);
                long afterMillis = System.currentTimeMillis();

                assertThat(response.statusCode).isEqualTo(200);
                assertThat(response.payload).isEqualTo(LongerDelayEndpoint.RESPONSE_PAYLOAD);
                assertThat(afterMillis - beforeMillis).isGreaterThanOrEqualTo(LongerDelayEndpoint.DELAY_MILLIS);
            }
        }
        finally {
            proxyServerChannel.close();
            bootstrap.config().group().shutdownGracefully();
        }
    }

    public static class RouterEndpointForwardingToShortCircuitError extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/routeToShortCircuitingEndpoint";
        private final int downstreamPort;

        public RouterEndpointForwardingToShortCircuitError(int downstreamPort) {
            this.downstreamPort = downstreamPort;
        }

        @Override
        public @NotNull CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(
            @NotNull RequestInfo<?> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            return CompletableFuture.completedFuture(
                new DownstreamRequestFirstChunkInfo(
                    "127.0.0.1", downstreamPort, false,
                    generateSimplePassthroughRequest(request, ShortCircuitingEndpoint.MATCHING_PATH, request.getMethod(), ctx)
                )
            );
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class RouterEndpointForwardingToDelayEndpoint extends SimpleProxyRouterEndpoint {
        public static final String MATCHING_PATH = "/routeToDelayEndpoint";

        public RouterEndpointForwardingToDelayEndpoint(int downstreamPort) {
            super(Matcher.match(MATCHING_PATH), "127.0.0.1", downstreamPort,
                  DelayEndpoint.MATCHING_PATH, false);
        }
    }

    public static class RouterEndpointForwardingToLongerDelayEndpoint extends SimpleProxyRouterEndpoint {
        public static final String MATCHING_PATH = "/routeToLongerDelayEndpoint";

        public RouterEndpointForwardingToLongerDelayEndpoint(int downstreamPort) {
            super(Matcher.match(MATCHING_PATH), "127.0.0.1", downstreamPort,
                  LongerDelayEndpoint.MATCHING_PATH, false);
        }
    }

    public static class ShortCircuitingEndpoint extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/shortCircuitingEndpoint";
        public static final ApiError FAIL_FAST_API_ERROR =
            new ApiErrorBase("FAIL_FAST_API_ERROR", 42, "Fail fast error occurred", 400);

        public ShortCircuitingEndpoint() {
        }

        @Override
        public @NotNull CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(
            @NotNull RequestInfo<?> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            throw new ApiException(FAIL_FAST_API_ERROR);
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class DelayEndpoint extends StandardEndpoint<Void, String> {
        public static final String MATCHING_PATH = "/delayEndpoint";
        public static final long DELAY_MILLIS = 1000;
        public static final String RESPONSE_PAYLOAD = "delay-endpoint-" + UUID.randomUUID().toString();

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<Void> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(DELAY_MILLIS);
                }
                catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build();
            });
        }
    }

    public static class LongerDelayEndpoint extends StandardEndpoint<Void, String> {
        public static final String MATCHING_PATH = "/longerDelayEndpoint";
        public static final long DELAY_MILLIS = DelayEndpoint.DELAY_MILLIS * 2;
        public static final String RESPONSE_PAYLOAD = "longer-delay-endpoint-" + UUID.randomUUID().toString();

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<Void> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(DELAY_MILLIS);
                }
                catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build();
            });
        }
    }

    public static class DownstreamServerTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public DownstreamServerTestConfig() {
            try {
                port = findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = asList(new ShortCircuitingEndpoint(), new DelayEndpoint(), new LongerDelayEndpoint());
        }

        @Override
        public @NotNull Collection<@NotNull Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }
    }

    public static class RouterServerTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public RouterServerTestConfig() {
            try {
                port = findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = asList(new RouterEndpointForwardingToShortCircuitError(downstreamServerConfig.endpointsPort()),
                               new RouterEndpointForwardingToDelayEndpoint(downstreamServerConfig.endpointsPort()),
                               new RouterEndpointForwardingToLongerDelayEndpoint(downstreamServerConfig.endpointsPort()));
        }

        @Override
        public @NotNull Collection<@NotNull Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }

        @Override
        public @Nullable List<@NotNull PipelineCreateHook> pipelineCreateHooks() {
            return singletonList(new ProxyRouterExplosionPipelineHook());
        }

    }

    public static class ProxyRouterExplosionPipelineHook implements PipelineCreateHook {
        @Override
        public void executePipelineCreateHook(@NotNull ChannelPipeline pipeline) {
            pipeline.addBefore(PROXY_ROUTER_ENDPOINT_EXECUTION_HANDLER_NAME,
                               "intentionalExplosionHandler",
                               new IntentionalExplosionHandler());
        }
    }

    public static class IntentionalExplosionHandler extends ChannelInboundHandlerAdapter {

        public static final String INTENTIONAL_EXPLOSION_AFTER_LAST_CHUNK_HEADER_KEY = "explode-after-last-chunk";
        public static final ApiError INTENTIONAL_EXPLOSION_AFTER_LAST_CHUNK_API_ERROR = new ApiErrorBase(
            "INTENTIONAL_EXPLOSION_AFTER_LAST_CHUNK_API_ERROR", 42, "Intentional explosion after last chunk", 500
        );

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof LastHttpContent) {
                HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
                if ("true".equals(state.getRequestInfo().getHeaders().get(INTENTIONAL_EXPLOSION_AFTER_LAST_CHUNK_HEADER_KEY))) {
                    ctx.channel().eventLoop().schedule(() -> {
                        ctx.fireExceptionCaught(new ApiException(INTENTIONAL_EXPLOSION_AFTER_LAST_CHUNK_API_ERROR));
                    }, 100, TimeUnit.MILLISECONDS);
                }
            }

            super.channelRead(ctx, msg);
        }
    }
}