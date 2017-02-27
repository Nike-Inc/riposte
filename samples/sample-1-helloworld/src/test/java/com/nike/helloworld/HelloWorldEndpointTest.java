package com.nike.helloworld;

import com.nike.Main;
import com.nike.backstopper.apierror.sample.SampleCoreApiError;
import com.nike.riposte.server.Server;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static io.restassured.http.ContentType.TEXT;
import static org.hamcrest.Matchers.equalTo;

/**
 * Simple component tests for the hello world endpoint.
 */
public class HelloWorldEndpointTest {

    public static class AppServerConfigForTesting extends Main.AppServerConfig {
        private final int port;

        public AppServerConfigForTesting(int port) {
            this.port = port;
        }

        @Override
        public int endpointsPort() {
            return port;
        }
    }

    private static Server server;
    private static AppServerConfigForTesting serverConfig;

    private static int findFreePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    @BeforeClass
    public static void setup() throws Exception {
        serverConfig = new AppServerConfigForTesting(findFreePort());
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void teardown() throws Exception {
        server.shutdown();
    }

    /**
     * Sample integration test to validate the content type and body for a supported request.
     */
    @Test
    public void success() {
        given()
            .port(serverConfig.endpointsPort())
        .when()
            .get("/")
        .then()
            .assertThat()
            .contentType(TEXT)
            .body(equalTo("Hello, world!"));
    }

    /**
     * Sample integration test to validate the content type and body for an unsupported request This is unnecessary
     * really as this test is provided tbe core library, but is provided here as a reference for the developer.
     */
    @Test
    public void pathNotfound() {
        given()
            .port(serverConfig.endpointsPort())
        .when()
            .get("/fail")
        .then()
            .assertThat()
            .statusCode(404)
            .contentType(JSON)
            .body("errors[0].code", equalTo(Integer.parseInt(SampleCoreApiError.NOT_FOUND.getErrorCode())));
    }


}
