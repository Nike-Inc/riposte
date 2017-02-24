package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.apierror.sample.SampleCoreApiError;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.backstopper.model.DefaultErrorDTO;
import com.nike.internal.util.MapBuilder;
import com.nike.internal.util.Pair;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Test class verifying the following:
 * <ul>
 *     <li>
 *          When a {@link ProxyRouterEndpoint} downstream call takes longer than
 *          {@link ServerConfig#defaultCompletableFutureTimeoutInMillisForNonblockingEndpoints()}, then the call is immediately cancelled and a
 *          {@link ProjectApiErrors#getTemporaryServiceProblemApiError()} error is returned (which for the default server impl will be
 *          a {@link SampleCoreApiError#TEMPORARY_SERVICE_PROBLEM}).
 *     </li>
 *     <li>
 *         When a {@link io.netty.channel.Channel} sits idle longer than {@link ServerConfig#workerChannelIdleTimeoutMillis()} then it is automatically closed.
 *     </li>
 *     <li>
 *         When a proxy routing endpoint is called repeatedly, connection pooling is used to reuse channels and minimize connection time.
 *     </li>
 *     <li>
 *         When a call is started but not finished (i.e. we've received the first chunk but not the last), and no
 *         further bytes are received for {@link ServerConfig#incompleteHttpCallTimeoutMillis()}, then a {@link
 *         ProjectApiErrors#getTemporaryServiceProblemApiError()} is returned.
 *     </li>
 * </ul>
 */
public class VerifyTimeoutsAndProxyConnectionPoolingWorksComponentTest {

    private static Server proxyServerShortCallTimeout;
    private static ServerConfig proxyServerShortCallTimeoutConfig;

    private static Server proxyServerLongTimeoutValues;
    private static ServerConfig proxyServerLongTimeoutValuesConfig;

    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;

    private static ObjectMapper objectMapper = new ObjectMapper();

    private static long incompleteCallTimeoutMillis = 200;

    @BeforeClass
    public static void setUpClass() throws Exception {
        proxyServerShortCallTimeoutConfig = new TimeoutsAndProxyTestServerConfig(60 * 1000,
                                                                                 150,
                                                                                 incompleteCallTimeoutMillis);
        proxyServerShortCallTimeout = new Server(proxyServerShortCallTimeoutConfig);
        proxyServerShortCallTimeout.startup();

        proxyServerLongTimeoutValuesConfig = new TimeoutsAndProxyTestServerConfig(60 * 1000,
                                                                                  60 * 1000,
                                                                                  incompleteCallTimeoutMillis);
        proxyServerLongTimeoutValues = new Server(proxyServerLongTimeoutValuesConfig);
        proxyServerLongTimeoutValues.startup();

        downstreamServerConfig = new TimeoutsAndProxyTestServerConfig(300,
                                                                      60 * 1000,
                                                                      incompleteCallTimeoutMillis);
        downstreamServer = new Server(downstreamServerConfig);
        downstreamServer.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        downstreamServer.shutdown();
        proxyServerLongTimeoutValues.shutdown();
        proxyServerShortCallTimeout.shutdown();
    }

    @Test
    public void verify_quick_proxy_call_works_without_timeout() throws IOException, InterruptedException {
        String responseString =
                    given()
                        .baseUri("http://127.0.0.1")
                        .port(proxyServerShortCallTimeoutConfig.endpointsPort())
                        .basePath(ProxyLongDelayTestEndpoint.MATCHING_PATH)
                        .header(LongDelayTestEndpoint.DELAY_MILLIS_HEADER_KEY, "1")
                        .log().all()
                    .when()
                        .get()
                    .then()
                        .log().all()
                        .statusCode(200)
                        .extract().asString();

        assertThat(responseString).isEqualTo(LongDelayTestEndpoint.SUCCESS_STRING);
    }

    @Test
    public void verify_long_proxy_call_fails_with_timeout() throws IOException, InterruptedException {
        String responseString =
                    given()
                        .baseUri("http://127.0.0.1")
                        .port(proxyServerShortCallTimeoutConfig.endpointsPort())
                        .basePath(ProxyLongDelayTestEndpoint.MATCHING_PATH)
                        .header(LongDelayTestEndpoint.DELAY_MILLIS_HEADER_KEY,
                                String.valueOf(proxyServerShortCallTimeoutConfig.defaultCompletableFutureTimeoutInMillisForNonblockingEndpoints() + 100))
                        .log().all()
                    .when()
                        .get()
                    .then()
                        .log().all()
                        .statusCode(SampleCoreApiError.TEMPORARY_SERVICE_PROBLEM.getHttpStatusCode())
                        .extract().asString();

        DefaultErrorContractDTO errorContract = objectMapper.readValue(responseString, DefaultErrorContractDTO.class);
        assertThat(errorContract).isNotNull();
        assertThat(errorContract.errors.size()).isEqualTo(1);
        assertThat(errorContract.errors.get(0).code).isEqualTo(SampleCoreApiError.TEMPORARY_SERVICE_PROBLEM.getErrorCode());
    }

    private String performChannelInfoProxyCall() {
        // This calls proxyServerLongTimeoutValuesConfig, which proxies the request to downstreamServer to return downstreamServer's channel info.
        return given()
                    .baseUri("http://127.0.0.1")
                    .port(proxyServerLongTimeoutValuesConfig.endpointsPort())
                    .basePath(ProxyChannelInfoTestEndpoint.MATCHING_PATH)
                    .log().all()
                .when()
                    .get()
                .then()
                    .log().all()
                    .statusCode(200)
                    .extract().asString();
    }

    @Test
    public void verify_connection_pooling_works_for_proxy_calls() {
        // given
        String initialCallChannelInfo = performChannelInfoProxyCall();

        // when
        String secondCallChannelInfo = performChannelInfoProxyCall();

        // then
        assertThat(initialCallChannelInfo).isEqualTo(secondCallChannelInfo);
    }

    @Test
    public void verify_timeout_kills_idle_channels() throws InterruptedException {
        // given - we verify that we're using a pooled connection and it works if we make a call sooner than the channel idle timeout
        String initialCallChannelInfo = performChannelInfoProxyCall();
        Thread.sleep(downstreamServerConfig.workerChannelIdleTimeoutMillis() - 100);
        String secondCallChannelInfo = performChannelInfoProxyCall();
        assertThat(initialCallChannelInfo).isEqualTo(secondCallChannelInfo);

        // when - we wait longer than the channel idle timeout value and then make another call
        Thread.sleep(downstreamServerConfig.workerChannelIdleTimeoutMillis() + 100);
        String afterTimeoutCallChannelInfo = performChannelInfoProxyCall();

        // then - the pooled channel should have closed and a new one used
        assertThat(afterTimeoutCallChannelInfo).isNotEqualTo(initialCallChannelInfo);
    }

    @Test
    public void verify_incomplete_call_is_timed_out() throws InterruptedException, TimeoutException,
                                                             ExecutionException, IOException {
        Bootstrap bootstrap = new Bootstrap();
        EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
        try {
            CompletableFuture<Pair<String, String>> responseFromServer = new CompletableFuture<>();

            // Create a raw netty HTTP client so we can fiddle with headers and intentionally create a bad request
            //      that should trigger the bad call timeout.
            bootstrap.group(eventLoopGroup)
                     .channel(NioSocketChannel.class)
                     .handler(new ChannelInitializer<SocketChannel>() {
                         @Override
                         protected void initChannel(SocketChannel ch) throws Exception {
                             ChannelPipeline p = ch.pipeline();
                             p.addLast(new HttpClientCodec());
                             p.addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
                             p.addLast(new SimpleChannelInboundHandler<HttpObject>() {
                                 @Override
                                 protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg)
                                     throws Exception {
                                     if (msg instanceof FullHttpResponse) {
                                         // Store the server response for asserting on later.
                                         FullHttpResponse responseMsg = (FullHttpResponse)msg;
                                         responseFromServer.complete(
                                             Pair.of(responseMsg.content().toString(CharsetUtil.UTF_8),
                                                     responseMsg.headers().get(HttpHeaders.Names.CONNECTION))
                                         );
                                     }
                                     else {
                                         // Should never happen.
                                         throw new RuntimeException(
                                             "Received unexpected message type: " + msg.getClass());
                                     }
                                 }
                             });
                         }
                     });

            // Connect to the server.
            Channel ch = bootstrap.connect("localhost", downstreamServerConfig.endpointsPort()).sync().channel();

            // Create a bad HTTP request. This one will be bad because it has a non-zero content-length header,
            //      but we're sending no payload. The server should (correctly) sit and wait for payload bytes to
            //      arrive until it hits the timeout, at which point it should return the correct error response.
            HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, LongDelayTestEndpoint.MATCHING_PATH
            );
            request.headers().set(HttpHeaders.Names.HOST, "localhost");
            request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);

            request.headers().set(HttpHeaders.Names.CONTENT_LENGTH, "100");

            long beforeCallTimeNanos = System.nanoTime();
            // Send the bad request.
            ch.writeAndFlush(request);
            // Wait for the response to be received and the connection to be closed.
            try {
                ch.closeFuture().get(incompleteCallTimeoutMillis * 10, TimeUnit.MILLISECONDS);
                responseFromServer.get(incompleteCallTimeoutMillis * 10, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException ex) {
                fail("The call took much longer than expected without receiving a response. "
                     + "Cancelling this test - it's not working properly", ex);
            }
            // If we reach here then the call should be complete.
            long totalCallTimeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeCallTimeNanos);

            // Verify that we got back the correct error response.
            //      It should be a MALFORMED_REQUEST with extra metadata explaining that the call was bad.
            Pair<String, String> responseInfo = responseFromServer.get();
            DefaultErrorContractDTO errorContract = objectMapper.readValue(responseInfo.getLeft(),
                                                                           DefaultErrorContractDTO.class);
            assertThat(errorContract).isNotNull();
            assertThat(errorContract.errors.size()).isEqualTo(1);
            DefaultErrorDTO error = errorContract.errors.get(0);
            ApiError expectedApiError = SampleCoreApiError.MALFORMED_REQUEST;
            Map<String, Object> expectedMetadata =
                MapBuilder.builder("cause", (Object)"Unfinished/invalid HTTP request").build();
            assertThat(error.code).isEqualTo(expectedApiError.getErrorCode());
            assertThat(error.message).isEqualTo(expectedApiError.getMessage());
            assertThat(error.metadata).isEqualTo(expectedMetadata);

            // The server should have closed the connection even though we asked for keep-alive.
            assertThat(responseInfo.getRight()).isEqualTo(HttpHeaders.Values.CLOSE);

            // Total call time should be pretty close to incompleteCallTimeoutMillis give or take a few
            //      milliseconds, but due to the inability to account for slow machines running the unit tests,
            //      a server that isn't warmed up, etc, we can't put a ceiling on the wiggle room we'd need, so
            //      we'll just verify it took at least the minimum necessary amount of time.
            assertThat(totalCallTimeMillis).isGreaterThanOrEqualTo(incompleteCallTimeoutMillis);
        }
        finally {
            eventLoopGroup.shutdownGracefully();
        }
    }

    public static class TimeoutsAndProxyTestServerConfig implements ServerConfig {
        private final int port;
        private final long workerChannelIdleTimeoutMillis;
        private final long cfTimeoutMillis;
        private final long incompleteCallTimeoutMillis;
        private final Collection<Endpoint<?>> endpoints = Arrays.asList(
            new LongDelayTestEndpoint(),
            new ProxyLongDelayTestEndpoint(),
            new ChannelInfoTestEndpoint(),
            new ProxyChannelInfoTestEndpoint()
        );

        public TimeoutsAndProxyTestServerConfig(long workerChannelIdleTimeoutMillis,
                                                long cfTimeoutMillis,
                                                long incompleteCallTimeoutMillis) {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            this.workerChannelIdleTimeoutMillis = workerChannelIdleTimeoutMillis;
            this.cfTimeoutMillis = cfTimeoutMillis;
            this.incompleteCallTimeoutMillis = incompleteCallTimeoutMillis;
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }

        @Override
        public long workerChannelIdleTimeoutMillis() {
            return workerChannelIdleTimeoutMillis;
        }

        @Override
        public long defaultCompletableFutureTimeoutInMillisForNonblockingEndpoints() {
            return cfTimeoutMillis;
        }

        @Override
        public long incompleteHttpCallTimeoutMillis() {
            return incompleteCallTimeoutMillis;
        }
    }

    public static class LongDelayTestEndpoint extends StandardEndpoint<String, String> {

        public static final String MATCHING_PATH = "/longdelay";
        public static final String DELAY_MILLIS_HEADER_KEY = "delayMillis";
        public static final String SUCCESS_STRING = "done";

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<String> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            long delayMillis = Long.parseLong(request.getHeaders().get(DELAY_MILLIS_HEADER_KEY));

            CompletableFuture<ResponseInfo<String>> cf = new CompletableFuture<>();

            ctx.executor().schedule(() -> cf.complete(ResponseInfo.newBuilder(SUCCESS_STRING).build()), delayMillis, TimeUnit.MILLISECONDS);

            return cf;
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }

    }

    public static class ProxyLongDelayTestEndpoint extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/proxy" + LongDelayTestEndpoint.MATCHING_PATH;

        @Override
        public CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(RequestInfo<?> request,
                                                                                                     Executor longRunningTaskExecutor,
                                                                                                     ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                    new DownstreamRequestFirstChunkInfo(
                            "127.0.0.1",
                            downstreamServerConfig.endpointsPort(),
                            false,
                            generateSimplePassthroughRequest(request, LongDelayTestEndpoint.MATCHING_PATH, HttpMethod.GET, ctx)
                    )
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }

    public static class ChannelInfoTestEndpoint extends StandardEndpoint<String, String> {

        public static final String MATCHING_PATH = "/channelinfo";

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<String> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(ResponseInfo.newBuilder(ctx.channel().toString()).build());
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }

    }

    public static class ProxyChannelInfoTestEndpoint extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/proxy" + ChannelInfoTestEndpoint.MATCHING_PATH;

        @Override
        public CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(RequestInfo<?> request,
                                                                                                     Executor longRunningTaskExecutor,
                                                                                                     ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                    new DownstreamRequestFirstChunkInfo(
                            "127.0.0.1",
                            downstreamServerConfig.endpointsPort(),
                            false,
                            generateSimplePassthroughRequest(request, ChannelInfoTestEndpoint.MATCHING_PATH, HttpMethod.GET, ctx)
                    )
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }
}
