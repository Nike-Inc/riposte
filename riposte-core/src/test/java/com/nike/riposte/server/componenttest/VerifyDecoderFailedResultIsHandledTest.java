package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.apierror.ApiErrorWithMetadata;
import com.nike.backstopper.apierror.sample.SampleCoreApiError;
import com.nike.internal.util.Pair;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.config.ServerConfig.HttpRequestDecoderConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.server.testutils.ComponentTestUtils.NettyHttpClientRequestBuilder;
import com.nike.riposte.server.testutils.ComponentTestUtils.NettyHttpClientResponse;
import com.nike.riposte.util.Matcher;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

import static com.nike.riposte.server.testutils.ComponentTestUtils.generatePayload;
import static com.nike.riposte.server.testutils.ComponentTestUtils.request;
import static com.nike.riposte.server.testutils.ComponentTestUtils.verifyErrorReceived;
import static com.nike.riposte.util.AsyncNettyHelper.supplierWithTracingAndMdc;
import static java.lang.Thread.sleep;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component test that verifies {@link ServerConfig#httpRequestDecoderConfig()} values are honored and that the
 * resulting decoder failure results are converted into the expected HTTP error responses.
 */
@RunWith(DataProviderRunner.class)
public class VerifyDecoderFailedResultIsHandledTest {

    private static Server basicServer;
    private static final ServerConfig basicServerConfig = new BasicServerTestConfig();
    private static Server downstreamServer;
    private static final ServerConfig downstreamServerConfig = new DownstreamServerTestConfig();
    private static Server proxyServer;
    private static final ServerConfig proxyServerConfig = new ProxyTestingTestConfig();
    private static final int incompleteCallTimeoutMillis = 5000;

    @BeforeClass
    public static void setUpClass() throws Exception {
        basicServer = new Server(basicServerConfig);
        basicServer.startup();

        downstreamServer = new Server(downstreamServerConfig);
        downstreamServer.startup();

        proxyServer = new Server(proxyServerConfig);
        proxyServer.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        proxyServer.shutdown();
        downstreamServer.shutdown();
        basicServer.shutdown();
    }

    private enum EndpointTypeScenario {
        STANDARD_ENDPOINT(basicServerConfig.endpointsPort(),
                          BasicEndpoint.MATCHING_PATH_BASE,
                          BasicEndpoint.RESPONSE_PAYLOAD),
        PROXY_ENDPOINT(proxyServerConfig.endpointsPort(),
                       RouterEndpoint.MATCHING_PATH_BASE,
                       DownstreamEndpoint.RESPONSE_PAYLOAD);

        public final int serverPort;
        public final String matchingPathBase;
        public final String successfulResponsePayload;

        EndpointTypeScenario(int serverPort, String matchingPathBase, String successfulResponsePayload) {
            this.serverPort = serverPort;
            this.matchingPathBase = matchingPathBase;
            this.successfulResponsePayload = successfulResponsePayload;
        }
    }

    @DataProvider(value = {
        "STANDARD_ENDPOINT",
        "PROXY_ENDPOINT"
    })
    @Test
    public void endpoints_should_be_reachable_with_barely_valid_initial_line_length_values(
        EndpointTypeScenario scenario
    ) throws Exception {
        // given
        String barelyAcceptableUri = generateUriForInitialLineLength(
            HttpMethod.GET, scenario.matchingPathBase, CUSTOM_REQUEST_DECODER_CONFIG.maxInitialLineLength()
        );
        Pair<String, Object> barelyAcceptableHeader =
            generateHeaderForHeaderLineLength(CUSTOM_REQUEST_DECODER_CONFIG.maxHeaderSize());

        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.GET)
            .withUri(barelyAcceptableUri)
            .withHeaders(barelyAcceptableHeader);

        // when
        NettyHttpClientResponse serverResponse = request.execute(scenario.serverPort,
                                                                 incompleteCallTimeoutMillis);

        // then
        assertThat(serverResponse.statusCode).isEqualTo(200);
        assertThat(serverResponse.payload).isEqualTo(scenario.successfulResponsePayload);
    }

    private String generateUriForInitialLineLength(HttpMethod method, String baseUri, int desiredInitialLineLength) {
        String baseInitialLine = method.name() + " " + baseUri + "/ HTTP/1.1";
        int neededWildcardLength = desiredInitialLineLength - baseInitialLine.length();
        return baseUri + "/" + generatePayload(neededWildcardLength, "a");
    }

    private Pair<String, Object> generateHeaderForHeaderLineLength(int desiredHeaderLineLength) {
        return generateHeaderForHeaderLineLength("foo", desiredHeaderLineLength);
    }

    private Pair<String, Object> generateHeaderForHeaderLineLength(String headerKey, int desiredHeaderLineLength) {
        String baseHeaderLine = headerKey + ": ";
        int neededHeaderValueLength = desiredHeaderLineLength - baseHeaderLine.length();
        return Pair.of(headerKey, generatePayload(neededHeaderValueLength, "h"));
    }

    @DataProvider(value = {
        "STANDARD_ENDPOINT",
        "PROXY_ENDPOINT"
    })
    @Test
    public void endpoints_should_throw_decode_exception_for_initial_line_length_that_is_too_long(
        EndpointTypeScenario scenario
    ) throws Exception {
        // given
        String tooLongUri = generateUriForInitialLineLength(
            HttpMethod.GET, scenario.matchingPathBase,
            CUSTOM_REQUEST_DECODER_CONFIG.maxInitialLineLength() + 1
        );

        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.GET)
            .withUri(tooLongUri);

        // when
        NettyHttpClientResponse serverResponse = request.execute(scenario.serverPort,
                                                                 incompleteCallTimeoutMillis);

        // then
        assertTooLongFrameErrorResponse(serverResponse, EXPECTED_TOO_LONG_FRAME_LINE_API_ERROR);
        // The EXPECTED_TOO_LONG_FRAME_LINE_API_ERROR check above should have verified 400 status code, but do a
        //      sanity check here just for test readability.
        assertThat(serverResponse.statusCode).isEqualTo(400);
    }

    @DataProvider(value = {
        "STANDARD_ENDPOINT",
        "PROXY_ENDPOINT"
    })
    @Test
    public void endpoints_should_throw_decode_exception_for_single_header_that_is_too_long(
        EndpointTypeScenario scenario
    ) throws Exception {
        // given
        Pair<String, Object> tooLongHeader = generateHeaderForHeaderLineLength(
            CUSTOM_REQUEST_DECODER_CONFIG.maxHeaderSize() + 1
        );

        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.GET)
            .withUri(scenario.matchingPathBase)
            .withHeaders(tooLongHeader);

        // when
        NettyHttpClientResponse serverResponse = request.execute(scenario.serverPort,
                                                                 incompleteCallTimeoutMillis);

        // then
        assertTooLongFrameErrorResponse(serverResponse, EXPECTED_TOO_LONG_FRAME_HEADER_API_ERROR);
        // The EXPECTED_TOO_LONG_FRAME_HEADER_API_ERROR check above should have verified 431 status code, but do a
        //      sanity check here just for test readability.
        assertThat(serverResponse.statusCode).isEqualTo(431);
    }

    @DataProvider(value = {
        "STANDARD_ENDPOINT",
        "PROXY_ENDPOINT"
    })
    @Test
    public void endpoints_should_throw_decode_exception_for_multiple_headers_that_are_too_long_when_summed(
        EndpointTypeScenario scenario
    ) throws Exception {
        // given
        Pair<String, Object> halfMaxLengthHeader = generateHeaderForHeaderLineLength(
            "foo",
            CUSTOM_REQUEST_DECODER_CONFIG.maxHeaderSize() / 2
        );
        Pair<String, Object> halfMaxLengthHeaderPlusOne = generateHeaderForHeaderLineLength(
            "bar",
            (CUSTOM_REQUEST_DECODER_CONFIG.maxHeaderSize() / 2) + 1
        );

        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.GET)
            .withUri(scenario.matchingPathBase)
            .withHeaders(halfMaxLengthHeader, halfMaxLengthHeaderPlusOne);

        // when
        NettyHttpClientResponse serverResponse = request.execute(scenario.serverPort,
                                                                 incompleteCallTimeoutMillis);

        // then
        assertTooLongFrameErrorResponse(serverResponse, EXPECTED_TOO_LONG_FRAME_HEADER_API_ERROR);
        // The EXPECTED_TOO_LONG_FRAME_HEADER_API_ERROR check above should have verified 431 status code, but do a
        //      sanity check here just for test readability.
        assertThat(serverResponse.statusCode).isEqualTo(431);
    }

    @DataProvider(value = {
        "STANDARD_ENDPOINT",
        "PROXY_ENDPOINT"
    })
    @Test
    public void endpoints_should_handle_decode_exception_for_invalid_http_request(
        EndpointTypeScenario scenario
    ) throws Exception {
        // given
        int payloadSize = CUSTOM_REQUEST_DECODER_CONFIG.maxInitialLineLength() + 1;
        String payload = generatePayload(payloadSize);

        //leave off content-length and transfer-encoding headers to trigger DecoderFailedResult
        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.POST)
            .withUri(scenario.matchingPathBase)
            .withPaylod(payload);

        // when
        NettyHttpClientResponse serverResponse = request.execute(scenario.serverPort,
                                                                 incompleteCallTimeoutMillis);

        // then
        assertTooLongFrameErrorResponse(serverResponse, EXPECTED_TOO_LONG_FRAME_LINE_API_ERROR);
        // The EXPECTED_TOO_LONG_FRAME_LINE_API_ERROR check above should have verified 400 status code, but do a
        //      sanity check here just for test readability.
        assertThat(serverResponse.statusCode).isEqualTo(400);
    }

    static HttpRequestDecoderConfig CUSTOM_REQUEST_DECODER_CONFIG = new HttpRequestDecoderConfig() {
        @Override
        public int maxInitialLineLength() {
            return 100;
        }

        @Override
        public int maxHeaderSize() {
            return 200;
        }
    };

    private static ApiError malformedReqError = SampleCoreApiError.MALFORMED_REQUEST;
    private static ApiError EXPECTED_TOO_LONG_FRAME_LINE_API_ERROR = new ApiErrorWithMetadata(
        new ApiErrorBase(malformedReqError, "TOO_LONG_HTTP_LINE"),
        Pair.of("cause", "The request contained a HTTP line that was longer than the maximum allowed"),
        Pair.of("max_length_allowed", CUSTOM_REQUEST_DECODER_CONFIG.maxInitialLineLength())
    );
    private static ApiError EXPECTED_TOO_LONG_FRAME_HEADER_API_ERROR = new ApiErrorWithMetadata(
        new ApiErrorBase(
            "TOO_LONG_HEADERS", malformedReqError.getErrorCode(), malformedReqError.getMessage(),
            431, malformedReqError.getMetadata()
        ),
        Pair.of("cause", "The combined size of the request's HTTP headers was more than the maximum allowed"),
        Pair.of("max_length_allowed", CUSTOM_REQUEST_DECODER_CONFIG.maxHeaderSize())
    );

    private void assertTooLongFrameErrorResponse(NettyHttpClientResponse serverResponse,
                                                 ApiError expectedApiError) throws IOException {
        verifyErrorReceived(serverResponse.payload, serverResponse.statusCode, expectedApiError);
    }

    private static class BasicEndpoint extends StandardEndpoint<Void, String> {
        public static final String MATCHING_PATH_BASE = "/basicEndpoint";
        public static final String MATCHING_PATH = MATCHING_PATH_BASE + "/**";
        public static final String RESPONSE_PAYLOAD = "basic-endpoint-" + UUID.randomUUID().toString();

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<Void> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            //need to do some work in a future to force the DecoderException to bubble up and return a 400
            return CompletableFuture.supplyAsync(supplierWithTracingAndMdc(() -> {
                try {
                    sleep(10);
                } catch (InterruptedException e) {
                }
                return null;
            }, ctx), longRunningTaskExecutor).thenApply(o -> ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build());
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    private static class DownstreamEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/downstreamEndpoint";
        public static final String RESPONSE_PAYLOAD = "downstream-endpoint-" + UUID.randomUUID().toString();

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<Void> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            //need to do some work in a future to force the DecoderException to bubble up and return a 400
            return CompletableFuture.supplyAsync(supplierWithTracingAndMdc(() -> {
                try {
                    sleep(10);
                } catch (InterruptedException e) {
                }
                return null;
            }, ctx), longRunningTaskExecutor).thenApply(o -> ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build());
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class RouterEndpoint extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH_BASE = "/proxyEndpoint";
        public static final String MATCHING_PATH = MATCHING_PATH_BASE + "/**";
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

    public static class BasicServerTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public BasicServerTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = singleton(new BasicEndpoint());
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
        public @Nullable HttpRequestDecoderConfig httpRequestDecoderConfig() {
            return CUSTOM_REQUEST_DECODER_CONFIG;
        }
    }

    public static class DownstreamServerTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public DownstreamServerTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = singleton(new DownstreamEndpoint());
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

    public static class ProxyTestingTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public ProxyTestingTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = singleton(new RouterEndpoint(downstreamServerConfig.endpointsPort()));
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
        public @Nullable HttpRequestDecoderConfig httpRequestDecoderConfig() {
            return CUSTOM_REQUEST_DECODER_CONFIG;
        }
    }
}
