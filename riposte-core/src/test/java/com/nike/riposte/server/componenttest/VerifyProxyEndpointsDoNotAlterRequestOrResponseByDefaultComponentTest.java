package com.nike.riposte.server.componenttest;

import com.nike.internal.util.Pair;
import com.nike.internal.util.StringUtils;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.hooks.PipelineCreateHook;
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
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;

import static com.nike.riposte.server.testutils.ComponentTestUtils.extractBodyFromRawRequestOrResponse;
import static com.nike.riposte.server.testutils.ComponentTestUtils.extractFullBodyFromChunks;
import static com.nike.riposte.server.testutils.ComponentTestUtils.extractHeadersFromRawRequestOrResponse;
import static com.nike.riposte.server.testutils.ComponentTestUtils.generatePayload;
import static com.nike.riposte.server.testutils.ComponentTestUtils.headersToMap;
import static com.nike.riposte.server.testutils.ComponentTestUtils.request;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaders.Values.CHUNKED;
import static io.netty.util.CharsetUtil.UTF_8;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ProxyRouterEndpoint}s do not alter the request or response in any way by default (except changes
 * that are strictly required by HTTP spec like setting host header, and tracing headers which we do want by default).
 *
 * <p>This includes not changing request or response payloads *in any way whatsoever, no exceptions* - including no
 * changes to transfer-encoding (i.e. if the request or response from the source is chunked then it will arrive at its
 * destination chunked, but if it was non-chunked then it will arrive at its destination non-chunked).
 */
@RunWith(DataProviderRunner.class)
public class VerifyProxyEndpointsDoNotAlterRequestOrResponseByDefaultComponentTest {

    private static Server proxyServer;
    private static ServerConfig proxyServerConfig;
    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;
    private static StringBuilder downstreamServerRawRequest;
    private static StringBuilder downstreamServerRawResponse;
    private static StringBuilder proxyServerRawRequest;
    private static StringBuilder proxyServerRawResponse;
    private static final int incompleteCallTimeoutMillis = 5000;

    @BeforeClass
    public static void setUpClass() throws Exception {
        downstreamServerConfig = new DownstreamServerTestConfig();
        downstreamServer = new Server(downstreamServerConfig);
        downstreamServer.startup();

        proxyServerConfig = new ProxyTestingTestConfig();
        proxyServer = new Server(proxyServerConfig);
        proxyServer.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        proxyServer.shutdown();
        downstreamServer.shutdown();
    }

    @Before
    public void setup() {
        downstreamServerRawRequest = new StringBuilder();
        downstreamServerRawResponse = new StringBuilder();
        proxyServerRawRequest = new StringBuilder();
        proxyServerRawResponse = new StringBuilder();
    }

    @After
    public void cleanup() {
        downstreamServerRawRequest = null;
        downstreamServerRawResponse = null;
        proxyServerRawRequest = null;
        proxyServerRawResponse = null;
    }

    private enum CallScenario {
        CHUNKED_REQUEST_CHUNKED_RESPONSE(true, true),
        CHUNKED_REQUEST_NORMAL_RESPONSE(true, false),
        NORMAL_REQUEST_CHUNKED_RESPONSE(false, true),
        NORMAL_REQUEST_NORMAL_RESPONSE(false, false);

        public final boolean isChunkedRequest;
        public final boolean isChunkedResponse;

        CallScenario(boolean isChunkedRequest, boolean isChunkedResponse) {
            this.isChunkedRequest = isChunkedRequest;
            this.isChunkedResponse = isChunkedResponse;
        }
    }

    private static final Pair<String, String> SOME_EXPECTED_REQUEST_HEADER =
        Pair.of("some-expected-request-header", UUID.randomUUID().toString());

    @DataProvider(value = {
        "CHUNKED_REQUEST_CHUNKED_RESPONSE",
        "CHUNKED_REQUEST_NORMAL_RESPONSE",
        "NORMAL_REQUEST_CHUNKED_RESPONSE",
        "NORMAL_REQUEST_NORMAL_RESPONSE",
    })
    @Test
    public void proxy_endpoints_should_honor_transfer_encoding_and_not_alter_request_or_response(
        CallScenario scenario
    ) throws Exception {
        // given
        int payloadSize = 10000;
        String origRequestPayload = generatePayload(payloadSize);

        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.POST)
            .withUri(RouterEndpoint.MATCHING_PATH)
            .withPaylod(origRequestPayload)
            .withHeader(SOME_EXPECTED_REQUEST_HEADER.getKey(), SOME_EXPECTED_REQUEST_HEADER.getValue())
            .withHeader(HttpHeaders.Names.HOST, "localhost");

        if (scenario.isChunkedRequest) {
            request = request.withHeader(TRANSFER_ENCODING, CHUNKED);
        }
        else {
            request = request.withHeader(CONTENT_LENGTH, origRequestPayload.length());
        }

        if (scenario.isChunkedResponse) {
            request.withHeader(DownstreamEndpoint.RESPONSE_SHOULD_BE_CHUNKED_REQUEST_HEADER_KEY, "true");
        }

        // when
        NettyHttpClientResponse serverResponse = request.execute(proxyServerConfig.endpointsPort(),
                                                                incompleteCallTimeoutMillis);

        // then
        // Sanity check the response from the caller's perspective.
        assertThat(serverResponse.statusCode).isEqualTo(HttpResponseStatus.OK.code());
        assertThat(serverResponse.payload).isEqualTo(DownstreamEndpoint.RESPONSE_PAYLOAD);

        // Verify all the requests through the proxy to the downstream.
        verifyRawRequestStuff(scenario, origRequestPayload);

        // Verify all the responses from the downstream through the proxy.
        verifyRawResponseStuff(scenario, DownstreamEndpoint.RESPONSE_PAYLOAD);
    }

    @DataProvider(value = {
        "CHUNKED_REQUEST_CHUNKED_RESPONSE",
        "CHUNKED_REQUEST_NORMAL_RESPONSE",
        "NORMAL_REQUEST_CHUNKED_RESPONSE",
        "NORMAL_REQUEST_NORMAL_RESPONSE",
    })
    @Test
    public void proxy_endpoints_should_not_alter_request_or_response_when_there_is_no_payload(
        CallScenario scenario
    ) throws Exception {
        // given
        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.POST)
            .withUri(RouterEndpoint.MATCHING_PATH)
            .withHeader(SOME_EXPECTED_REQUEST_HEADER.getKey(), SOME_EXPECTED_REQUEST_HEADER.getValue())
            .withHeader(HttpHeaders.Names.HOST, "localhost")
            .withHeader(DownstreamEndpoint.RESPONSE_SHOULD_BE_EMPTY_REQUEST_HEADER_KEY, "true");

        if (scenario.isChunkedRequest) {
            request = request.withHeader(TRANSFER_ENCODING, CHUNKED);
        }
        else {
            request = request.withHeader(CONTENT_LENGTH, 0);
        }

        if (scenario.isChunkedResponse) {
            request.withHeader(DownstreamEndpoint.RESPONSE_SHOULD_BE_CHUNKED_REQUEST_HEADER_KEY, "true");
        }

        // when
        NettyHttpClientResponse serverResponse = request.execute(proxyServerConfig.endpointsPort(),
                                                                 incompleteCallTimeoutMillis);

        // then
        // Sanity check the response from the caller's perspective.
        assertThat(serverResponse.statusCode).isEqualTo(HttpResponseStatus.OK.code());
        assertThat(serverResponse.payload).isNullOrEmpty();

        // Verify all the requests through the proxy to the downstream.
        verifyRawRequestStuff(scenario, "");

        // Verify all the responses from the downstream through the proxy.
        verifyRawResponseStuff(scenario, "");
    }

    private enum AlwaysEmptyResponseScenario {
        STATUS_204_RESPONSE(204, HttpMethod.POST, false, false),
        // NOTE: It's unclear whether expectProxyToStripTransferEncodingResponseHeader should be true or false for
        //      205 responses. Netty strips it, so we'll set it to true for now, but it's possible it should be false?
        STATUS_205_RESPONSE(205, HttpMethod.POST, false, true),
        STATUS_304_RESPONSE(304, HttpMethod.POST, true, true),
        HEAD_REQUEST(200, HttpMethod.HEAD, true, true);

        private final int responseStatusCode;
        private final HttpMethod requestMethod;
        private final boolean isResponseAllowedToLieAboutContentLength;
        private final boolean expectProxyToStripTransferEncodingResponseHeader;

        AlwaysEmptyResponseScenario(int responseStatusCode, HttpMethod requestMethod,
                                    boolean isResponseAllowedToLieAboutContentLength,
                                    boolean expectProxyToStripTransferEncodingResponseHeader) {
            this.responseStatusCode = responseStatusCode;
            this.requestMethod = requestMethod;
            this.isResponseAllowedToLieAboutContentLength = isResponseAllowedToLieAboutContentLength;
            this.expectProxyToStripTransferEncodingResponseHeader = expectProxyToStripTransferEncodingResponseHeader;
        }
    }

    @DataProvider(value = {
        "STATUS_204_RESPONSE    |   true",
        "STATUS_204_RESPONSE    |   false",
        "STATUS_205_RESPONSE    |   true",
        "STATUS_205_RESPONSE    |   false",
        "STATUS_304_RESPONSE    |   true",
        "STATUS_304_RESPONSE    |   false",
        "HEAD_REQUEST           |   true",
        "HEAD_REQUEST           |   false"
    }, splitBy = "\\|")
    @Test
    public void proxy_endpoints_should_handle_always_empty_response_scenarios_correctly(
        AlwaysEmptyResponseScenario scenario,
        boolean isChunkedResponse
    ) throws Exception {

        // given
        NettyHttpClientRequestBuilder request = request()
            .withMethod(scenario.requestMethod)
            .withUri(RouterEndpoint.MATCHING_PATH)
            .withHeader(SOME_EXPECTED_REQUEST_HEADER.getKey(), SOME_EXPECTED_REQUEST_HEADER.getValue())
            .withHeader(HttpHeaders.Names.HOST, "localhost")
            .withHeader(CONTENT_LENGTH, 0)
            .withHeader(DownstreamEndpoint.DESIRED_RESPONSE_STATUS_CODE_HEADER_KEY, scenario.responseStatusCode);

        if (isChunkedResponse) {
            request.withHeader(DownstreamEndpoint.RESPONSE_SHOULD_BE_CHUNKED_REQUEST_HEADER_KEY, "true");
        }

        // when
        NettyHttpClientResponse serverResponse = request.execute(proxyServerConfig.endpointsPort(),
                                                                 incompleteCallTimeoutMillis);

        // then
        // Sanity check the response from the caller's perspective.
        assertThat(serverResponse.statusCode).isEqualTo(scenario.responseStatusCode);
        assertThat(serverResponse.payload).isNullOrEmpty();

        {
            // Verify content-length header. We have to do this on the raw on-the-wire data because the Netty
            //      HttpObjectAggregator (which is used by NettyHttpClientResponse) adds a Content-Length header even
            //      if the original response didn't have one (e.g. 204 response).
            HttpHeaders proxyResponseHeaders = extractHeadersFromRawRequestOrResponse(proxyServerRawResponse.toString());
            HttpHeaders downstreamResponseHeaders = extractHeadersFromRawRequestOrResponse(downstreamServerRawResponse.toString());
            String proxyResponseContentLengthHeader = proxyResponseHeaders.get(CONTENT_LENGTH);
            assertThat(proxyResponseContentLengthHeader).isEqualTo(downstreamResponseHeaders.get(CONTENT_LENGTH));

            // Now we know that downstream and proxy response had matching content-length header, so we can move on
            //      to verifying the value of that header.
            if (scenario.responseStatusCode == 205) {
                // Netty treats 205 special - it strips any payload and transfer-encoding header, and sets
                //      content-length to 0. So even though we ask for a chunked response (which would normally lead to
                //      null content-length response header), we end up with content-length header equal to zero.
                //      See this Netty PR and issue: https://github.com/netty/netty/pull/7891
                //      and https://github.com/netty/netty/issues/7888
                assertThat(proxyResponseContentLengthHeader).isEqualTo("0");
            }
            else if (isChunkedResponse || scenario.responseStatusCode == 204) {
                // Chunked responses will never have content-length header defined, and
                //      204 is special - it should never have content-length returned regardless of transfer encoding.
                assertThat(proxyResponseContentLengthHeader).isNull();
            }
            else if (scenario.isResponseAllowedToLieAboutContentLength) {
                assertThat(proxyResponseContentLengthHeader)
                    .isEqualTo(String.valueOf(DownstreamEndpoint.RESPONSE_PAYLOAD.length()));
            }
            else {
                assertThat(proxyResponseContentLengthHeader).isEqualTo("0");
            }
        }

        CallScenario normalOrChunkedResponseScenario = (isChunkedResponse)
                                ? CallScenario.NORMAL_REQUEST_CHUNKED_RESPONSE
                                : CallScenario.NORMAL_REQUEST_NORMAL_RESPONSE;

        // Verify all the requests through the proxy to the downstream (not strictly necessary for what this test is
        //      testing, but doesn't hurt).
        verifyRawRequestStuff(normalOrChunkedResponseScenario, "");

        // Verify all the responses from the downstream through the proxy.
        if (scenario.expectProxyToStripTransferEncodingResponseHeader) {
            verifyRawResponseStuffButIgnoreTransferEncodingHeader(normalOrChunkedResponseScenario, "");
        }
        else {
            verifyRawResponseStuff(normalOrChunkedResponseScenario, "");
        }
    }

    private void verifyRawRequestStuff(CallScenario scenario, String origRequestPayload) {
        verifyProxyAndDownstreamRequestHeaders();

        String downstreamRawRequestBody = extractBodyFromRawRequestOrResponse(downstreamServerRawRequest.toString());
        String proxyRawRequestBody = extractBodyFromRawRequestOrResponse(proxyServerRawRequest.toString());

        if (scenario.isChunkedRequest) {
            // Verify that the request was sent in chunks
            verifyChunked(downstreamRawRequestBody);
            verifyChunked(proxyRawRequestBody);
        }
        else {
            // Verify that the request was NOT sent in chunks
            verifyNotChunked(downstreamRawRequestBody);
            verifyNotChunked(proxyRawRequestBody);
        }

        // Verify that request bodies are functionally equal by removing the chunk metadata (if any) and comparing.
        verifyBodyEqualityMinusChunkMetadata(downstreamRawRequestBody, origRequestPayload);
        verifyBodyEqualityMinusChunkMetadata(proxyRawRequestBody, origRequestPayload);
        verifyBodyEqualityMinusChunkMetadata(proxyRawRequestBody, downstreamRawRequestBody);
    }

    private void verifyRawResponseStuff(
        CallScenario scenario,
        String expectedResponseBody
    ) {
        verifyRawResponseStuff(scenario, expectedResponseBody, false);
    }

    private void verifyRawResponseStuffButIgnoreTransferEncodingHeader(
        CallScenario scenario,
        String expectedResponseBody
    ) {
        verifyRawResponseStuff(scenario, expectedResponseBody, true);
    }

    private void verifyRawResponseStuff(
        CallScenario scenario,
        String expectedResponseBody,
        boolean expectProxyToRemoveTransferEncoding
    ) {
        verifyProxyAndDownstreamResponseHeaders(expectProxyToRemoveTransferEncoding);

        String downstreamRawResponseBody = extractBodyFromRawRequestOrResponse(downstreamServerRawResponse.toString());
        String proxyRawResponseBody = extractBodyFromRawRequestOrResponse(proxyServerRawResponse.toString());

        if (scenario.isChunkedResponse) {
            // Verify that the response was sent in chunks, or if the expected response body is empty then we have
            //      a few options.
            if (expectedResponseBody.isEmpty()) {
                // It either needs to be actually empty, or a single chunk that represents an empty payload.
                verifyActuallyEmptyOrSingleEmptyChunk(downstreamRawResponseBody);
                verifyActuallyEmptyOrSingleEmptyChunk(proxyRawResponseBody);
            }
            else {
                // Not empty - do normal chunk checks.
                verifyChunked(downstreamRawResponseBody);
                verifyChunked(proxyRawResponseBody);
            }
        }
        else {
            // Verify that the response was *not* sent in chunks
            verifyNotChunked(downstreamRawResponseBody);
            verifyNotChunked(proxyRawResponseBody);
        }

        // Verify that response bodies are functionally equal by removing the chunk metadata (if any) and comparing.
        verifyBodyEqualityMinusChunkMetadata(downstreamRawResponseBody, expectedResponseBody);
        verifyBodyEqualityMinusChunkMetadata(proxyRawResponseBody, expectedResponseBody);
        verifyBodyEqualityMinusChunkMetadata(proxyRawResponseBody, downstreamRawResponseBody);
    }

    private void verifyChunked(String body) {
        //https://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6.1
        assertThat(body).contains("\r\n");
    }

    private void verifyActuallyEmptyOrSingleEmptyChunk(String body) {
        if (body.isEmpty()) {
            return;
        }

        // Not the empty string, so it should be an empty chunk, i.e. "0\r\n\r\n"
        assertThat(body).isEqualTo("0\r\n\r\n");
    }

    private void verifyNotChunked(String body) {
        //https://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6.1
        assertThat(body).doesNotContain("\r\n");
    }

    private void verifyBodyEqualityMinusChunkMetadata(String body1, String body2) {
        String body1MinusChunkMeta = extractFullBodyFromChunks(body1);
        String body2MinusChunkMeta = extractFullBodyFromChunks(body2);

        assertThat(body1MinusChunkMeta).isEqualTo(body2MinusChunkMeta);
    }

    private void verifyProxyAndDownstreamRequestHeaders() {
        HttpHeaders proxyRequestHeaders = extractHeadersFromRawRequestOrResponse(proxyServerRawRequest.toString());
        HttpHeaders downstreamRequestHeaders = extractHeadersFromRawRequestOrResponse(downstreamServerRawRequest.toString());

        // Verify that request headers are passed downstream as expected. The host may change, but content length
        //      and transfer encoding in particular should be unchanged as it passes through the proxy.
        assertThat(proxyRequestHeaders.get(HttpHeaders.Names.HOST)).isEqualTo("localhost");
        assertThat(proxyRequestHeaders.get(CONTENT_LENGTH)).isEqualTo(downstreamRequestHeaders.get(CONTENT_LENGTH));
        assertThat(proxyRequestHeaders.get(TRANSFER_ENCODING)).isEqualTo(downstreamRequestHeaders.get(TRANSFER_ENCODING));
        assertThat(downstreamRequestHeaders.get(HttpHeaders.Names.HOST)).isEqualTo("127.0.0.1:" + downstreamServerConfig.endpointsPort());

        // Sanity check that the expected header was passed.
        assertThat(proxyRequestHeaders.get(SOME_EXPECTED_REQUEST_HEADER.getKey())).isEqualTo(SOME_EXPECTED_REQUEST_HEADER.getValue());
        assertThat(downstreamRequestHeaders.get(SOME_EXPECTED_REQUEST_HEADER.getKey())).isEqualTo(SOME_EXPECTED_REQUEST_HEADER.getValue());

        // Verify that trace info was added to downstream call by the proxy.
        assertThat(downstreamRequestHeaders.get("X-B3-TraceId")).isNotNull();
        assertThat(downstreamRequestHeaders.get("X-B3-SpanId")).isNotNull();
        assertThat(downstreamRequestHeaders.get("X-B3-ParentSpanId")).isNotNull();
        assertThat(downstreamRequestHeaders.get("X-B3-Sampled")).isNotNull();

        // Verify that the proxy and downstream requests have the same headers (barring the tracing headers and the
        //      host header, which can be different between proxy and downstream and we've already accounted for above).
        Map<String, List<String>> normalizedProxyHeaders =
            headersToMap(copyWithMutableHeadersRemoved(proxyRequestHeaders), true);
        Map<String, List<String>> normalizedDownstreamHeaders =
            headersToMap(copyWithMutableHeadersRemoved(downstreamRequestHeaders), true);
        assertThat(normalizedProxyHeaders).isEqualTo(normalizedDownstreamHeaders);
    }

    private HttpHeaders copyWithMutableHeadersRemoved(HttpHeaders orig) {
        HttpHeaders result = new DefaultHttpHeaders();
        return result.set(orig)
                     .remove("Host")
                     .remove("X-B3-TraceId")
                     .remove("X-B3-SpanId")
                     .remove("X-B3-ParentSpanId")
                     .remove("X-B3-Sampled");
    }

    /**
     * @param expectProxyToRemoveTransferEncoding According to https://tools.ietf.org/html/rfc7230#section-3.3.1,
     * proxies are allowed to remove transfer-encoding for responses to HEAD requests, or to 304 responses. Netty
     * actually does perform this removal, so in those two cases pass in true for this argument to have the verification
     * ignore the transfer-encoding header.
     */
    private void verifyProxyAndDownstreamResponseHeaders(boolean expectProxyToRemoveTransferEncoding) {
        HttpHeaders proxyResponseHeaders = extractHeadersFromRawRequestOrResponse(proxyServerRawResponse.toString());
        HttpHeaders downstreamResponseHeaders = extractHeadersFromRawRequestOrResponse(downstreamServerRawResponse.toString());

        // Verify that trace ID was added by the proxy.
        assertThat(downstreamResponseHeaders.get("X-B3-TraceId")).isNull();
        assertThat(proxyResponseHeaders.get("X-B3-TraceId")).isNotNull();

        // Sanity check that the expected response header was passed.
        assertThat(proxyResponseHeaders.get(DownstreamEndpoint.SOME_EXPECTED_RESPONSE_HEADER.getKey()))
            .isEqualTo(DownstreamEndpoint.SOME_EXPECTED_RESPONSE_HEADER.getValue());
        assertThat(downstreamResponseHeaders.get(DownstreamEndpoint.SOME_EXPECTED_RESPONSE_HEADER.getKey()))
            .isEqualTo(DownstreamEndpoint.SOME_EXPECTED_RESPONSE_HEADER.getValue());

        // Verify that the proxy and downstream requests have the same headers (barring the trace ID header, which can
        //      be added by the proxy and we've already accounted for above).
        Map<String, List<String>> normalizedProxyHeaders =
            headersToMap(copyWithMutableHeadersRemoved(proxyResponseHeaders), true);
        Map<String, List<String>> normalizedDownstreamHeaders =
            headersToMap(copyWithMutableHeadersRemoved(downstreamResponseHeaders), true);

        if (expectProxyToRemoveTransferEncoding) {
            List<String> downstreamTransferEncodingValue = normalizedDownstreamHeaders.get(TRANSFER_ENCODING.toLowerCase());

            if (downstreamTransferEncodingValue != null) {
                // For the purpose of testing equality we'll set the proxy headers to include whatever downstream had
                //      for transfer-encoding. This is because the Netty correctly stripped that header on the proxy
                //      when it received the response. By adding this back to the proxy headers we can then do a normal
                //      equality check in order to verify the other headers.
                normalizedProxyHeaders.put(TRANSFER_ENCODING.toLowerCase(), downstreamTransferEncodingValue);
            }
        }

        assertThat(normalizedProxyHeaders).isEqualTo(normalizedDownstreamHeaders);
    }

    // We're doing a raw netty endpoint for the downstream so that we can explicitly specify chunked or full responses.
    private static class DownstreamEndpoint extends SimpleChannelInboundHandler<FullHttpRequest> {
        public static final String MATCHING_PATH = "/downstreamEndpoint";
        public static final List<String> RESPONSE_PAYLOAD_CHUNKS = Arrays.asList(
            generatePayload(10000),
            generatePayload(5000),
            generatePayload(25),
            generatePayload(5),
            generatePayload(1)
        );
        public static final String RESPONSE_PAYLOAD = StringUtils.join(RESPONSE_PAYLOAD_CHUNKS, "");
        public static final String RESPONSE_SHOULD_BE_CHUNKED_REQUEST_HEADER_KEY = "response-should-be-chunked";
        public static final String RESPONSE_SHOULD_BE_EMPTY_REQUEST_HEADER_KEY = "response-should-be-empty";
        public static final String DESIRED_RESPONSE_STATUS_CODE_HEADER_KEY = "desired-response-status-code";
        public static final Pair<String, String> SOME_EXPECTED_RESPONSE_HEADER =
            Pair.of("some-expected-response-header", UUID.randomUUID().toString());

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            List<HttpObject> responseChunks;

            if (msg.uri().equals(MATCHING_PATH)) {
                if(HttpMethod.POST.equals(msg.method()) || HttpMethod.HEAD.equals(msg.method())) {
                    responseChunks = handleMatchingCall(msg);
                }
                else {
                    responseChunks = handleError(HttpResponseStatus.METHOD_NOT_ALLOWED);
                }
            }
            else {
                responseChunks = handleError(HttpResponseStatus.NOT_FOUND);
            }

            responseChunks.forEach(chunk -> {
                ctx.write(chunk);
                ctx.flush();
            });
        }

        protected List<HttpObject> handleMatchingCall(FullHttpRequest request) {
            boolean responseShouldBeEmpty = "true".equals(request.headers().get(RESPONSE_SHOULD_BE_EMPTY_REQUEST_HEADER_KEY));
            String desiredResponseStatusCodeString = request.headers().get(DESIRED_RESPONSE_STATUS_CODE_HEADER_KEY);
            int desiredResponseStatusCode = (desiredResponseStatusCodeString == null)
                                            ? 200
                                            : Integer.parseInt(desiredResponseStatusCodeString);

            if ("true".equals(request.headers().get(RESPONSE_SHOULD_BE_CHUNKED_REQUEST_HEADER_KEY))) {
                return handleChunkedResponse(desiredResponseStatusCode, responseShouldBeEmpty);
            }
            else {
                return handleFullResponse(desiredResponseStatusCode, responseShouldBeEmpty);
            }
        }

        private List<HttpObject> handleFullResponse(int desiredResponseStatusCode, boolean responseShouldBeEmpty) {
            ByteBuf responsePayload = (responseShouldBeEmpty)
                                      ? Unpooled.buffer(0)
                                      : Unpooled.wrappedBuffer(RESPONSE_PAYLOAD.getBytes(CharsetUtil.UTF_8));
            
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(desiredResponseStatusCode),
                responsePayload
            );

            setContentLengthAndKeepAliveHeaders(response);
            response.headers().set(SOME_EXPECTED_RESPONSE_HEADER.getKey(), SOME_EXPECTED_RESPONSE_HEADER.getValue());
            
            return singletonList(response);
        }

        private List<HttpObject> handleChunkedResponse(int desiredResponseStatusCode, boolean responseShouldBeEmpty) {
            HttpResponse firstChunk = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(desiredResponseStatusCode)
            );

            firstChunk.headers()
                    .set(TRANSFER_ENCODING, CHUNKED)
                    .set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
                    .set(SOME_EXPECTED_RESPONSE_HEADER.getKey(), SOME_EXPECTED_RESPONSE_HEADER.getValue());

            List<HttpObject> responseChunks = new ArrayList<>();
            
            responseChunks.add(firstChunk);

            if (!responseShouldBeEmpty) {
                RESPONSE_PAYLOAD_CHUNKS.forEach(chunkData -> responseChunks.add(
                    new DefaultHttpContent(Unpooled.wrappedBuffer(chunkData.getBytes(CharsetUtil.UTF_8)))
                ));
            }

            responseChunks.add(LastHttpContent.EMPTY_LAST_CONTENT);

            return responseChunks;
        }

        protected List<HttpObject> handleError(HttpResponseStatus responseStatus) {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, responseStatus);
            setContentLengthAndKeepAliveHeaders(response);
            return singletonList(response);
        }

        protected void setContentLengthAndKeepAliveHeaders(FullHttpResponse response) {
            response.headers()
                    .set(CONTENT_LENGTH, response.content().readableBytes())
                    .set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
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

    public static class DownstreamServerTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public DownstreamServerTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            // We're going to hack up the pipeline and do a raw netty endpoint, see pipelineCreateHooks() below.
            //      We still have to have a non-empty collection though or Riposte won't start up.
            endpoints = singleton(new StandardEndpoint<Void, Void>() {
                @Override
                public @NotNull Matcher requestMatcher() {
                    return Matcher.match("/should/never/match/anything/" + UUID.randomUUID().toString());
                }

                @Override
                public @NotNull CompletableFuture<ResponseInfo<Void>> execute(
                    @NotNull RequestInfo<Void> request,
                    @NotNull Executor longRunningTaskExecutor,
                    @NotNull ChannelHandlerContext ctx
                ) {
                    throw new UnsupportedOperationException("Should never reach here");
                }
            });
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
        public @Nullable List<@NotNull PipelineCreateHook> pipelineCreateHooks() {
            return singletonList(pipeline -> {
                try {
                    // Clear out the entire pipeline. We're going to build one from scratch.
                    while(true) {
                        pipeline.removeFirst();
                    }
                }
                catch(NoSuchElementException ex) {
                    // Expected. Do nothing.
                }
                pipeline.addFirst("recordDownstreamInboundRequest", new RecordDownstreamServerInboundRequest());
                pipeline.addFirst("recordDownstreamOutboundResponse", new RecordDownstreamServerOutboundResponse());
                pipeline.addLast("serverCodec", new HttpServerCodec());
                pipeline.addLast("httpObjectAggregator", new HttpObjectAggregator(Integer.MAX_VALUE));
                pipeline.addLast("httpRequestHandler", new DownstreamEndpoint());
            });
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
        public @Nullable List<@NotNull PipelineCreateHook> pipelineCreateHooks() {
            return singletonList(pipeline -> {
                pipeline.addFirst("recordProxyInboundRequest", new RecordProxyServerInboundRequest());
                pipeline.addFirst("recordProxyOutboundResponse", new RecordProxyServerOutboundResponse());
            });
        }
    }

    private static class RecordDownstreamServerInboundRequest extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ByteBuf byteBuf = (ByteBuf) msg;

            downstreamServerRawRequest.append(byteBuf.toString(UTF_8));

            super.channelRead(ctx, msg);
        }
    }

    private static class RecordDownstreamServerOutboundResponse extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            ByteBuf byteBuf = (ByteBuf) msg;
            downstreamServerRawResponse.append(byteBuf.toString(UTF_8));
            super.write(ctx, msg, promise);
        }
    }

    private static class RecordProxyServerInboundRequest extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ByteBuf byteBuf = (ByteBuf) msg;

            proxyServerRawRequest.append(byteBuf.toString(UTF_8));

            super.channelRead(ctx, msg);
        }
    }

    private static class RecordProxyServerOutboundResponse extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            ByteBuf byteBuf = (ByteBuf) msg;
            proxyServerRawResponse.append(byteBuf.toString(UTF_8));
            super.write(ctx, msg, promise);
        }
    }
}
