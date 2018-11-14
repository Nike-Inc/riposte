package com.nike.riposte.server.componenttest;

import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.config.distributedtracing.DefaultRiposteServerSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfigImpl;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;
import com.nike.wingtips.Span;
import com.nike.wingtips.Span.TimestampedAnnotation;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.lifecyclelistener.SpanLifecycleListener;
import com.nike.wingtips.tags.KnownZipkinTags;
import com.nike.wingtips.tags.WingtipsTags;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

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
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.netty.channel.ChannelHandlerContext;
import io.restassured.response.ExtractableResponse;

import static io.restassured.RestAssured.given;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the distributed tracing behavior of the various settings for {@link DistributedTracingConfig} (exposed
 * via {@link ServerConfig#distributedTracingConfig()}), as well as expected tags.
 */
@RunWith(DataProviderRunner.class)
public class VerifyDistributedTracingConfigBehaviorAndTagsComponentTest {

    private static Server proxyServer;
    private static ServerConfig proxyServerConfig;
    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;
    private SpanRecorder spanRecorder;
    private DtConfigAdjustments dtConfigAdjustments;

    private static final AdjustableServerSpanNamingAndTaggingStrategy adjustableTaggingStrategy =
        new AdjustableServerSpanNamingAndTaggingStrategy();

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

        dtConfigAdjustments = new DtConfigAdjustments();
        adjustableTaggingStrategy.config = dtConfigAdjustments;
    }

    @After
    public void afterMethod() {
        resetTracing();
    }

    private enum AnnotationCustomizationScenario {
        ALL_ANNOTATIONS_ON(dtAdjust -> {}),
        WR_START_ONLY(dtAdjust -> {
            disableAllAnnotations(dtAdjust);
            dtAdjust.shouldAddWireReceiveStartAnnotation = true;
        }),
        WR_FINISH_ONLY(dtAdjust -> {
            disableAllAnnotations(dtAdjust);
            dtAdjust.shouldAddWireReceiveFinishAnnotation = true;
        }),
        ENDPOINT_START_ONLY(dtAdjust -> {
            disableAllAnnotations(dtAdjust);
            dtAdjust.shouldAddEndpointStartAnnotation = true;
        }),
        ENDPOINT_FINISH_ONLY(dtAdjust -> {
            disableAllAnnotations(dtAdjust);
            dtAdjust.shouldAddEndpointFinishAnnotation = true;
        }),
        WS_START_ONLY(dtAdjust -> {
            disableAllAnnotations(dtAdjust);
            dtAdjust.shouldAddWireSendStartAnnotation = true;
        }),
        WS_FINISH_ONLY(dtAdjust -> {
            disableAllAnnotations(dtAdjust);
            dtAdjust.shouldAddWireSendFinishAnnotation = true;
        }),
        ERROR_ANNOTATION_ONLY(dtAdjust -> {
            disableAllAnnotations(dtAdjust);
            dtAdjust.shouldAddErrorAnnotationForCaughtException = true;
        });

        public final Consumer<DtConfigAdjustments> scenarioSetup;

        AnnotationCustomizationScenario(
            Consumer<DtConfigAdjustments> scenarioSetup
        ) {
            this.scenarioSetup = scenarioSetup;
        }

        private static void disableAllAnnotations(DtConfigAdjustments dtConfig) {
            dtConfig.shouldAddWireReceiveStartAnnotation = false;
            dtConfig.shouldAddWireReceiveFinishAnnotation = false;
            dtConfig.shouldAddEndpointStartAnnotation = false;
            dtConfig.shouldAddEndpointFinishAnnotation = false;
            dtConfig.shouldAddWireSendStartAnnotation = false;
            dtConfig.shouldAddWireSendFinishAnnotation = false;
            dtConfig.shouldAddErrorAnnotationForCaughtException = false;
        }
    }

    @DataProvider
    public static List<List<Object>> dtConfigAnnotationsAndTaggingScenarioDataProvider() {
        List<List<Object>> scenariosWithoutErrors = Arrays
            .stream(AnnotationCustomizationScenario.values())
            .map(scenario -> Arrays.<Object>asList(scenario, false))
            .collect(Collectors.toList());

        List<List<Object>> scenariosWithErrors = Arrays
            .stream(AnnotationCustomizationScenario.values())
            .map(scenario -> Arrays.<Object>asList(scenario, true))
            .collect(Collectors.toList());

        List<List<Object>> scenarioData = new ArrayList<>(scenariosWithoutErrors);
        scenarioData.addAll(scenariosWithErrors);

        return scenarioData;
    }

    @UseDataProvider("dtConfigAnnotationsAndTaggingScenarioDataProvider")
    @Test
    public void verify_DistributedTracingConfig_annotations_and_tagging_behavior(
        AnnotationCustomizationScenario scenario, boolean triggerDownstreamError
    ) {
        scenario.scenarioSetup.accept(dtConfigAdjustments);

        String pathParam = UUID.randomUUID().toString();
        String queryString = "?foo=bar";
        String path = RouterEndpoint.MATCHING_PATH_BASE + "/" + pathParam;
        String uriWithQueryString = path + queryString;

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(proxyServerConfig.endpointsPort())
                .header(DownstreamEndpoint.TRIGGER_DOWNSTREAM_ERROR_HEADER_KEY, triggerDownstreamError)
                .log().all()
            .when()
                .get(uriWithQueryString)
            .then()
                .log().headers()
                .extract();

        int expectedStatusCode = (triggerDownstreamError) ? 500 : 200;
        assertThat(response.statusCode()).isEqualTo(expectedStatusCode);

        if (!triggerDownstreamError) {
            assertThat(response.asString()).isEqualTo(DownstreamEndpoint.RESPONSE_PAYLOAD);
        }

        verifyTracingInfo(dtConfigAdjustments, pathParam, queryString, expectedStatusCode);
    }

    private void verifyTracingInfo(
        DtConfigAdjustments dtConfig,
        String pathParam,
        String queryString,
        int expectedStatusCode
    ) {
        waitUntilSpanRecorderHasExpectedNumSpans(3);
        assertThat(spanRecorder.completedSpans).hasSize(3);

        Span proxyServerOverallSpan = findServerOverallSpan("GET " + RouterEndpoint.MATCHING_PATH);
        Span downstreamServerOverallSpan = findServerOverallSpan("GET " + DownstreamEndpoint.MATCHING_PATH);

        boolean downstreamErrorOccurred = (expectedStatusCode == 500);
        String expectedStatusCodeStr = String.valueOf(expectedStatusCode);

        String proxyPath = RouterEndpoint.MATCHING_PATH_BASE + "/" + pathParam;
        String expectedProxyErrorTag = (downstreamErrorOccurred) ? expectedStatusCodeStr : null;
        verifyServerOverallSpanAnnotations(
            dtConfig, proxyServerOverallSpan, true, false
        );
        verifyServerOverallSpanTags(
            dtConfig, proxyServerOverallSpan, "GET", proxyPath, proxyPath + queryString,
            RouterEndpoint.MATCHING_PATH, expectedStatusCodeStr, "riposte.server", expectedProxyErrorTag
        );

        String downstreamPath = DownstreamEndpoint.MATCHING_PATH_BASE + "/" + pathParam;
        String expectedDownstreamErrorTag = (downstreamErrorOccurred)
                                            ? "java.lang.RuntimeException: intentional downstream exception"
                                            : null;
        verifyServerOverallSpanAnnotations(
            dtConfig, downstreamServerOverallSpan, false, downstreamErrorOccurred
        );
        verifyServerOverallSpanTags(
            dtConfig, downstreamServerOverallSpan, "GET", downstreamPath,
            downstreamPath + queryString, DownstreamEndpoint.MATCHING_PATH, expectedStatusCodeStr,
            "riposte.server", expectedDownstreamErrorTag
        );
    }

    private Span findServerOverallSpan(String expectedSpanName) {
        return spanRecorder.completedSpans
            .stream()
            .filter(
                s -> s.getSpanName().equals(expectedSpanName)
                     && "riposte.server".equals(s.getTags().get(WingtipsTags.SPAN_HANDLER))
            )
            .findFirst()
            .orElseThrow(
                () -> new RuntimeException(
                    "Unable to find server overall request span with expected span name: " + expectedSpanName
                )
            );
    }

    private void verifyServerOverallSpanAnnotations(
        DtConfigAdjustments dtConfig,
        Span span,
        boolean endpointAnnotationsAlwaysMissing,
        boolean downstreamErrorOccurred
    ) {
        AtomicInteger expectedTotalNumAnnotations = new AtomicInteger(0);
        AtomicLong minAnnotationTimestamp = new AtomicLong(span.getSpanStartTimeEpochMicros());

        verifySpanAnnotation(
            span,
            dtConfig.wireReceiveStartAnnotationName,
            dtConfig.shouldAddWireReceiveStartAnnotation,
            expectedTotalNumAnnotations,
            minAnnotationTimestamp
        );

        verifySpanAnnotation(
            span,
            dtConfig.wireReceiveFinishAnnotationName,
            dtConfig.shouldAddWireReceiveFinishAnnotation,
            expectedTotalNumAnnotations,
            minAnnotationTimestamp
        );

        boolean expectEndpointStart = !endpointAnnotationsAlwaysMissing
                                      && dtConfig.shouldAddEndpointStartAnnotation;
        boolean expectEndpointFinish = !endpointAnnotationsAlwaysMissing
                                       && dtConfig.shouldAddEndpointFinishAnnotation;
        verifySpanAnnotation(
            span,
            dtConfig.endpointStartAnnotationName,
            expectEndpointStart,
            expectedTotalNumAnnotations,
            minAnnotationTimestamp
        );

        verifySpanAnnotation(
            span,
            dtConfig.endpointFinishAnnotationName,
            expectEndpointFinish,
            expectedTotalNumAnnotations,
            minAnnotationTimestamp
        );

        boolean expectErrorAnnotation = downstreamErrorOccurred && dtConfig.shouldAddErrorAnnotationForCaughtException;

        verifySpanAnnotation(
            span,
            dtConfig.errorAnnotationName,
            expectErrorAnnotation,
            expectedTotalNumAnnotations,
            minAnnotationTimestamp
        );

        verifySpanAnnotation(
            span,
            dtConfigAdjustments.wireSendStartAnnotationName,
            dtConfigAdjustments.shouldAddWireSendStartAnnotation,
            expectedTotalNumAnnotations,
            minAnnotationTimestamp
        );

        verifySpanAnnotation(
            span,
            dtConfig.wireSendFinishAnnotationName,
            dtConfig.shouldAddWireSendFinishAnnotation,
            expectedTotalNumAnnotations,
            minAnnotationTimestamp
        );

        assertThat(span.getTimestampedAnnotations()).hasSize(expectedTotalNumAnnotations.get());
    }

    private void verifySpanAnnotation(
        Span span, String annotationName, boolean annotationShouldExist, AtomicInteger expectedNumAnnotationsCounter,
        AtomicLong minAnnotationTimestamp
    ) {
        TimestampedAnnotation annotation = findSpanAnnotation(span, annotationName);
        if (annotationShouldExist) {
            assertThat(annotation).isNotNull();
            expectedNumAnnotationsCounter.incrementAndGet();
            assertThat(annotation.getTimestampEpochMicros()).isGreaterThanOrEqualTo(minAnnotationTimestamp.get());
            minAnnotationTimestamp.set(annotation.getTimestampEpochMicros());
        }
        else {
            assertThat(annotation).isNull();
        }
    }

    private TimestampedAnnotation findSpanAnnotation(Span span, String annotationName) {
        return span.getTimestampedAnnotations().stream()
                   .filter(a -> annotationName.equalsIgnoreCase(a.getValue()))
                   .findFirst()
                   .orElse(null);
    }

    private void verifyServerOverallSpanTags(
        DtConfigAdjustments dtConfig,
        Span span,
        String expectedHttpMethod,
        String expectedPath,
        String expectedUrl,
        String expectedRoute,
        String expectedStatusCode,
        String expectedSpanHandler,
        String expectedErrorTag
    ) {
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_METHOD)).isEqualTo(expectedHttpMethod);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_PATH)).isEqualTo(expectedPath);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_URL)).isEqualTo(expectedUrl);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_ROUTE)).isEqualTo(expectedRoute);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_STATUS_CODE)).isEqualTo(expectedStatusCode);
        assertThat(span.getTags().get(KnownZipkinTags.ERROR)).isEqualTo(expectedErrorTag);
        assertThat(span.getTags().get(WingtipsTags.SPAN_HANDLER)).isEqualTo(expectedSpanHandler);

        int expectedTotalNumTags = (expectedErrorTag == null) ? 6 : 7;
        assertThat(span.getTags()).hasSize(expectedTotalNumTags);
    }

    static class DownstreamEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH_BASE = "/downstreamEndpoint";
        public static final String MATCHING_PATH = MATCHING_PATH_BASE + "/{fooPathParam}";
        public static final String RESPONSE_PAYLOAD = "downstream-endpoint-" + UUID.randomUUID().toString();
        public static final String TRIGGER_DOWNSTREAM_ERROR_HEADER_KEY = "triggerDownstreamError";

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(
            RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx
        ) {
            if ("true".equals(request.getHeaders().get(TRIGGER_DOWNSTREAM_ERROR_HEADER_KEY))) {
                throw new RuntimeException("intentional downstream exception");
            }

            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class RouterEndpoint extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH_BASE = "/proxyEndpoint";
        public static final String MATCHING_PATH = MATCHING_PATH_BASE + "/{fooPathParam}";
        private final int downstreamPort;

        public RouterEndpoint(int downstreamPort) {
            this.downstreamPort = downstreamPort;
        }

        @Override
        public CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(
            RequestInfo<?> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx
        ) {
            String fooPathParam = request.getPathParam("fooPathParam");
            String downstreamUri = DownstreamEndpoint.MATCHING_PATH_BASE + "/" + fooPathParam;

            DownstreamRequestFirstChunkInfo target = new DownstreamRequestFirstChunkInfo(
                "127.0.0.1", downstreamPort, false,
                generateSimplePassthroughRequest(
                    request, downstreamUri, request.getMethod(), ctx
                )
            );

            return CompletableFuture.completedFuture(target);
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class DownstreamServerTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;
        private final DistributedTracingConfig<Span> dtConfig = new DistributedTracingConfigImpl<>(
            adjustableTaggingStrategy, Span.class
        );

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
        public DistributedTracingConfig<?> distributedTracingConfig() {
            return dtConfig;
        }
    }

    public static class ProxyTestingTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;
        private final DistributedTracingConfig<Span> dtConfig = new DistributedTracingConfigImpl<>(
            adjustableTaggingStrategy, Span.class
        );

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
        public DistributedTracingConfig<?> distributedTracingConfig() {
            return dtConfig;
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

    private static class DtConfigAdjustments {
        public boolean shouldAddWireReceiveStartAnnotation = true;
        public String wireReceiveStartAnnotationName = "wr.start." + UUID.randomUUID().toString();

        public boolean shouldAddWireReceiveFinishAnnotation = true;
        public String wireReceiveFinishAnnotationName = "wr.finish." + UUID.randomUUID().toString();

        public boolean shouldAddWireSendStartAnnotation = true;
        public String wireSendStartAnnotationName = "ws.start." + UUID.randomUUID().toString();

        public boolean shouldAddWireSendFinishAnnotation = true;
        public String wireSendFinishAnnotationName = "ws.finish." + UUID.randomUUID().toString();

        public boolean shouldAddEndpointStartAnnotation = true;
        public String endpointStartAnnotationName = "endpoint.start." + UUID.randomUUID().toString();

        public boolean shouldAddEndpointFinishAnnotation = true;
        public String endpointFinishAnnotationName = "endpoint.finish." + UUID.randomUUID().toString();

        public boolean shouldAddErrorAnnotationForCaughtException = true;
        public String errorAnnotationName = "error." + UUID.randomUUID().toString();
    }

    private static class AdjustableServerSpanNamingAndTaggingStrategy
        extends DefaultRiposteServerSpanNamingAndTaggingStrategy {

        public DtConfigAdjustments config = new DtConfigAdjustments();

        @Override
        public boolean shouldAddWireReceiveStartAnnotation() { return config.shouldAddWireReceiveStartAnnotation; }

        @Override
        public @NotNull String wireReceiveStartAnnotationName() { return config.wireReceiveStartAnnotationName; }

        @Override
        public boolean shouldAddWireReceiveFinishAnnotation() { return config.shouldAddWireReceiveFinishAnnotation; }

        @Override
        public @NotNull String wireReceiveFinishAnnotationName() { return config.wireReceiveFinishAnnotationName; }

        @Override
        public boolean shouldAddWireSendStartAnnotation() { return config.shouldAddWireSendStartAnnotation; }

        @Override
        public @NotNull String wireSendStartAnnotationName() { return config.wireSendStartAnnotationName; }

        @Override
        public boolean shouldAddWireSendFinishAnnotation() { return config.shouldAddWireSendFinishAnnotation; }

        @Override
        public @NotNull String wireSendFinishAnnotationName() { return config.wireSendFinishAnnotationName; }

        @Override
        public boolean shouldAddEndpointStartAnnotation() { return config.shouldAddEndpointStartAnnotation; }

        @Override
        public @NotNull String endpointStartAnnotationName() { return config.endpointStartAnnotationName; }

        @Override
        public boolean shouldAddEndpointFinishAnnotation() { return config.shouldAddEndpointFinishAnnotation; }

        @Override
        public @NotNull String endpointFinishAnnotationName() { return config.endpointFinishAnnotationName; }

        @Override
        public boolean shouldAddErrorAnnotationForCaughtException(
            @NotNull ResponseInfo<?> responseInfo, @NotNull Throwable error
        ) { return config.shouldAddErrorAnnotationForCaughtException; }

        @Override
        public @NotNull String errorAnnotationName(
            @NotNull ResponseInfo<?> responseInfo, @NotNull Throwable error
        ) { return config.errorAnnotationName; }
    }
}
