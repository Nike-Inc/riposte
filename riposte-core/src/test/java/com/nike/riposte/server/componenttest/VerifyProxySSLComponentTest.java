package com.nike.riposte.server.componenttest;

import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import org.jetbrains.annotations.NotNull;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static io.restassured.RestAssured.given;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

public class VerifyProxySSLComponentTest {

    public static final String RESPONSE_STRING = "Response encoded with SSL";
    private static Server proxyServer;
    private static ServerConfig proxyServerConfig;
    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        downstreamServerConfig = new SSLTestConfig();
        downstreamServer = new Server(downstreamServerConfig);
        downstreamServer.startup();

        proxyServerConfig = new ProxyTestingTestConfig();
        proxyServer = new Server(proxyServerConfig);
        proxyServer.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        downstreamServer.shutdown();
    }

    @Test
    public void verify_get() {
        String responseString = given()
                .config(RestAssuredConfig.newConfig().sslConfig(new SSLConfig().relaxedHTTPSValidation()))
                .baseUri("http://127.0.0.1")
                .port(proxyServerConfig.endpointsPort())
                .basePath(RouterEndpoint.MATCHING_PATH)
                .log().all()
                .when()
                .get()
                .then()
                .log().all()
                .statusCode(200)
                .extract().asString();

        assertThat(responseString).isEqualTo(RESPONSE_STRING);
    }


    public static class SSLTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints = singleton(new SSLTestEndpoint());

        public SSLTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
        }

        @Override
        public @NotNull Collection<@NotNull Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public boolean isEndpointsUseSsl() {
            return true;
        }

        @Override
        public int endpointsSslPort() {
            return port;
        }
    }

    public static class SSLTestEndpoint extends StandardEndpoint<String, String> {

        public static String MATCHING_PATH = "/ssl";

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
                @NotNull RequestInfo<String> request,
                @NotNull Executor longRunningTaskExecutor,
                @NotNull ChannelHandlerContext ctx
        ) {
            return CompletableFuture.completedFuture(ResponseInfo.newBuilder(RESPONSE_STRING).build());
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }

    }

    public static class RouterEndpoint extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/proxyEndpoint";
        private final int downstreamPort;

        public RouterEndpoint(int downstreamPort) {
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
                            "127.0.0.1", downstreamPort, true,
                            generateSimplePassthroughRequest(request, SSLTestEndpoint.MATCHING_PATH, request.getMethod(), ctx)
                    ).withRelaxedHttpsValidation(true)
            );
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class ProxyTestingTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public ProxyTestingTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = singleton(new RouterEndpoint(downstreamServerConfig.endpointsSslPort()));
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

}
