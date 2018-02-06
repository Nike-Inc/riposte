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
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.lifecyclelistener.SpanLifecycleListener;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.nike.riposte.server.testutils.ComponentTestUtils.extractBodyFromRawRequest;
import static com.nike.riposte.server.testutils.ComponentTestUtils.extractHeaders;
import static com.nike.riposte.server.testutils.ComponentTestUtils.generatePayload;
import static com.nike.riposte.server.testutils.ComponentTestUtils.request;
import static io.netty.util.CharsetUtil.UTF_8;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(DataProviderRunner.class)
public class VerifyProxyRouterTracingBehaviorComponentTest {

    private static Server proxyServer;
    private static ServerConfig proxyServerConfig;
    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;
    private static StringBuilder downstreamServerRequest;
    private static StringBuilder proxyServerRequest;
    private SpanRecorder spanRecorder;

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
    public void beforeMethod() {
        downstreamServerRequest = new StringBuilder();
        proxyServerRequest = new StringBuilder();

        resetTracing();
        spanRecorder = new SpanRecorder();
        Tracer.getInstance().addSpanLifecycleListener(spanRecorder);
    }

    @After
    public void afterMethod() {
        resetTracing();
    }

    @Test
    @DataProvider(value = {
            "false",
            "true"
    }, splitBy = "\\|")
    public void proxy_endpoints_should_setsTracingHeadersBasedOnFlag(boolean sendTraceHeaders) throws Exception {
        // given
        int payloadSize = 10000;
        String payload = generatePayload(payloadSize);

        NettyHttpClientRequestBuilder request = request()
                .withMethod(HttpMethod.POST)
                .withUri(RouterEndpoint.MATCHING_PATH)
                .withPaylod(payload)
                .withHeader("X-Test-SendTraceHeaders", sendTraceHeaders)
                .withHeader(HttpHeaders.Names.CONTENT_LENGTH, payloadSize)
                .withHeader(HttpHeaders.Names.HOST, "localhost");

        // when
        NettyHttpClientResponse serverResponse = request.execute(proxyServerConfig.endpointsPort(), 400);

        // then
        assertThat(serverResponse.payload).isEqualTo(DownstreamEndpoint.RESPONSE_PAYLOAD);
        assertThat(serverResponse.statusCode).isEqualTo(HttpResponseStatus.OK.code());
        assertProxyAndDownstreamServiceHeadersAndTracingHeadersAdded(sendTraceHeaders);

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

    @Test
    @DataProvider(value = {
            "true | true",
            "true | false",
            "false | false",
            "false | true"
    }, splitBy = "\\|")
    public void proxy_endpoints_should_performSubSpanAroundCallsBasedOnFlag(boolean performSubSpanAroundCall, boolean sendTraceHeadersToDownstream) throws Exception {
        // given
        int payloadSize = 10000;
        String payload = generatePayload(payloadSize);

        NettyHttpClientRequestBuilder request = request()
                .withMethod(HttpMethod.POST)
                .withUri(RouterEndpoint.MATCHING_PATH)
                .withPaylod(payload)
                .withHeader("X-Test-PerformSubSpanAroundCall", performSubSpanAroundCall)
                .withHeader("X-Test-SendTraceHeaders", sendTraceHeadersToDownstream)
                .withHeader(HttpHeaders.Names.CONTENT_LENGTH, payloadSize)
                .withHeader(HttpHeaders.Names.HOST, "localhost");

        // when
        NettyHttpClientResponse serverResponse = request.execute(proxyServerConfig.endpointsPort(), 400);

        // then
        assertThat(serverResponse.payload).isEqualTo(DownstreamEndpoint.RESPONSE_PAYLOAD);
        assertThat(serverResponse.statusCode).isEqualTo(HttpResponseStatus.OK.code());

        // verify SubSpan size and creation
        if (performSubSpanAroundCall) {
            assertThat(spanRecorder.completedSpans.size()).isEqualTo(3);
            assertThat(spanRecorder.completedSpans.get(1).getSpanName()).isEqualTo("async_downstream_call-POST_127.0.0.1:" + downstreamServerConfig.endpointsPort() + "/downstreamEndpoint");
        } else {
            assertThat(spanRecorder.completedSpans.size()).isEqualTo(2);
            for (Span completedSpan : spanRecorder.completedSpans) {
                assertThat(completedSpan.getSpanName()).doesNotStartWith("async_downstream_call");
            }
        }

        assertProxyAndDownstreamServiceHeadersAndTracingHeadersAdded(sendTraceHeadersToDownstream);

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

    private void assertProxyAndDownstreamServiceHeadersAndTracingHeadersAdded(boolean traceHeadersExpected) {
        Map<String, Object> proxyHeaders = extractHeaders(proxyServerRequest.toString());
        Map<String, Object> downstreamHeaders = extractHeaders(downstreamServerRequest.toString());

        //assert input headers are passed down stream
        assertThat(proxyHeaders.get(HttpHeaders.Names.HOST)).isEqualTo("localhost");
        assertThat(proxyHeaders.get(HttpHeaders.Names.CONTENT_LENGTH)).isEqualTo(downstreamHeaders.get(HttpHeaders.Names.CONTENT_LENGTH));
        assertThat(proxyHeaders.get(HttpHeaders.Names.TRANSFER_ENCODING)).isEqualTo(downstreamHeaders.get(HttpHeaders.Names.TRANSFER_ENCODING));
        assertThat(downstreamHeaders.get(HttpHeaders.Names.HOST)).isEqualTo("127.0.0.1:" + downstreamServerConfig.endpointsPort());

        //assert trace info added to downstream call
        assertThat(downstreamHeaders.containsKey("X-B3-Sampled")).isEqualTo(traceHeadersExpected);
        assertThat(downstreamHeaders.containsKey("X-B3-TraceId")).isEqualTo(traceHeadersExpected);
        assertThat(downstreamHeaders.containsKey("X-B3-SpanId")).isEqualTo(traceHeadersExpected);
        assertThat(downstreamHeaders.containsKey("X-B3-SpanName")).isEqualTo(traceHeadersExpected);
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
                            .withAddTracingHeadersToDownstreamCall(Boolean.parseBoolean(request.getHeaders().get("X-Test-SendTraceHeaders")))
                            .withPerformSubSpanAroundDownstreamCall(Boolean.parseBoolean(request.getHeaders().get("X-Test-PerformSubSpanAroundCall")))
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

    private void resetTracing() {
        MDC.clear();
        Tracer.getInstance().unregisterFromThread();
        removeSpanRecorderLifecycleListener();
    }

    private void removeSpanRecorderLifecycleListener() {
        List<SpanLifecycleListener> listeners = new ArrayList<>(Tracer.getInstance().getSpanLifecycleListeners());
        for (SpanLifecycleListener listener : listeners) {
            if (listener instanceof SpanRecorder) {
                Tracer.getInstance().removeSpanLifecycleListener(listener);
            }
        }
    }

    private static class SpanRecorder implements SpanLifecycleListener {

        public final List<Span> completedSpans = new ArrayList<>();

        @Override
        public void spanStarted(Span span) {
        }

        @Override
        public void spanSampled(Span span) {
        }

        @Override
        public void spanCompleted(Span span) {
            completedSpans.add(span);
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
