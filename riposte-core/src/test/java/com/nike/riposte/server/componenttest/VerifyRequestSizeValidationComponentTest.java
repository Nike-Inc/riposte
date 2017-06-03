package com.nike.riposte.server.componenttest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.restassured.response.ExtractableResponse;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.nike.riposte.server.componenttest.VerifyRequestSizeValidationComponentTest.RequestSizeValidationConfig.GLOBAL_MAX_REQUEST_SIZE;
import static com.nike.riposte.server.testutils.ComponentTestUtils.executeRequest;
import static io.netty.handler.codec.http.HttpHeaders.Values.CHUNKED;
import static io.netty.util.CharsetUtil.UTF_8;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class VerifyRequestSizeValidationComponentTest {

    private static final String BASE_URI = "http://127.0.0.1";
    private static Server server;
    private static ServerConfig serverConfig;
    private static ObjectMapper objectMapper;
    private int incompleteCallTimeoutMillis = 2000;

    @BeforeClass
    public static void setUpClass() throws Exception {
        objectMapper = new ObjectMapper();
        serverConfig = new RequestSizeValidationConfig();
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void should_return_bad_request_when_ContentLength_header_exceeds_global_configured_max_request_size() throws IOException {
        ExtractableResponse response =
                given()
                        .baseUri(BASE_URI)
                        .port(serverConfig.endpointsPort())
                        .basePath(BasicEndpoint.MATCHING_PATH)
                        .log().all()
                        .body(generatePayloadOfSizeInBytes(GLOBAL_MAX_REQUEST_SIZE + 1))
                        .when()
                        .post()
                        .then()
                        .log().headers()
                        .extract();

        assertThat(response.statusCode()).isEqualTo(HttpResponseStatus.BAD_REQUEST.code());
        assertBadRequestErrorMessageAndMetadata(response.asString());
    }

    @Test
    public void should_return_expected_response_when_ContentLength_header_not_exceeding_global_request_size() {
        ExtractableResponse response =
                given()
                        .baseUri(BASE_URI)
                        .port(serverConfig.endpointsPort())
                        .basePath(BasicEndpoint.MATCHING_PATH)
                        .log().all()
                        .body(generatePayloadOfSizeInBytes(GLOBAL_MAX_REQUEST_SIZE))
                        .when()
                        .post()
                        .then()
                        .log().headers()
                        .extract();

        assertThat(response.statusCode()).isEqualTo(HttpResponseStatus.OK.code());
        assertThat(response.asString()).isEqualTo(BasicEndpoint.RESPONSE_PAYLOAD);
    }

    @Test
    public void should_return_bad_request_when_ContentLength_header_exceeds_endpoint_overridden_configured_max_request_size() throws IOException {
        ExtractableResponse response =
                given()
                        .baseUri(BASE_URI)
                        .port(serverConfig.endpointsPort())
                        .basePath(BasicEndpointWithRequestSizeValidationOverride.MATCHING_PATH)
                        .log().all()
                        .body(generatePayloadOfSizeInBytes(BasicEndpointWithRequestSizeValidationOverride.MAX_REQUEST_SIZE + 1))
                        .when()
                        .post()
                        .then()
                        .log().headers()
                        .extract();

        assertThat(response.statusCode()).isEqualTo(HttpResponseStatus.BAD_REQUEST.code());
        assertBadRequestErrorMessageAndMetadata(response.asString());
    }

    @Test
    public void should_return_expected_response_when_ContentLength_header_not_exceeding_endpoint_overridden_request_size() {
        ExtractableResponse response =
                given()
                        .baseUri(BASE_URI)
                        .port(serverConfig.endpointsPort())
                        .basePath(BasicEndpointWithRequestSizeValidationOverride.MATCHING_PATH)
                        .log().all()
                        .body(generatePayloadOfSizeInBytes(BasicEndpointWithRequestSizeValidationOverride.MAX_REQUEST_SIZE))
                        .when()
                        .post()
                        .then()
                        .log().headers()
                        .extract();

        assertThat(response.statusCode()).isEqualTo(HttpResponseStatus.OK.code());
        assertThat(response.asString()).isEqualTo(BasicEndpointWithRequestSizeValidationOverride.RESPONSE_PAYLOAD);
    }

    @Test
    public void should_return_expected_response_when_endpoint_disabled_ContentLength_header_above_global_size_validation() {
        ExtractableResponse response =
                given()
                        .baseUri(BASE_URI)
                        .port(serverConfig.endpointsPort())
                        .basePath(BasicEndpointWithRequestSizeValidationDisabled.MATCHING_PATH)
                        .log().all()
                        .body(generatePayloadOfSizeInBytes(GLOBAL_MAX_REQUEST_SIZE + 100))
                        .when()
                        .post()
                        .then()
                        .log().headers()
                        .extract();

        assertThat(response.statusCode()).isEqualTo(HttpResponseStatus.OK.code());
        assertThat(response.asString()).isEqualTo(BasicEndpointWithRequestSizeValidationDisabled.RESPONSE_PAYLOAD);
    }

    @Test
    public void should_return_bad_request_when_chunked_request_exceeds_global_configured_max_request_size() throws Exception {
        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, BasicEndpoint.MATCHING_PATH, Unpooled.wrappedBuffer(generatePayloadOfSizeInBytes(GLOBAL_MAX_REQUEST_SIZE + 1).getBytes(UTF_8))
        );

        request.headers().set(HttpHeaders.Names.HOST, "127.0.0.1");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        request.headers().set(HttpHeaders.Names.TRANSFER_ENCODING, CHUNKED);

        // when
        Pair<Integer, String> serverResponse = executeRequest(request, serverConfig.endpointsPort(), incompleteCallTimeoutMillis);

        // then
        assertThat(serverResponse.getLeft()).isEqualTo(HttpResponseStatus.BAD_REQUEST.code());
        assertBadRequestErrorMessageAndMetadata(serverResponse.getRight());
    }

    @Test
    public void should_return_expected_response_when_chunked_request_not_exceeding_global_request_size() throws Exception {
        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, BasicEndpoint.MATCHING_PATH, Unpooled.wrappedBuffer(generatePayloadOfSizeInBytes(GLOBAL_MAX_REQUEST_SIZE).getBytes(UTF_8))
        );

        request.headers().set(HttpHeaders.Names.HOST, "127.0.0.1");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        request.headers().set(HttpHeaders.Names.TRANSFER_ENCODING, CHUNKED);

        // when
        Pair<Integer, String> serverResponse = executeRequest(request, serverConfig.endpointsPort(), incompleteCallTimeoutMillis);

        // then
        assertThat(serverResponse.getLeft()).isEqualTo(HttpResponseStatus.OK.code());
        assertThat(serverResponse.getRight()).isEqualTo(BasicEndpoint.RESPONSE_PAYLOAD);
    }

    @Test
    public void should_return_bad_request_when_chunked_request_exceeds_endpoint_overridden_configured_max_request_size() throws Exception {
        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, BasicEndpointWithRequestSizeValidationOverride.MATCHING_PATH, Unpooled.wrappedBuffer(generatePayloadOfSizeInBytes(BasicEndpointWithRequestSizeValidationOverride.MAX_REQUEST_SIZE + 1).getBytes(UTF_8))
        );

        request.headers().set(HttpHeaders.Names.HOST, "127.0.0.1");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        request.headers().set(HttpHeaders.Names.TRANSFER_ENCODING, CHUNKED);

        // when
        Pair<Integer, String> serverResponse = executeRequest(request, serverConfig.endpointsPort(), incompleteCallTimeoutMillis);

        // then
        assertThat(serverResponse.getLeft()).isEqualTo(HttpResponseStatus.BAD_REQUEST.code());
        assertBadRequestErrorMessageAndMetadata(serverResponse.getRight());
    }

    @Test
    public void should_return_expected_response_when_chunked_request_not_exceeding_endpoint_overridden_request_size() throws Exception {
        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, BasicEndpointWithRequestSizeValidationOverride.MATCHING_PATH, Unpooled.wrappedBuffer(generatePayloadOfSizeInBytes(BasicEndpointWithRequestSizeValidationOverride.MAX_REQUEST_SIZE).getBytes(UTF_8))
        );

        request.headers().set(HttpHeaders.Names.HOST, "127.0.0.1");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        request.headers().set(HttpHeaders.Names.TRANSFER_ENCODING, CHUNKED);

        // when
        Pair<Integer, String> serverResponse = executeRequest(request, serverConfig.endpointsPort(), incompleteCallTimeoutMillis);

        // then
        assertThat(serverResponse.getLeft()).isEqualTo(HttpResponseStatus.OK.code());
        assertThat(serverResponse.getRight()).isEqualTo(BasicEndpointWithRequestSizeValidationOverride.RESPONSE_PAYLOAD);
    }

    @Test
    public void should_return_expected_response_when_endpoint_disabled_chunked_request_size_validation() throws Exception {
        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, BasicEndpointWithRequestSizeValidationDisabled.MATCHING_PATH, Unpooled.wrappedBuffer(generatePayloadOfSizeInBytes(GLOBAL_MAX_REQUEST_SIZE + 100).getBytes(UTF_8))
        );

        request.headers().set(HttpHeaders.Names.HOST, "127.0.0.1");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        request.headers().set(HttpHeaders.Names.TRANSFER_ENCODING, CHUNKED);

        // when
        Pair<Integer, String> serverResponse = executeRequest(request, serverConfig.endpointsPort(), incompleteCallTimeoutMillis);

        // then
        assertThat(serverResponse.getLeft()).isEqualTo(HttpResponseStatus.OK.code());
        assertThat(serverResponse.getRight()).isEqualTo(BasicEndpointWithRequestSizeValidationDisabled.RESPONSE_PAYLOAD);
    }

    private void assertBadRequestErrorMessageAndMetadata(String response) throws IOException {
        JsonNode error = objectMapper.readValue(response, JsonNode.class).get("errors").get(0);
        assertThat(error.get("message").textValue()).isEqualTo("Malformed request");
        assertThat(error.get("metadata").get("cause").textValue())
                .isEqualTo("The request exceeded the maximum payload size allowed");
    }

    private static String generatePayloadOfSizeInBytes(int length) {
        StringBuilder sb = new StringBuilder(length);
        for(int i = 0; i < length; i++) {
            sb.append(i % 10);
        }
        return sb.toString();
    }

    private static class BasicEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/basicEndpoint";
        public static final String RESPONSE_PAYLOAD = "basic-endpoint-" + UUID.randomUUID().toString();

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                    ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }
    }

    private static class BasicEndpointWithRequestSizeValidationOverride extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/basicEndpointWithOverride";
        public static final String RESPONSE_PAYLOAD = "basic-endpoint-" + UUID.randomUUID().toString();
        public static Integer MAX_REQUEST_SIZE = 10;

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                    ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }

        @Override
        public Integer maxRequestSizeInBytesOverride() {
            return MAX_REQUEST_SIZE;
        }
    }

    private static class BasicEndpointWithRequestSizeValidationDisabled extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/basicEndpointWithRequestSizeValidationDisabled";
        public static final String RESPONSE_PAYLOAD = "basic-endpoint-" + UUID.randomUUID().toString();
        public static Integer MAX_REQUEST_SIZE = 0;

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                    ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }

        @Override
        public Integer maxRequestSizeInBytesOverride() {
            return MAX_REQUEST_SIZE;
        }
    }

    public static class RequestSizeValidationConfig implements ServerConfig {
        private final Collection<Endpoint<?>> endpoints = Arrays.asList(new BasicEndpoint(),
                new BasicEndpointWithRequestSizeValidationOverride(),
                new BasicEndpointWithRequestSizeValidationDisabled());

        private final int port;
        public static int GLOBAL_MAX_REQUEST_SIZE = 5;

        public RequestSizeValidationConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
        }

        @Override
        public int maxRequestSizeInBytes() {
            return GLOBAL_MAX_REQUEST_SIZE;
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }
    }

}
