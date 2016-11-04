package com.nike.riposte.server.componenttest;

import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;

import com.jayway.restassured.config.RestAssuredConfig;
import com.jayway.restassured.config.SSLConfig;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

import static com.jayway.restassured.RestAssured.given;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by wvoorh on 8/10/15.
 */
public class VerifySSLComponentTest {

    public static final String RESPONSE_STRING = "Response encoded with SSL";
    private static Server server;
    private static ServerConfig serverConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        serverConfig = new SSLTestConfig();
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void verify_get(){
        String responseString= given()
                .config(RestAssuredConfig.newConfig().sslConfig(new SSLConfig().relaxedHTTPSValidation()))
                .baseUri("https://127.0.0.1")
                .port(serverConfig.endpointsSslPort())
                .basePath(SSLTestEndpoint.MATCHING_PATH)
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
        public Collection<Endpoint<?>> appEndpoints() {
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
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<String> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(ResponseInfo.newBuilder(RESPONSE_STRING).build());
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }

    }
}
