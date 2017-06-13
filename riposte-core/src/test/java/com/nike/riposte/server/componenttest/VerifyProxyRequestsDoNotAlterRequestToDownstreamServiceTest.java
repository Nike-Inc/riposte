package com.nike.riposte.server.componenttest;

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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import static com.nike.riposte.server.testutils.ComponentTestUtils.generatePayload;
import static com.nike.riposte.server.testutils.ComponentTestUtils.request;
import static io.netty.util.CharsetUtil.UTF_8;
import static java.util.Arrays.stream;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.StringUtils.containsOnly;
import static org.apache.commons.lang3.StringUtils.split;
import static org.apache.commons.lang3.StringUtils.substringAfter;
import static org.apache.commons.lang3.StringUtils.substringBefore;
import static org.apache.commons.lang3.StringUtils.substringBetween;
import static org.apache.commons.lang3.StringUtils.substringsBetween;
import static org.assertj.core.api.Assertions.assertThat;

public class VerifyProxyRequestsDoNotAlterRequestToDownstreamServiceTest {

    private static Server proxyServer;
    private static ServerConfig proxyServerConfig;
    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;
    private static StringBuilder downstreamServerRequest;
    private static StringBuilder proxyServerRequest;
    private static final String HEADER_SEPARATOR = ":";
    private static final int incompleteCallTimeoutMillis = 5000;
    private static final String payloadDictionary = "aBcDefGhiJkLmN@#$%";

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
        downstreamServerRequest = null;
        proxyServerRequest = null;
    }

    @Before
    public void setup() {
        downstreamServerRequest = new StringBuilder();
        proxyServerRequest = new StringBuilder();
    }

    @After
    public void cleanup() {
        downstreamServerRequest = null;
        proxyServerRequest = null;
    }

    @Test
    public void proxy_endpoints_should_honor_chunked_transfer_encoding() throws Exception {
        // given
        int payloadSize = 10000;
        String payload = generatePayload(payloadSize);

        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.POST)
            .withUri(RouterEndpoint.MATCHING_PATH)
            .withPaylod(payload)
            .withHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
            .withHeader(HttpHeaders.Names.HOST, "localhost");

        // when
        NettyHttpClientResponse serverResponse = request.execute(proxyServerConfig.endpointsPort(),
                                                                incompleteCallTimeoutMillis);

        // then
        assertThat(serverResponse.payload).isEqualTo(DownstreamEndpoint.RESPONSE_PAYLOAD);
        assertThat(serverResponse.statusCode).isEqualTo(HttpResponseStatus.OK.code());
        assertProxyAndDownstreamServiceHeadersAndTracingHeadersAdded();

        String proxyBody = extractBodyFromRawRequest(proxyServerRequest.toString());
        String downstreamBody = extractBodyFromRawRequest(downstreamServerRequest.toString());

        //assert request was sent in chunks
        //https://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6.1
        assertThat(downstreamBody).contains("\r\n");
        assertThat(proxyBody).contains("\r\n");

        //assert bodies are equal
        String proxyBodyMinusChunkInfo = extractFullBodyFromChunks(proxyBody);
        String downstreamBodyMinusChunkInfo = extractFullBodyFromChunks(downstreamBody);

        //assert proxy and downstream have same bodies
        assertThat(proxyBodyMinusChunkInfo).isEqualTo(downstreamBodyMinusChunkInfo);

        //assert input payload matches proxy and downstream payloads
        assertThat(proxyBodyMinusChunkInfo).isEqualTo(payload);
        assertThat(downstreamBodyMinusChunkInfo).isEqualTo(payload);
    }

    @Test
    public void proxy_endpoints_should_honor_non_chunked_transfer_encoding() throws Exception {
        // given
        int payloadSize = 10000;
        String payload = generatePayload(payloadSize);

        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.POST)
            .withUri(RouterEndpoint.MATCHING_PATH)
            .withPaylod(payload)
            .withHeader(HttpHeaders.Names.CONTENT_LENGTH, payloadSize)
            .withHeader(HttpHeaders.Names.HOST, "localhost");

        // when
        NettyHttpClientResponse serverResponse = request.execute(proxyServerConfig.endpointsPort(),
                                                                incompleteCallTimeoutMillis);

        // then
        assertThat(serverResponse.payload).isEqualTo(DownstreamEndpoint.RESPONSE_PAYLOAD);
        assertThat(serverResponse.statusCode).isEqualTo(HttpResponseStatus.OK.code());
        assertProxyAndDownstreamServiceHeadersAndTracingHeadersAdded();

        String proxyBody = extractBodyFromRawRequest(proxyServerRequest.toString());
        String downstreamBody = extractBodyFromRawRequest(downstreamServerRequest.toString());

        //assert request was NOT sent in chunks
        //https://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6.1
        assertThat(proxyBody).doesNotContain("\r\n");
        assertThat(downstreamBody).doesNotContain("\r\n");

        //assert bodies are equal
        assertThat(proxyBody).isEqualTo(downstreamBody);

        //assert input payload matches proxy and downstream payloads
        assertThat(proxyBody).isEqualTo(payload);
        assertThat(downstreamBody).isEqualTo(payload);
    }

    private String extractBodyFromRawRequest(String request) {
        return substringAfter(request.toString(), "\r\n\r\n"); //body start after \r\n\r\n combo
    }

    private String extractFullBodyFromChunks(String downstreamBody) {
        return stream(substringsBetween(downstreamBody, "\r\n", "\r\n")) //get all chunks
                .filter(chunk -> containsOnly(chunk, payloadDictionary)) //filter out chunk sizes
                .collect(Collectors.joining());
    }

    private Map<String, Object> extractHeaders(String requestHeaderString) {
        String concatHeaders = substringBetween(requestHeaderString, "HTTP/1.1\r\n", "\r\n\r\n");

        Map<String, Object> extractedHeaders = new HashMap<>();

        for (String concatHeader : split(concatHeaders, "\r\n")) {
            extractedHeaders.put(substringBefore(concatHeader, HEADER_SEPARATOR).trim(), substringAfter(concatHeader, HEADER_SEPARATOR).trim());
        }

        return extractedHeaders;
    }

    private void assertProxyAndDownstreamServiceHeadersAndTracingHeadersAdded() {
        Map<String, Object> proxyHeaders = extractHeaders(proxyServerRequest.toString());
        Map<String, Object> downstreamHeaders = extractHeaders(downstreamServerRequest.toString());

        //assert input headers are passed down stream
        assertThat(proxyHeaders.get(HttpHeaders.Names.HOST)).isEqualTo("localhost");
        assertThat(proxyHeaders.get(HttpHeaders.Names.CONTENT_LENGTH)).isEqualTo(downstreamHeaders.get(HttpHeaders.Names.CONTENT_LENGTH));
        assertThat(proxyHeaders.get(HttpHeaders.Names.TRANSFER_ENCODING)).isEqualTo(downstreamHeaders.get(HttpHeaders.Names.TRANSFER_ENCODING));
        assertThat(downstreamHeaders.get(HttpHeaders.Names.HOST)).isEqualTo("127.0.0.1");

        //assert trace info added to downstream call
        assertThat(downstreamHeaders.get("X-B3-Sampled")).isNotNull();
        assertThat(downstreamHeaders.get("X-B3-TraceId")).isNotNull();
        assertThat(downstreamHeaders.get("X-B3-SpanId")).isNotNull();
        assertThat(downstreamHeaders.get("X-B3-SpanName")).isNotNull();
    }

    private static class DownstreamEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/downstreamEndpoint";
        public static final String RESPONSE_PAYLOAD = "basic-endpoint-" + UUID.randomUUID().toString();

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build());
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }
    }

    public static class RouterEndpoint extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/proxyEndpoint";
        private final int downstreamPort;

        public RouterEndpoint(int downstreamPort) {
            this.downstreamPort = downstreamPort;
        }

        @Override
        public CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(RequestInfo<?> request,
                                                                                                     Executor longRunningTaskExecutor,
                                                                                                     ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                    new DownstreamRequestFirstChunkInfo(
                            "127.0.0.1", downstreamPort, false,
                            generateSimplePassthroughRequest(request, DownstreamEndpoint.MATCHING_PATH, request.getMethod(), ctx)
                    )
            );
        }

        @Override
        public Matcher requestMatcher() {
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

            endpoints = singleton(new DownstreamEndpoint());
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }

        @Override
        public List<PipelineCreateHook> pipelineCreateHooks() {
            return singletonList(pipeline -> pipeline.addFirst("recordDownstreamInboundRequest", new RecordDownstreamServerInboundRequest()));
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
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }

        @Override
        public List<PipelineCreateHook> pipelineCreateHooks() {
            return singletonList(pipeline -> pipeline.addFirst("recordProxyInboundRequest", new RecordProxyServerInboundRequest()));
        }
    }

    public static class RecordDownstreamServerInboundRequest extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ByteBuf byteBuf = (ByteBuf) msg;

            downstreamServerRequest.append(byteBuf.toString(UTF_8));

            super.channelRead(ctx, msg);
        }
    }

    public static class RecordProxyServerInboundRequest extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ByteBuf byteBuf = (ByteBuf) msg;

            proxyServerRequest.append(byteBuf.toString(UTF_8));

            super.channelRead(ctx, msg);
        }
    }
}
