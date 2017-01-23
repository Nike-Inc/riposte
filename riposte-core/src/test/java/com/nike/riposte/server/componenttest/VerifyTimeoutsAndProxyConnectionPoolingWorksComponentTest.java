package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.apierror.sample.SampleCoreApiError;
import com.nike.backstopper.model.DefaultErrorContractDTO;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

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

    @BeforeClass
    public static void setUpClass() throws Exception {
        proxyServerShortCallTimeoutConfig = new TimeoutsAndProxyTestServerConfig(60 * 1000, 150);
        proxyServerShortCallTimeout = new Server(proxyServerShortCallTimeoutConfig);
        proxyServerShortCallTimeout.startup();

        proxyServerLongTimeoutValuesConfig = new TimeoutsAndProxyTestServerConfig(60 * 1000, 60 * 1000);
        proxyServerLongTimeoutValues = new Server(proxyServerLongTimeoutValuesConfig);
        proxyServerLongTimeoutValues.startup();

        downstreamServerConfig = new TimeoutsAndProxyTestServerConfig(300, 60 * 1000);
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

    public static class TimeoutsAndProxyTestServerConfig implements ServerConfig {
        private final int port;
        private final long workerChannelIdleTimeoutMillis;
        private final long cfTimeoutMillis;
        private final Collection<Endpoint<?>> endpoints = Arrays.asList(
            new LongDelayTestEndpoint(),
            new ProxyLongDelayTestEndpoint(),
            new ChannelInfoTestEndpoint(),
            new ProxyChannelInfoTestEndpoint()
        );

        public TimeoutsAndProxyTestServerConfig(long workerChannelIdleTimeoutMillis, long cfTimeoutMillis) {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            this.workerChannelIdleTimeoutMillis = workerChannelIdleTimeoutMillis;
            this.cfTimeoutMillis = cfTimeoutMillis;
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
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
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
