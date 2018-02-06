package com.nike.riposte.server.componenttest;

import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.http.impl.SimpleProxyRouterEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.server.testutils.ComponentTestUtils.CompressionType;
import com.nike.riposte.util.Matcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.restassured.response.ExtractableResponse;

import static com.nike.riposte.server.componenttest.VerifyAutoPayloadDecompressionComponentTest.DeserializationEndpointWithDecompressionEnabled.SOME_OBJ_FIELD_VALUE_HEADER_KEY;
import static com.nike.riposte.server.testutils.ComponentTestUtils.base64Decode;
import static com.nike.riposte.server.testutils.ComponentTestUtils.base64Encode;
import static com.nike.riposte.server.testutils.ComponentTestUtils.deflatePayload;
import static com.nike.riposte.server.testutils.ComponentTestUtils.gzipPayload;
import static com.nike.riposte.server.testutils.ComponentTestUtils.inflatePayload;
import static com.nike.riposte.server.testutils.ComponentTestUtils.ungzipPayload;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaders.Values.CHUNKED;
import static io.netty.util.CharsetUtil.UTF_8;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component test that verifies {@link com.nike.riposte.server.handler.SmartHttpContentDecompressor} works as expected.
 */
@RunWith(DataProviderRunner.class)
public class VerifyAutoPayloadDecompressionComponentTest {

    private static final String BASE_URI = "http://127.0.0.1";
    private static Server server;
    private static ServerConfig serverConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        serverConfig = new PayloadDecompressionValidationConfig();
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
    }

    @DataProvider(value = {
        "GZIP",
        "DEFLATE"
    })
    @Test
    public void riposte_successfully_decompresses_payload_for_endpoints_that_indicate_they_want_it(
        CompressionType compressionType
    ) {
        String origPayload = UUID.randomUUID().toString();
        byte[] origPayloadBytes = origPayload.getBytes(UTF_8);
        byte[] compressedPayload = compressionType.compress(origPayload);

        ExtractableResponse response =
                given()
                        .baseUri(BASE_URI)
                        .port(serverConfig.endpointsPort())
                        .basePath(BasicEndpointWithDecompressionEnabled.MATCHING_PATH)
                        .log().all()
                    .when()
                        .header(CONTENT_ENCODING, compressionType.contentEncodingHeaderValue)
                        .body(compressedPayload)
                        .post()
                    .then()
                        .log().all()
                        .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(BasicEndpointWithDecompressionEnabled.RESPONSE_PAYLOAD);
        assertThat(
            base64Decode(response.header(RECEIVED_PAYLOAD_BYTES_AS_BASE64_RESPONSE_HEADER_KEY))
        ).isEqualTo(origPayloadBytes);
        verifyExpectedContentAndTransferHeaders(response, "null", "null", CHUNKED);
    }

    private static void verifyExpectedContentAndTransferHeaders(
        ExtractableResponse response, String expectedContentEncoding, String expectedContentLength,
        String expectedTransferEncoding
    ) {
        assertThat(response.header(RECEIVED_CONTENT_ENCODING_HEADER)).isEqualTo(expectedContentEncoding);
        assertThat(response.header(RECEIVED_CONTENT_LENGTH_HEADER)).isEqualTo(expectedContentLength);
        assertThat(response.header(RECEIVED_TRANSFER_ENCODING_HEADER)).isEqualTo(expectedTransferEncoding);
    }

    @DataProvider(value = {
        "GZIP",
        "DEFLATE"
    })
    @Test
    public void riposte_successfully_decompresses_payload_and_deserializes_for_endpoints_that_indicate_they_want_it(
        CompressionType compressionType
    ) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        SomeObj origPayload = new SomeObj(UUID.randomUUID().toString());
        String origPayloadAsJSON = objectMapper.writeValueAsString(origPayload);

        byte[] origPayloadBytes = origPayloadAsJSON.getBytes(UTF_8);
        byte[] compressedPayload = compressionType.compress(origPayloadAsJSON);

        ExtractableResponse response =
            given()
                .baseUri(BASE_URI)
                .port(serverConfig.endpointsPort())
                .basePath(DeserializationEndpointWithDecompressionEnabled.MATCHING_PATH)
                .log().all()
            .when()
                .header(CONTENT_ENCODING, compressionType.contentEncodingHeaderValue)
                .body(compressedPayload)
                .post()
            .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(DeserializationEndpointWithDecompressionEnabled.RESPONSE_PAYLOAD);
        assertThat(
            base64Decode(response.header(RECEIVED_PAYLOAD_BYTES_AS_BASE64_RESPONSE_HEADER_KEY))
        ).isEqualTo(origPayloadBytes);
        assertThat(response.header(SOME_OBJ_FIELD_VALUE_HEADER_KEY)).isEqualTo(origPayload.someField);
        verifyExpectedContentAndTransferHeaders(response, "null", "null", CHUNKED);
    }

    @DataProvider(value = {
        "GZIP",
        "DEFLATE"
    })
    @Test
    public void riposte_does_not_decompresses_payload_for_endpoints_that_indicate_they_do_not_want_it(
        CompressionType compressionType
    ) {
        String origPayload = UUID.randomUUID().toString();
        byte[] compressedPayload = compressionType.compress(origPayload);

        ExtractableResponse response =
            given()
                .baseUri(BASE_URI)
                .port(serverConfig.endpointsPort())
                .basePath(BasicEndpointWithDecompressionDisabled.MATCHING_PATH)
                .log().all()
            .when()
                .header(CONTENT_ENCODING, compressionType.contentEncodingHeaderValue)
                .body(compressedPayload)
                .post()
            .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(BasicEndpointWithDecompressionDisabled.RESPONSE_PAYLOAD);
        assertThat(
            base64Decode(response.header(RECEIVED_PAYLOAD_BYTES_AS_BASE64_RESPONSE_HEADER_KEY))
        ).isEqualTo(compressedPayload);
        verifyExpectedContentAndTransferHeaders(
            response, compressionType.contentEncodingHeaderValue, String.valueOf(compressedPayload.length), "null"
        );
    }

    @DataProvider(value = {
        "GZIP",
        "DEFLATE"
    })
    @Test
    public void riposte_does_not_decompresses_payload_for_ProxyRouterEndpoint_by_default(
        CompressionType compressionType
    ) {
        String origPayload = UUID.randomUUID().toString();
        byte[] compressedPayload = compressionType.compress(origPayload);

        ExtractableResponse response =
            given()
                .baseUri(BASE_URI)
                .port(serverConfig.endpointsPort())
                .basePath(BasicRouterEndpointToDecompressionDisabledBackend.MATCHING_PATH)
                .log().all()
            .when()
                .header(CONTENT_ENCODING, compressionType.contentEncodingHeaderValue)
                .body(compressedPayload)
                .post()
            .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(BasicEndpointWithDecompressionDisabled.RESPONSE_PAYLOAD);
        assertThat(
            base64Decode(response.header(RECEIVED_PAYLOAD_BYTES_AS_BASE64_RESPONSE_HEADER_KEY))
        ).isEqualTo(compressedPayload);
        verifyExpectedContentAndTransferHeaders(
            response, compressionType.contentEncodingHeaderValue, String.valueOf(compressedPayload.length), "null"
        );
    }

    @DataProvider(value = {
        "GZIP",
        "DEFLATE"
    })
    @Test
    public void riposte_will_decompresses_payload_for_ProxyRouterEndpoint_if_the_router_endpoint_wants_it_to(
        CompressionType compressionType
    ) {
        String origPayload = UUID.randomUUID().toString();
        byte[] origPayloadBytes = origPayload.getBytes(UTF_8);
        byte[] compressedPayload = compressionType.compress(origPayload);

        ExtractableResponse response =
            given()
                .baseUri(BASE_URI)
                .port(serverConfig.endpointsPort())
                .basePath(DecompressingRouterEndpointToDecompressionDisabledBackend.MATCHING_PATH)
                .log().all()
            .when()
                .header(CONTENT_ENCODING, compressionType.contentEncodingHeaderValue)
                .body(compressedPayload)
                .post()
            .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(BasicEndpointWithDecompressionDisabled.RESPONSE_PAYLOAD);
        assertThat(
            base64Decode(response.header(RECEIVED_PAYLOAD_BYTES_AS_BASE64_RESPONSE_HEADER_KEY))
        ).isEqualTo(origPayloadBytes);
        verifyExpectedContentAndTransferHeaders(response, "null", "null", CHUNKED);
    }

    @Test
    public void verify_compression_helper_methods_work_as_expected() {
        // given
        String orig = UUID.randomUUID().toString();

        // when
        byte[] gzipped = gzipPayload(orig);
        String ungzipped = ungzipPayload(gzipped);

        byte[] deflated = deflatePayload(orig);
        String inflated = inflatePayload(deflated);

        // then
        assertThat(gzipped).isNotEqualTo(orig.getBytes(UTF_8));
        assertThat(deflated).isNotEqualTo(orig.getBytes(UTF_8));

        assertThat(ungzipped).isEqualTo(orig);
        assertThat(inflated).isEqualTo(orig);

        assertThat(gzipped).isNotEqualTo(deflated);
    }

    private static final String RECEIVED_PAYLOAD_BYTES_AS_BASE64_RESPONSE_HEADER_KEY = "received-payload-bytes-base-64";
    private static final String RECEIVED_CONTENT_ENCODING_HEADER = "received-content-encoding";
    private static final String RECEIVED_CONTENT_LENGTH_HEADER = "received-content-length";
    private static final String RECEIVED_TRANSFER_ENCODING_HEADER = "received-transfer-encoding";

    private static class BasicEndpointWithDecompressionEnabled extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/basicEndpointWithDecompressionEnabled";
        public static final String RESPONSE_PAYLOAD = "basic-endpoint-decompression-enabled-" + UUID.randomUUID().toString();

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                    ResponseInfo.newBuilder(RESPONSE_PAYLOAD)
                                .withHeaders(generateDefaultResponseHeaders(request))
                                .build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }

    }

    private static HttpHeaders generateDefaultResponseHeaders(RequestInfo<?> request) {
        String base64EncodedPayload = base64Encode(request.getRawContentBytes());

        return new DefaultHttpHeaders()
            .set(RECEIVED_PAYLOAD_BYTES_AS_BASE64_RESPONSE_HEADER_KEY, base64EncodedPayload)
            .set(RECEIVED_CONTENT_ENCODING_HEADER, String.valueOf(request.getHeaders().get(CONTENT_ENCODING)))
            .set(RECEIVED_CONTENT_LENGTH_HEADER, String.valueOf(request.getHeaders().get(CONTENT_LENGTH)))
            .set(RECEIVED_TRANSFER_ENCODING_HEADER, String.valueOf(request.getHeaders().get(TRANSFER_ENCODING)));
        
    }

    private static class BasicEndpointWithDecompressionDisabled extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/basicEndpointWithDecompressionDisabled";
        public static final String RESPONSE_PAYLOAD = "basic-endpoint-decompression-disabled-" + UUID.randomUUID().toString();

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(RESPONSE_PAYLOAD)
                            .withHeaders(generateDefaultResponseHeaders(request))
                            .build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }

        @Override
        public boolean isDecompressRequestPayloadAllowed(RequestInfo<?> request) {
            return false;
        }
    }

    protected static class DeserializationEndpointWithDecompressionEnabled extends StandardEndpoint<SomeObj, String> {

        public static final String MATCHING_PATH = "/deserializationEndpointWithDecompressionEnabled";
        public static final String RESPONSE_PAYLOAD = "deserialization-endpoint-decompression-enabled-" + UUID.randomUUID().toString();
        public static final String SOME_OBJ_FIELD_VALUE_HEADER_KEY = "some-obj-field-value";

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(
            RequestInfo<SomeObj> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx
        ) {
            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(RESPONSE_PAYLOAD)
                            .withHeaders(
                                generateDefaultResponseHeaders(request)
                                    .set(SOME_OBJ_FIELD_VALUE_HEADER_KEY, request.getContent().someField)
                            )
                            .build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }

    }

    private static class BasicRouterEndpointToDecompressionDisabledBackend extends SimpleProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/basicRouterEndpointToDecompressionDisabledBackend";

        public BasicRouterEndpointToDecompressionDisabledBackend(int port) {
            super(
                Matcher.match(MATCHING_PATH, HttpMethod.POST),
                "localhost",
                port,
                BasicEndpointWithDecompressionDisabled.MATCHING_PATH,
                false
            );
        }

    }

    private static class DecompressingRouterEndpointToDecompressionDisabledBackend extends SimpleProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/decompressingRouterEndpointToDecompressionDisabledBackend";

        public DecompressingRouterEndpointToDecompressionDisabledBackend(int port) {
            super(
                Matcher.match(MATCHING_PATH, HttpMethod.POST),
                "localhost",
                port,
                BasicEndpointWithDecompressionDisabled.MATCHING_PATH,
                false
            );
        }

        @Override
        public boolean isDecompressRequestPayloadAllowed(RequestInfo request) {
            return true;
        }
    }

    private static class SomeObj {
        public final String someField;

        public SomeObj() { this(null); }
        public SomeObj(String someField) {
            this.someField = someField;
        }
    }

    public static class PayloadDecompressionValidationConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;


        public PayloadDecompressionValidationConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
                endpoints = Arrays.asList(
                    new BasicEndpointWithDecompressionEnabled(),
                    new BasicEndpointWithDecompressionDisabled(),
                    new DeserializationEndpointWithDecompressionEnabled(),
                    new BasicRouterEndpointToDecompressionDisabledBackend(port),
                    new DecompressingRouterEndpointToDecompressionDisabledBackend(port));
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
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
