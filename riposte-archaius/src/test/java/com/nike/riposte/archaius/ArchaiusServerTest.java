package com.nike.riposte.archaius;

import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.util.MainClassUtils;
import com.nike.riposte.util.Matcher;

import com.netflix.config.ConfigurationManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ResourceLeakDetector;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tests the functionality of {@link ArchaiusServer}.
 *
 * @author Nic Munroe
 */
public class ArchaiusServerTest {

    private static int findFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ArchaiusServer generateArchaiusServer(int port) {
        return new ArchaiusServer() {
            @Override
            protected ServerConfig getServerConfig() {
                return new ServerConfig() {
                    @Override
                    public Collection<Endpoint<?>> appEndpoints() {
                        return Collections.singleton(new SomeEndpoint());
                    }

                    @Override
                    public int endpointsPort() {
                        return port;
                    }
                };
            }
        };
    }

    private void clearSystemProps() {
        System.clearProperty("@appId");
        System.clearProperty("@environment");
        System.clearProperty("org.jboss.logging.provider");
        System.clearProperty(MainClassUtils.DELAY_CRASH_ON_STARTUP_SYSTEM_PROP_KEY);
    }

    private void setAppAndEnvironment(String appId, String environment) {
        System.setProperty("@appId", appId);
        System.setProperty("@environment", environment);
    }

    @Before
    public void beforeMethod() {
        clearSystemProps();
        System.setProperty(MainClassUtils.DELAY_CRASH_ON_STARTUP_SYSTEM_PROP_KEY, "false");
        ConfigurationManager.getConfigInstance().clear();
    }

    @After
    public void afterMethod() {
        clearSystemProps();
    }

    @Test
    public void verify_essential_behavior() throws Exception {
        // given
        setAppAndEnvironment("archaiusserver", "compiletimetest");
        int port = findFreePort();
        ArchaiusServer server = generateArchaiusServer(port);
        assertThat(System.getProperty("org.jboss.logging.provider")).isNull();

        // when
        server.launchServer(null);
        ExtractableResponse<Response> response =
            given()
                .baseUri("http://localhost")
                .port(port)
                .when()
                .get(SomeEndpoint.MATCHING_PATH)
                .then()
                .extract();

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo("overridevalue");
        assertThat(System.getProperty("org.jboss.logging.provider")).isEqualTo("slf4j");
        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(ResourceLeakDetector.Level.PARANOID);
    }

    @Test
    public void verify_error_when_loading_archaius_props() throws Exception {
        // given
        setAppAndEnvironment("doesnotexist", "nope");
        int port = findFreePort();
        ArchaiusServer server = generateArchaiusServer(port);

        // when
        Throwable ex = catchThrowable(() -> server.launchServer(null));

        // then
        assertThat(ex)
            .isNotNull()
            .hasMessage("Error loading Archaius properties");
    }

    private static class SomeEndpoint extends StandardEndpoint<Void, String> {

        static final String MATCHING_PATH = "/archaiusValue";

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request,
                                                               Executor longRunningTaskExecutor,
                                                               ChannelHandlerContext ctx) {
            String value = ConfigurationManager.getConfigInstance().getString("archaiusServer.foo");
            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(value).build()
            );
        }
    }
}