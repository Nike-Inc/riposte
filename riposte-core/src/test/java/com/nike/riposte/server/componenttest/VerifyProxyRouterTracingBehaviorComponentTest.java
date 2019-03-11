package com.nike.riposte.server.componenttest;

import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.ProxyRouterEndpoint.DownstreamRequestFirstChunkInfo;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;
import com.nike.wingtips.Span;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.http.HttpRequestTracingUtils;
import com.nike.wingtips.lifecyclelistener.SpanLifecycleListener;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.jetbrains.annotations.NotNull;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.restassured.response.ExtractableResponse;

import static com.nike.riposte.server.componenttest.VerifyProxyRouterTracingBehaviorComponentTest.DownstreamEndpoint.RECEIVED_PARENT_SPAN_ID_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyProxyRouterTracingBehaviorComponentTest.DownstreamEndpoint.RECEIVED_SAMPLED_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyProxyRouterTracingBehaviorComponentTest.DownstreamEndpoint.RECEIVED_SPAN_ID_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyProxyRouterTracingBehaviorComponentTest.DownstreamEndpoint.RECEIVED_TRACE_ID_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyProxyRouterTracingBehaviorComponentTest.RouterEndpoint.PERFORM_SUBSPAN_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyProxyRouterTracingBehaviorComponentTest.RouterEndpoint.SET_TRACING_HEADERS_HEADER_KEY;
import static com.nike.wingtips.http.HttpRequestTracingUtils.convertSampleableBooleanToExpectedB3Value;
import static io.restassured.RestAssured.given;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the distributed tracing behavior of {@link ProxyRouterEndpoint} calls, including the {@link
 * DownstreamRequestFirstChunkInfo#withAddTracingHeadersToDownstreamCall(boolean)} and {@link
 * DownstreamRequestFirstChunkInfo#withPerformSubSpanAroundDownstreamCall(boolean)} options.
 */
@RunWith(DataProviderRunner.class)
public class VerifyProxyRouterTracingBehaviorComponentTest {

    private static Server proxyServer;
    private static ServerConfig proxyServerConfig;
    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;
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
    }

    @Before
    public void beforeMethod() {
        resetTracing();
        spanRecorder = new SpanRecorder();
        Tracer.getInstance().addSpanLifecycleListener(spanRecorder);
    }

    @After
    public void afterMethod() {
        resetTracing();
    }

    private enum TracingBehaviorScenario {
        HEADERS_ON_SUBSPAN_ON(true, true),
        HEADERS_OFF_SUBSPAN_ON(false, true),
        HEADERS_ON_SUBSPAN_OFF(true, false),
        HEADERS_OFF_SUBSPAN_OFF(false, false);

        public final boolean tracingHeadersOn;
        public final boolean subspanOn;

        TracingBehaviorScenario(boolean tracingHeadersOn, boolean subspanOn) {
            this.tracingHeadersOn = tracingHeadersOn;
            this.subspanOn = subspanOn;
        }
    }

    @DataProvider(value = {
        "HEADERS_ON_SUBSPAN_ON",
        "HEADERS_OFF_SUBSPAN_ON",
        "HEADERS_ON_SUBSPAN_OFF",
        "HEADERS_OFF_SUBSPAN_OFF"
    })
    @Test
    public void proxy_endpoint_tracing_behavior_should_work_as_desired_when_orig_request_does_not_have_tracing_headers(
        TracingBehaviorScenario scenario
    ) {
        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(proxyServerConfig.endpointsPort())
                .basePath(RouterEndpoint.MATCHING_PATH)
                .header(SET_TRACING_HEADERS_HEADER_KEY, scenario.tracingHeadersOn)
                .header(PERFORM_SUBSPAN_HEADER_KEY, scenario.subspanOn)
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(DownstreamEndpoint.RESPONSE_PAYLOAD);

        // Verify that the proxy honored the subspan option, and get a handle on the span that surrounded the proxy
        //      downstream call.
        Span spanForDownstreamCall = verifyCompletedSpansAndReturnSpanForDownstreamCall(scenario.subspanOn);

        if (scenario.tracingHeadersOn) {
            // The downstream endpoint should have received tracing headers based on the span surrounding the proxy
            //      downstream call.
            verifyExpectedTracingHeadersReceivedDownstream(response, spanForDownstreamCall);
        }
        else {
            // Proxy had the "set tracing headers" option off, and we didn't send any in our original request, so
            //      the downstream endpoint should not have received *any* tracing headers.
            verifyExpectedTracingHeadersReceivedDownstream(response, null, null, null, null);
        }
    }

    private Span verifyCompletedSpansAndReturnSpanForDownstreamCall(boolean subspanOn) {
        if (subspanOn) {
            // Should be 3 completed spans - one for proxy overall request, one for the subspan around the downstream
            //      call, and one for downstream overall request. Completed in reverse order.
            waitUntilSpanRecorderHasExpectedNumSpans(3);
            assertThat(spanRecorder.completedSpans).hasSize(3);
            assertThat(spanRecorder.completedSpans.get(0).getSpanName()).isEqualTo("GET " + DownstreamEndpoint.MATCHING_PATH);
            assertThat(spanRecorder.completedSpans.get(1).getSpanName()).isEqualTo("GET");
            assertThat(spanRecorder.completedSpans.get(2).getSpanName()).isEqualTo("GET " + RouterEndpoint.MATCHING_PATH);
        }
        else {
            // Should be 2 completed spans - one for proxy overall request, and one for downstream overall request.
            //      Completed in reverse order.
            waitUntilSpanRecorderHasExpectedNumSpans(2);
            assertThat(spanRecorder.completedSpans).hasSize(2);
            assertThat(spanRecorder.completedSpans.get(0).getSpanName()).isEqualTo("GET " + DownstreamEndpoint.MATCHING_PATH);
            assertThat(spanRecorder.completedSpans.get(1).getSpanName()).isEqualTo("GET " + RouterEndpoint.MATCHING_PATH);
        }

        // In either case, the span for the downstream call lives in index 1.
        return spanRecorder.completedSpans.get(1);
    }

    private void verifyExpectedTracingHeadersReceivedDownstream(
        ExtractableResponse response, Span expectedSpan
    ) {
        verifyExpectedTracingHeadersReceivedDownstream(
            response, expectedSpan.getTraceId(), expectedSpan.getSpanId(), expectedSpan.getParentSpanId(),
            convertSampleableBooleanToExpectedB3Value(expectedSpan.isSampleable())
        );
    }

    private void verifyExpectedTracingHeadersReceivedDownstream(
        ExtractableResponse response, String expectedTraceId, String expectedSpanId, String expectedParentSpanId,
        String expectedSampled
    ) {
        assertThat(response.header(RECEIVED_TRACE_ID_HEADER_KEY)).isEqualTo(String.valueOf(expectedTraceId));
        assertThat(response.header(RECEIVED_SPAN_ID_HEADER_KEY)).isEqualTo(String.valueOf(expectedSpanId));
        assertThat(response.header(RECEIVED_PARENT_SPAN_ID_HEADER_KEY)).isEqualTo(String.valueOf(expectedParentSpanId));
        assertThat(response.header(RECEIVED_SAMPLED_HEADER_KEY)).isEqualTo(String.valueOf(expectedSampled));
    }

    @DataProvider(value = {
        "HEADERS_ON_SUBSPAN_ON      |   true",
        "HEADERS_ON_SUBSPAN_ON      |   false",
        "HEADERS_OFF_SUBSPAN_ON     |   true",
        "HEADERS_OFF_SUBSPAN_ON     |   false",
        "HEADERS_ON_SUBSPAN_OFF     |   true",
        "HEADERS_ON_SUBSPAN_OFF     |   false",
        "HEADERS_OFF_SUBSPAN_OFF    |   true",
        "HEADERS_OFF_SUBSPAN_OFF    |   false"
    }, splitBy = "\\|")
    @Test
    public void proxy_endpoint_tracing_behavior_should_work_as_desired_when_orig_request_does_have_tracing_headers(
        TracingBehaviorScenario scenario, boolean sampled
    ) {
        Span origCallSpan = Span.newBuilder("origCall", Span.SpanPurpose.CLIENT)
                                .withSampleable(sampled)
                                .build();
        Map<String, String> tracingHeaders = new HashMap<>();
        HttpRequestTracingUtils.propagateTracingHeaders(tracingHeaders::put, origCallSpan);

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(proxyServerConfig.endpointsPort())
                .basePath(RouterEndpoint.MATCHING_PATH)
                .header(SET_TRACING_HEADERS_HEADER_KEY, scenario.tracingHeadersOn)
                .header(PERFORM_SUBSPAN_HEADER_KEY, scenario.subspanOn)
                .headers(tracingHeaders)
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(DownstreamEndpoint.RESPONSE_PAYLOAD);

        // Verify that the proxy honored the subspan option, and get a handle on the span that surrounded the proxy
        //      downstream call.
        Span spanForDownstreamCall = verifyCompletedSpansAndReturnSpanForDownstreamCall(scenario.subspanOn);

        // Sanity checking - spanForDownstreamCall should be a child of origCallSpan (same trace ID and sampleable
        //      value but different span IDs.
        assertThat(spanForDownstreamCall.getTraceId()).isEqualTo(origCallSpan.getTraceId());
        assertThat(spanForDownstreamCall.isSampleable()).isEqualTo(origCallSpan.isSampleable());
        assertThat(spanForDownstreamCall.getSpanId()).isNotEqualTo(origCallSpan.getSpanId());

        if (scenario.tracingHeadersOn) {
            // The downstream endpoint should have received tracing headers based on the span surrounding the proxy
            //      downstream call.
            verifyExpectedTracingHeadersReceivedDownstream(response, spanForDownstreamCall);
        }
        else {
            // The ProxyRouterEndpoint is configured to *not* set tracing headers, so we should see the values
            //      from origCallSpan show up on the other end since we sent them with the original call - the proxy
            //      should not have messed with them.
            verifyExpectedTracingHeadersReceivedDownstream(response, origCallSpan);
        }
    }

    static class DownstreamEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/downstreamEndpoint";
        public static final String RESPONSE_PAYLOAD = "downstream-endpoint-" + UUID.randomUUID().toString();
        public static final String RECEIVED_TRACE_ID_HEADER_KEY = "received-traceid";
        public static final String RECEIVED_SPAN_ID_HEADER_KEY = "received-spanid";
        public static final String RECEIVED_PARENT_SPAN_ID_HEADER_KEY = "received-parent-spanid";
        public static final String RECEIVED_SAMPLED_HEADER_KEY = "received-sampled";

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<Void> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            HttpHeaders reqHeaders = request.getHeaders();
            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(RESPONSE_PAYLOAD)
                            .withHeaders(
                                new DefaultHttpHeaders()
                                    .set(RECEIVED_TRACE_ID_HEADER_KEY, String.valueOf(reqHeaders.get(TraceHeaders.TRACE_ID)))
                                    .set(RECEIVED_SPAN_ID_HEADER_KEY, String.valueOf(reqHeaders.get(TraceHeaders.SPAN_ID)))
                                    .set(RECEIVED_PARENT_SPAN_ID_HEADER_KEY, String.valueOf(reqHeaders.get(TraceHeaders.PARENT_SPAN_ID)))
                                    .set(RECEIVED_SAMPLED_HEADER_KEY, String.valueOf(reqHeaders.get(TraceHeaders.TRACE_SAMPLED)))
                            )
                            .build()
            );
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class RouterEndpoint extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/proxyEndpoint";
        public static final String SET_TRACING_HEADERS_HEADER_KEY = "X-Test-SendTraceHeaders";
        public static final String PERFORM_SUBSPAN_HEADER_KEY = "X-Test-PerformSubSpanAroundCall";
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
            DownstreamRequestFirstChunkInfo target = new DownstreamRequestFirstChunkInfo(
                "127.0.0.1", downstreamPort, false,
                generateSimplePassthroughRequest(request, DownstreamEndpoint.MATCHING_PATH, request.getMethod(), ctx)
            );

            String setTracingHeadersStrValue = request.getHeaders().get(SET_TRACING_HEADERS_HEADER_KEY);
            String performSubspanStrValue = request.getHeaders().get(PERFORM_SUBSPAN_HEADER_KEY);

            if (setTracingHeadersStrValue != null) {
                target.withAddTracingHeadersToDownstreamCall(Boolean.parseBoolean(setTracingHeadersStrValue));
            }

            if (performSubspanStrValue != null) {
                target.withPerformSubSpanAroundDownstreamCall(Boolean.parseBoolean(performSubspanStrValue));
            }

            return CompletableFuture.completedFuture(target);
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

    private void waitUntilSpanRecorderHasExpectedNumSpans(int expectedNumSpans) {
        long timeoutMillis = 5000;
        long startTimeMillis = System.currentTimeMillis();
        while (spanRecorder.completedSpans.size() < expectedNumSpans) {
            try {
                Thread.sleep(10);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            long timeSinceStart = System.currentTimeMillis() - startTimeMillis;
            if (timeSinceStart > timeoutMillis) {
                throw new RuntimeException(
                    "spanRecorder did not have the expected number of spans after waiting "
                    + timeoutMillis + " milliseconds"
                );
            }
        }

        // Before we return we need to sort completedSpans by start time (in reverse order to mimic what normally
        //      happens with spans where the last-created is first-completed). We need to do this sort because running
        //      these tests on travis CI can get weird and we can get them completing and arriving in the list in
        //      out-of-expected-order state.
        spanRecorder.completedSpans.sort(Comparator.comparingLong(Span::getSpanStartTimeNanos).reversed());
    }
}
