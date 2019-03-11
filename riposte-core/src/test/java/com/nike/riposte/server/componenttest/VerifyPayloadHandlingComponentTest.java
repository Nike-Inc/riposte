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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;
import io.restassured.response.ExtractableResponse;

import static io.restassured.RestAssured.given;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that request payloads are automatically deserialized correctly, and that response payloads are serialized
 * (if appropriate) and sent down the wire correctly.
 *
 * @author Nic Munroe
 */
public class VerifyPayloadHandlingComponentTest {

    private static final String RESPONSE_PAYLOAD_HASH_HEADER_KEY = "response-hash";
    private static final String REQUEST_PAYLOAD_HASH_HEADER_KEY = "request-hash";

    private static final HashFunction hashFunction = Hashing.md5();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static Server server;
    private static ServerConfig serverConfig;

    private static Server downstreamNonSslServer;
    private static ServerConfig downstreamNonSslServerConfig;

    private static Server downstreamSslServer;
    private static ServerConfig downstreamSslServerConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        downstreamNonSslServerConfig = new DownstreamServerTestConfig(false);
        downstreamNonSslServer = new Server(downstreamNonSslServerConfig);
        downstreamNonSslServer.startup();

        downstreamSslServerConfig = new DownstreamServerTestConfig(true);
        downstreamSslServer = new Server(downstreamSslServerConfig);
        downstreamSslServer.startup();

        serverConfig = new PayloadTypeHandlingTestConfig(downstreamNonSslServerConfig.endpointsPort(), downstreamSslServerConfig.endpointsSslPort());
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
        downstreamSslServer.shutdown();
        downstreamNonSslServer.shutdown();
    }

    @Test
    public void verify_byte_array_response_payload_is_sent_as_is_with_no_modifications() throws IOException, InterruptedException {

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(ByteArrayPayloadReturner.MATCHING_PATH)
                .log().all()
            .when()
                .post()
            .then()
                .log().headers()
                .statusCode(200)
                .extract();

        byte[] responsePayload = response.asByteArray();
        String expectedHash = response.header(RESPONSE_PAYLOAD_HASH_HEADER_KEY);
        String actualHash = getHashForPayload(responsePayload);
        assertThat(actualHash).isEqualTo(expectedHash);
    }

    @Test
    public void verify_CharSequence_response_payload_is_sent_as_is_with_no_modifications() throws IOException, InterruptedException {

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(CharSequencePayloadReturner.MATCHING_PATH)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract();

        String responsePayload = response.asString();
        String expectedHash = response.header(RESPONSE_PAYLOAD_HASH_HEADER_KEY);
        String actualHash = getHashForPayload(responsePayload.getBytes(CharsetUtil.UTF_8));
        assertThat(actualHash).isEqualTo(expectedHash);
    }

    @Test
    public void verify_SerializedObject_response_payload_is_sent_as_serialized_json() throws IOException, InterruptedException {
        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(SerializableObjectPayloadReturner.MATCHING_PATH)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract();

        String responsePayload = response.asString();
        String expectedHash = response.header(RESPONSE_PAYLOAD_HASH_HEADER_KEY);
        String actualHash = getHashForPayload(responsePayload.getBytes(CharsetUtil.UTF_8));
        assertThat(actualHash).isEqualTo(expectedHash);
    }

    @Test
    public void verify_request_payload_raw_bytes_received_for_void_input_type() {
        byte[] bytePayload = generateRandomBytes(64);
        String payloadHash = getHashForPayload(bytePayload);

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(VoidTypeDeserializer.MATCHING_PATH)
                .header(REQUEST_PAYLOAD_HASH_HEADER_KEY, payloadHash)
                .body(bytePayload)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract();

        String responsePayload = response.asString();
        assertThat(responsePayload).isEqualTo("success_void");
    }

    @Test
    public void verify_request_payload_received_for_string_input_type() {
        String requestPayload = UUID.randomUUID().toString();
        String payloadHash = getHashForPayload(requestPayload.getBytes(CharsetUtil.UTF_8));

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(StringTypeDeserializer.MATCHING_PATH)
                .header(REQUEST_PAYLOAD_HASH_HEADER_KEY, payloadHash)
                .body(requestPayload)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract();

        String responsePayload = response.asString();
        assertThat(responsePayload).isEqualTo("success_string");
    }

    @Test
    public void verify_request_payload_received_for_byte_array_input_type() {
        String requestPayload = UUID.randomUUID().toString();
        byte[] payloadBytes = requestPayload.getBytes(CharsetUtil.UTF_8);
        String payloadHash = getHashForPayload(payloadBytes);

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(ByteArrayTypeDeserializer.MATCHING_PATH)
                .header(REQUEST_PAYLOAD_HASH_HEADER_KEY, payloadHash)
                .body(requestPayload)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract();

        String responsePayload = response.asString();
        assertThat(responsePayload).isEqualTo("success_string");
    }

    @Test
    public void verify_request_payload_received_for_widget_input_type() throws JsonProcessingException {
        SerializableObject widget = new SerializableObject(UUID.randomUUID().toString(), generateRandomBytes(32));
        String requestPayload = objectMapper.writeValueAsString(widget);
        String payloadHash = getHashForPayload(requestPayload.getBytes(CharsetUtil.UTF_8));

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(WidgetTypeDeserializer.MATCHING_PATH)
                .header(REQUEST_PAYLOAD_HASH_HEADER_KEY, payloadHash)
                .body(requestPayload)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract();

        String responsePayload = response.asString();
        assertThat(responsePayload).isEqualTo("success_widget");
    }

    @Test
    public void verify_proxy_router_call_works_for_non_ssl_downstream_system() throws JsonProcessingException {
        SerializableObject widget = new SerializableObject(UUID.randomUUID().toString(), generateRandomBytes(32));
        String requestPayload = objectMapper.writeValueAsString(widget);
        String payloadHash = getHashForPayload(requestPayload.getBytes(CharsetUtil.UTF_8));

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(DownstreamProxyNonSsl.MATCHING_PATH)
                .header(REQUEST_PAYLOAD_HASH_HEADER_KEY, payloadHash)
                .body(requestPayload)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract();

        String responsePayload = response.asString();
        assertThat(responsePayload).isEqualTo("success_proxy_downstream_endpoint_call_ssl_false");
    }

    @Test
    public void verify_proxy_router_call_works_for_ssl_downstream_system() throws JsonProcessingException {
        SerializableObject widget = new SerializableObject(UUID.randomUUID().toString(), generateRandomBytes(32));
        String requestPayload = objectMapper.writeValueAsString(widget);
        String payloadHash = getHashForPayload(requestPayload.getBytes(CharsetUtil.UTF_8));

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(DownstreamProxySsl.MATCHING_PATH)
                .header(REQUEST_PAYLOAD_HASH_HEADER_KEY, payloadHash)
                .body(requestPayload)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract();

        String responsePayload = response.asString();
        assertThat(responsePayload).isEqualTo("success_proxy_downstream_endpoint_call_ssl_true");
    }

    public static class PayloadTypeHandlingTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public PayloadTypeHandlingTestConfig(int downstreamPortNonSsl, int downstreamPortSsl) {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = Arrays.asList(
                new ByteArrayPayloadReturner(),
                new CharSequencePayloadReturner(),
                new SerializableObjectPayloadReturner(),
                new VoidTypeDeserializer(),
                new StringTypeDeserializer(),
                new ByteArrayTypeDeserializer(),
                new WidgetTypeDeserializer(),
                new DownstreamProxyNonSsl(downstreamPortNonSsl),
                new DownstreamProxySsl(downstreamPortSsl)
            );
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

    public static class DownstreamServerTestConfig implements ServerConfig {
        private final int port;
        private final boolean isSsl;
        private final Collection<Endpoint<?>> endpoints;

        public DownstreamServerTestConfig(boolean isSsl) {
            this.isSsl = isSsl;
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = singleton(new DownstreamEndpoint(isSsl));
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
        public int endpointsSslPort() {
            return port;
        }

        @Override
        public boolean isEndpointsUseSsl() {
            return isSsl;
        }


    }

    /**
     * @return The MD5 hash of the given payload. This can be used to verify correctness when sending payloads across the wire.
     */
    private static String getHashForPayload(byte[] payloadBytes) {
        Hasher hasher = hashFunction.newHasher();

        hasher = hasher.putBytes(payloadBytes);

        HashCode hc = hasher.hash();
        return hc.toString();
    }

    private static final Random random = new Random(System.nanoTime());
    private static byte[] generateRandomBytes(int length) {
        byte[] randomBytes = new byte[length];
        random.nextBytes(randomBytes);
        return randomBytes;
    }

    public static class ByteArrayPayloadReturner extends StandardEndpoint<String, byte[]> {

        public static final String MATCHING_PATH = "/bytes";

        @Override
        public @NotNull CompletableFuture<ResponseInfo<byte[]>> execute(
            @NotNull RequestInfo<String> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            byte[] responsePayload = generateRandomBytes(15000);
            String responsePayloadHash = getHashForPayload(responsePayload);
            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(responsePayload)
                            .withHeaders(new DefaultHttpHeaders().add(RESPONSE_PAYLOAD_HASH_HEADER_KEY, responsePayloadHash))
                            .build()
            );
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }
    }

    public static class CharSequencePayloadReturner extends StandardEndpoint<String, CharSequence> {

        public static final String MATCHING_PATH = "/charSequence";

        @Override
        public @NotNull CompletableFuture<ResponseInfo<CharSequence>> execute(
            @NotNull RequestInfo<String> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            CharSequence responsePayload = new StringBuilder(UUID.randomUUID().toString());
            String responsePayloadHash = getHashForPayload(responsePayload.toString().getBytes(CharsetUtil.UTF_8));
            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(responsePayload)
                            .withHeaders(new DefaultHttpHeaders().add(RESPONSE_PAYLOAD_HASH_HEADER_KEY, responsePayloadHash))
                            .build()
            );
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }
    }

    public static class SerializableObject implements Serializable {
        public final String fooThing;
        public final byte[] barThing;

        protected SerializableObject() {
            this(null, null);
        }

        public SerializableObject(String fooThing, byte[] barThing) {
            this.fooThing = fooThing;
            this.barThing = barThing;
        }
    }

    public static class SerializableObjectPayloadReturner extends StandardEndpoint<String, SerializableObject> {

        public static final String MATCHING_PATH = "/serializableObject";
        public static final ObjectMapper jsonSerializer = new ObjectMapper();

        @Override
        public @NotNull CompletableFuture<ResponseInfo<SerializableObject>> execute(
            @NotNull RequestInfo<String> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            SerializableObject responsePayload = new SerializableObject(UUID.randomUUID().toString(), generateRandomBytes(32));
            try {
                String responsePayloadAsJson = jsonSerializer.writeValueAsString(responsePayload);
                String responsePayloadHash = getHashForPayload(responsePayloadAsJson.getBytes(CharsetUtil.UTF_8));
                return CompletableFuture.completedFuture(
                    ResponseInfo.newBuilder(responsePayload)
                                .withHeaders(new DefaultHttpHeaders().add(RESPONSE_PAYLOAD_HASH_HEADER_KEY, responsePayloadHash))
                                .build()
                );
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }

    }

    public static void verifyIncomingPayloadByteHash(RequestInfo<?> request, boolean verifyRawStringMatchesByteHashAlso) {
        String expectedPayloadHash = request.getHeaders().get(REQUEST_PAYLOAD_HASH_HEADER_KEY);
        if (expectedPayloadHash == null)
            throw new IllegalArgumentException("Expected to receive " + REQUEST_PAYLOAD_HASH_HEADER_KEY + " header, but none was found.");

        String actualBytesHash = getHashForPayload(request.getRawContentBytes());
        if (!actualBytesHash.equals(expectedPayloadHash)) {
            throw new IllegalArgumentException(
                String.format("Actual byte[] payload hash (%s) did not match expected payload hash (%s)", actualBytesHash, expectedPayloadHash));
        }

        if (verifyRawStringMatchesByteHashAlso) {
            String actualRawStringHash = getHashForPayload(request.getRawContent().getBytes(CharsetUtil.UTF_8));
            if (!actualRawStringHash.equals(expectedPayloadHash)) {
                throw new IllegalArgumentException(
                    String.format("Actual raw string payload hash (%s) did not match expected payload hash (%s)", actualRawStringHash, expectedPayloadHash));
            }
        }
    }

    public static class VoidTypeDeserializer extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/voidDeserializer";

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<Void> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            if (request.getContent() != null)
                throw new IllegalStateException("Since the deserialized type is Void, getContent() should return null. Instead it returned: " + request.getContent());

            verifyIncomingPayloadByteHash(request, false);

            return CompletableFuture.completedFuture(ResponseInfo.newBuilder("success_void").build());
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class StringTypeDeserializer extends StandardEndpoint<String, String> {

        public static final String MATCHING_PATH = "/stringDeserializer";

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<String> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            if (!request.getContent().equals(request.getRawContent())) {
                throw new IllegalStateException(
                    "Since the deserialized type is String, getContent() should return the same thing as getRawContent(). getContent(): " + request
                        .getContent() + " - getRawContent(): " + request.getRawContent());
            }

            verifyIncomingPayloadByteHash(request, true);

            return CompletableFuture.completedFuture(ResponseInfo.newBuilder("success_string").build());
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class ByteArrayTypeDeserializer extends StandardEndpoint<byte[], String> {

        public static final String MATCHING_PATH = "/byteArrayDeserializer";

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<byte[]> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            if (!request.getContent().equals(request.getRawContentBytes())) {
                throw new IllegalStateException(
                    "Since the deserialized type is byte[], getContent() should return the same thing as "
                    + "getRawContentBytes(). getContent(): " + request.getContent() + " - getRawContentBytes(): "
                    + request.getRawContentBytes());
            }

            verifyIncomingPayloadByteHash(request, true);

            return CompletableFuture.completedFuture(ResponseInfo.newBuilder("success_string").build());
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class WidgetTypeDeserializer extends StandardEndpoint<SerializableObject, String> {

        public static final String MATCHING_PATH = "/widgetDeserializer";

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<SerializableObject> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            if (request.getContent() == null) {
                throw new IllegalStateException(
                    "getContent() should return a non-null value for deserializable content. getRawContent(): " + request.getRawContent());
            }

            verifyIncomingPayloadByteHash(request, true);

            try {
                SerializableObject widget = request.getContent();
                String widgetAsString = objectMapper.writeValueAsString(widget);
                if (!widgetAsString.equals(request.getRawContent())) {
                    throw new IllegalArgumentException("Expected serialized widget to match getRawContent(), but it didn't. serialized widget string: " +
                                                       widgetAsString + ", getRawContent(): " + request.getRawContent());
                }
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            return CompletableFuture.completedFuture(ResponseInfo.newBuilder(responseMessage()).build());
        }

        protected String responseMessage() {
            return "success_widget";
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class DownstreamProxyNonSsl extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/proxyNonSsl";
        private final int downstreamPort;

        public DownstreamProxyNonSsl(int downstreamPort) {
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
                    generateSimplePassthroughRequest(request, DownstreamEndpoint.MATCHING_PATH, request.getMethod(), ctx)
                )
            );
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class DownstreamProxySsl extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/proxySsl";
        private final int downstreamPort;

        public DownstreamProxySsl(int downstreamPort) {
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
                    generateSimplePassthroughRequest(request, DownstreamEndpoint.MATCHING_PATH, request.getMethod(), ctx)
                ).withRelaxedHttpsValidation(true)
            );
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class DownstreamEndpoint extends WidgetTypeDeserializer {

        private final boolean isSslServer;

        public DownstreamEndpoint(boolean isSslServer) {
            this.isSslServer = isSslServer;
        }

        @Override
        public @Nullable TypeReference<SerializableObject> requestContentType() {
            return new TypeReference<SerializableObject>() {};
        }

        @Override
        protected String responseMessage() {
            return "success_proxy_downstream_endpoint_call_ssl_" + isSslServer;
        }
    }
}
