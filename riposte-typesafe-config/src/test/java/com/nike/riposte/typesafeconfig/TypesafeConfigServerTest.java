package com.nike.riposte.typesafeconfig;

import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.util.MainClassUtils;
import com.nike.riposte.util.Matcher;

import com.jayway.restassured.response.ExtractableResponse;
import com.jayway.restassured.response.Response;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

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

import static com.jayway.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the functionality of {@link TypesafeConfigServer}.
 *
 * @author Nic Munroe
 */
public class TypesafeConfigServerTest {
    public static int findFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private TypesafeConfigServer generateTypesafeConfigServer(int port) {
        return new TypesafeConfigServer() {
            @Override
            protected ServerConfig getServerConfig(Config appConfig) {
                return new ServerConfig() {
                    @Override
                    public Collection<Endpoint<?>> appEndpoints() {
                        return Collections.singleton(new SomeEndpoint(appConfig));
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
        ConfigFactory.invalidateCaches();
    }

    @After
    public void afterMethod() {
        clearSystemProps();
        ConfigFactory.invalidateCaches();
    }

    @Test
    public void verify_essential_behavior() throws Exception {
        // given
        setAppAndEnvironment("typesafeconfigserver", "compiletimetest");
        int port = findFreePort();
        TypesafeConfigServer server = generateTypesafeConfigServer(port);
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

    private static class SomeEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/typesafeConfigValue";
        private final Config appConfig;

        private SomeEndpoint(Config appConfig) {
            this.appConfig = appConfig;
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request,
                                                               Executor longRunningTaskExecutor,
                                                               ChannelHandlerContext ctx) {
            String value = appConfig.getString("typesafeConfigServer.foo");
            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(value).build()
            );
        }
    }
}