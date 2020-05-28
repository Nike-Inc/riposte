package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.sample.SampleCoreApiError;
import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.channelpipeline.message.ChunkedOutboundMessage;
import com.nike.riposte.server.channelpipeline.message.OutboundMessageSendHeadersChunkFromResponseInfo;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.config.distributedtracing.DefaultRiposteDistributedTracingConfigImpl;
import com.nike.riposte.server.handler.ResponseSenderHandler;
import com.nike.riposte.server.hooks.PipelineCreateHook;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.http.impl.SimpleProxyRouterEndpoint;
import com.nike.riposte.server.logging.AccessLogger;
import com.nike.riposte.server.metrics.ServerMetricsEvent;
import com.nike.riposte.server.testutils.ComponentTestUtils.SpanRecorder;
import com.nike.riposte.util.Matcher;
import com.nike.wingtips.Span;
import com.nike.wingtips.Span.SpanPurpose;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.tags.KnownZipkinTags;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.apache.http.ConnectionClosedException;
import org.apache.http.NoHttpResponseException;
import org.apache.http.impl.client.DefaultHttpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpResponse;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.internal.http.ResponseParseException;
import io.restassured.response.ExtractableResponse;

import static com.nike.riposte.server.channelpipeline.HttpChannelInitializer.RESPONSE_SENDER_HANDLER_NAME;
import static com.nike.riposte.server.metrics.ServerMetricsEvent.REQUEST_RECEIVED;
import static com.nike.riposte.server.metrics.ServerMetricsEvent.RESPONSE_SENT;
import static com.nike.riposte.server.metrics.ServerMetricsEvent.RESPONSE_WRITE_FAILED;
import static com.nike.riposte.server.testutils.ComponentTestUtils.findFreePort;
import static com.nike.riposte.server.testutils.ComponentTestUtils.generatePayload;
import static com.nike.riposte.server.testutils.ComponentTestUtils.verifyErrorReceived;
import static com.nike.riposte.server.testutils.ComponentTestUtils.waitUntilCollectionHasSize;
import static com.nike.riposte.server.testutils.ComponentTestUtils.waitUntilSpanRecorderHasExpectedNumSpans;
import static io.restassured.RestAssured.given;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Verifies the behavior of Riposte when the {@link com.nike.riposte.server.http.ResponseSender} fails with an
 * exception.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class VerifyResponseSenderExceptionBehaviorComponentTest {

    private static int proxyPort;
    private static Server proxyServer;
    private static ProxyServerConfig proxyServerConfig;
    private static int downstreamPort;
    private static Server downstreamServer;
    private static DownstreamServerConfig downstreamServerConfig;

    private static ExplodingResponseSender proxyResponseSender;
    private static ExplodingResponseSender downstreamResponseSender;

    private static TrackingAccessLogger proxyAccessLogger;
    private static TrackingAccessLogger downstreamAccessLogger;

    private static TrackingMetricsListener proxyMetrics;
    private static TrackingMetricsListener downstreamMetrics;

    private static SpanRecorder spanRecorder;

    @BeforeClass
    public static void beforeClass() throws Exception {
        downstreamPort = findFreePort();
        downstreamServerConfig = new DownstreamServerConfig(downstreamPort);
        downstreamServer = new Server(downstreamServerConfig);
        downstreamServer.startup();
        downstreamResponseSender = downstreamServerConfig.explodingResponseSender;
        downstreamAccessLogger = downstreamServerConfig.trackingAccessLogger;
        downstreamMetrics = downstreamServerConfig.trackingMetricsListener;

        proxyPort = findFreePort();
        proxyServerConfig = new ProxyServerConfig(proxyPort, downstreamPort);
        proxyServer = new Server(proxyServerConfig);
        proxyServer.startup();
        proxyResponseSender = proxyServerConfig.explodingResponseSender;
        proxyAccessLogger = proxyServerConfig.trackingAccessLogger;
        proxyMetrics = proxyServerConfig.trackingMetricsListener;
    }

    @AfterClass
    public static void afterClass() throws Exception {
        proxyServer.shutdown();
        downstreamServer.shutdown();
    }

    @Before
    public void beforeMethod() {
        proxyResponseSender.resetFailures();
        downstreamResponseSender.resetFailures();

        proxyAccessLogger.accessLogs.clear();
        downstreamAccessLogger.accessLogs.clear();

        proxyMetrics.metricsEvents.clear();
        downstreamMetrics.metricsEvents.clear();

        resetTracing();
    }

    @After
    public void afterMethod() {
        resetTracing();
    }

    private void resetTracing() {
        MDC.clear();
        Tracer.getInstance().unregisterFromThread();
        Tracer.getInstance().removeAllSpanLifecycleListeners();
        spanRecorder = new SpanRecorder();
        Tracer.getInstance().addSpanLifecycleListener(spanRecorder);
    }

    private RestAssuredConfig noRetryRestAssuredConfig() {
        return RestAssuredConfig
            .config()
            .httpClient(HttpClientConfig.httpClientConfig().httpClientFactory(
                () -> {
                    DefaultHttpClient result = new DefaultHttpClient();
                    result.setHttpRequestRetryHandler((exception, executionCount, context) -> false);
                    return result;
                })
            );
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void regular_endpoint_call_with_initial_response_sender_failure_causes_last_ditch_error_response_to_be_returned(
        boolean sendBigPayload
    ) throws IOException {
        // given
        downstreamResponseSender.failOnFirstFullResponse = true;

        int payloadSize = (sendBigPayload) ? 12_000 : 0;
        String payload = generatePayload(payloadSize);

        // when
        ExtractableResponse<?> response =
            given()
                .config(noRetryRestAssuredConfig())
                .baseUri("http://127.0.0.1")
                .port(downstreamPort)
                .basePath(DownstreamEndpoint.DS_MATCHING_PATH)
                .body(payload)
                .log().headers()
            .when()
                .post()
            .then()
                .log().status().log().headers()
                .extract();

        // then
        // Response payload and headers should match the error we expect.
        verifyErrorReceived(response.asString(), response.statusCode(), SampleCoreApiError.GENERIC_SERVICE_ERROR);

        String errorId = response.header("error_uid");
        assertThat(errorId).isNotBlank();
        assertThat(response.asString()).contains("\"error_id\":\"" + errorId + "\"");

        String traceIdResponseHeader = response.header(TraceHeaders.TRACE_ID);
        assertThat(traceIdResponseHeader).isNotBlank();

        // We should find a completed server span representing the request, with the expected info.
        waitUntilSpanRecorderHasExpectedNumSpans(spanRecorder, 1);
        assertThat(spanRecorder.completedSpans).hasSize(1);
        verifySpanHasExpectedInfo(
            spanRecorder.completedSpans.get(0),
            traceIdResponseHeader,
            SpanPurpose.SERVER,
            "POST " + DownstreamEndpoint.DS_MATCHING_PATH,
            500,
            "Intentional ResponseSender failure - full responses seen: 1"
        );

        // We should find a completed access log for the request, with the expected info.
        waitUntilCollectionHasSize(downstreamAccessLogger.accessLogs, 1, 1000, "downstreamAccessLogger");
        assertThat(downstreamAccessLogger.accessLogs).hasSize(1);
        verifyAccessLogHasExpectedInfo(
            downstreamAccessLogger.accessLogs.get(0),
            "POST " + DownstreamEndpoint.DS_MATCHING_PATH,
            "500",
            errorId,
            traceIdResponseHeader
        );

        // Metrics for the request should have been finalized.
        waitUntilCollectionHasSize(downstreamMetrics.metricsEvents, 2, 1000, "downstreamMetrics");
        assertThat(downstreamMetrics.metricsEvents).isEqualTo(Arrays.asList(REQUEST_RECEIVED, RESPONSE_SENT));
    }

    private void verifySpanHasExpectedInfo(
        Span span,
        String expectedTraceId,
        SpanPurpose expectedSpanPurpose,
        String expectedSpanName,
        int expectedStatusCodeTagValue,
        String expectedErrorTagValue
    ) {
        if (expectedTraceId != null) {
            assertThat(span.getTraceId()).isEqualTo(expectedTraceId);
        }
        assertThat(span.getSpanPurpose()).isEqualTo(expectedSpanPurpose);
        assertThat(span.getSpanName()).isEqualTo(expectedSpanName);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_STATUS_CODE))
            .isEqualTo(String.valueOf(expectedStatusCodeTagValue));
        assertThat(span.getTags().get(KnownZipkinTags.ERROR)).isEqualTo(expectedErrorTagValue);
    }

    private void verifyAccessLogHasExpectedInfo(
        String accessLog,
        String expectedEndpoint,
        String expectedStatusCode,
        String expectedErrorId,
        String expectedTraceId
    ) {
        assertThat(accessLog).contains("\"" + expectedEndpoint + " HTTP/1.1\" " + expectedStatusCode);
        if (expectedErrorId != null) {
            assertThat(accessLog).contains("error_uid-Res=" + expectedErrorId);
        }
        if (expectedTraceId != null) {
            assertThat(accessLog).contains("X-B3-TraceId-Res=" + expectedTraceId);
        }
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void regular_endpoint_call_with_continual_response_sender_failures_causes_connection_to_be_closed(
        boolean sendBigPayload
    ) {
        // given
        downstreamResponseSender.failOnAnyFullResponse = true;

        int payloadSize = (sendBigPayload) ? 12_000 : 0;
        String payload = generatePayload(payloadSize);

        // when
        Throwable ex = catchThrowable(
            () -> given()
                .config(noRetryRestAssuredConfig())
                .baseUri("http://127.0.0.1")
                .port(downstreamPort)
                .basePath(DownstreamEndpoint.DS_MATCHING_PATH)
                .body(payload)
                .log().headers()
            .when()
                .post()
            .then()
                .log().status().log().headers()
                .extract()
        );

        // then
        // The connection should have been closed without a response.
        verifyConnectionClosedException(ex);

        // We should find a completed server span representing the request, with the expected info.
        waitUntilSpanRecorderHasExpectedNumSpans(spanRecorder, 1);
        assertThat(spanRecorder.completedSpans).hasSize(1);
        verifySpanHasExpectedInfo(
            spanRecorder.completedSpans.get(0),
            null,
            SpanPurpose.SERVER,
            "POST " + DownstreamEndpoint.DS_MATCHING_PATH,
            500,
            "Intentional ResponseSender failure - full responses seen: 1"
        );

        // We should find a completed access log for the request, with the expected info.
        assertThat(downstreamAccessLogger.accessLogs).hasSize(1);
        waitUntilCollectionHasSize(downstreamAccessLogger.accessLogs, 1, 1000, "downstreamAccessLogger");
        verifyAccessLogHasExpectedInfo(
            downstreamAccessLogger.accessLogs.get(0),
            "POST " + DownstreamEndpoint.DS_MATCHING_PATH,
            "500",
            null,
            null
        );

        // Metrics for the request should have been finalized.
        waitUntilCollectionHasSize(downstreamMetrics.metricsEvents, 2, 1000, "downstreamMetrics");
        assertThat(downstreamMetrics.metricsEvents).isEqualTo(Arrays.asList(REQUEST_RECEIVED, RESPONSE_WRITE_FAILED));
    }

    private void verifyConnectionClosedException(Throwable ex) {
        boolean isNoHttpResponseEx = (ex instanceof NoHttpResponseException);
        boolean isSocketExForConnectionReset =
            (ex instanceof SocketException) && (ex.getMessage().equals("Connection reset"));

        assertThat(isNoHttpResponseEx || isSocketExForConnectionReset)
            .withFailMessage("Expected a NoHttpResponseException, or a SocketException with message "
                             + "'Connection reset', but instead found: " + ex.toString()
            )
            .isTrue();
    }

    // Doing calls that result in a 404 is a completely different codepath since they doesn't hit any endpoint,
    //      which is why we're testing them explicitly.
    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void a_404_call_with_initial_response_sender_failure_causes_last_ditch_error_response_to_be_returned(
        boolean sendBigPayload
    ) throws IOException {
        // given
        downstreamResponseSender.failOnFirstFullResponse = true;

        int payloadSize = (sendBigPayload) ? 12_000 : 0;
        String payload = generatePayload(payloadSize);

        String bad404Path = "/does/not/exist/" + UUID.randomUUID().toString();

        // when
        ExtractableResponse<?> response =
            given()
                .config(noRetryRestAssuredConfig())
                .baseUri("http://127.0.0.1")
                .port(downstreamPort)
                .basePath(bad404Path)
                .body(payload)
                .log().headers()
            .when()
                .post()
            .then()
                .log().status().log().headers()
                .extract();

        // then
        // Response payload and headers should match the error we expect.
        verifyErrorReceived(response.asString(), response.statusCode(), SampleCoreApiError.GENERIC_SERVICE_ERROR);

        String errorId = response.header("error_uid");
        assertThat(errorId).isNotBlank();
        assertThat(response.asString()).contains("\"error_id\":\"" + errorId + "\"");

        String traceIdResponseHeader = response.header(TraceHeaders.TRACE_ID);
        assertThat(traceIdResponseHeader).isNotBlank();

        // We should find a completed server span representing the request, with the expected info.
        waitUntilSpanRecorderHasExpectedNumSpans(spanRecorder, 1);
        assertThat(spanRecorder.completedSpans).hasSize(1);
        verifySpanHasExpectedInfo(
            spanRecorder.completedSpans.get(0),
            traceIdResponseHeader,
            SpanPurpose.SERVER,
            "POST",
            500,
            "Intentional ResponseSender failure - full responses seen: 1"
        );

        // We should find a completed access log for the request, with the expected info.
        waitUntilCollectionHasSize(downstreamAccessLogger.accessLogs, 1, 1000, "downstreamAccessLogger");
        assertThat(downstreamAccessLogger.accessLogs).hasSize(1);
        verifyAccessLogHasExpectedInfo(
            downstreamAccessLogger.accessLogs.get(0),
            "POST " + bad404Path,
            "500",
            errorId,
            traceIdResponseHeader
        );

        // Metrics for the request should have been finalized.
        waitUntilCollectionHasSize(downstreamMetrics.metricsEvents, 2, 1000, "downstreamMetrics");
        assertThat(downstreamMetrics.metricsEvents).isEqualTo(Arrays.asList(REQUEST_RECEIVED, RESPONSE_SENT));
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void a_404_call_with_continual_response_sender_failures_causes_connection_to_be_closed(
        boolean sendBigPayload
    ) {
        // given
        downstreamResponseSender.failOnAnyFullResponse = true;

        int payloadSize = (sendBigPayload) ? 12_000 : 0;
        String payload = generatePayload(payloadSize);

        String bad404Path = "/does/not/exist/" + UUID.randomUUID().toString();

        // when
        Throwable ex = catchThrowable(
            () -> given()
                .config(noRetryRestAssuredConfig())
                .baseUri("http://127.0.0.1")
                .port(downstreamPort)
                .basePath(bad404Path)
                .body(payload)
                .log().headers()
            .when()
                .post()
            .then()
                .log().status().log().headers()
                .extract()
        );

        // then
        // The connection should have been closed without a response.
        verifyConnectionClosedException(ex);

        // We should find a completed server span representing the request, with the expected info.
        waitUntilSpanRecorderHasExpectedNumSpans(spanRecorder, 1);
        assertThat(spanRecorder.completedSpans).hasSize(1);
        verifySpanHasExpectedInfo(
            spanRecorder.completedSpans.get(0),
            null,
            SpanPurpose.SERVER,
            "POST",
            500,
            "Intentional ResponseSender failure - full responses seen: 1"
        );

        // We should find a completed access log for the request, with the expected info.
        assertThat(downstreamAccessLogger.accessLogs).hasSize(1);
        waitUntilCollectionHasSize(downstreamAccessLogger.accessLogs, 1, 1000, "downstreamAccessLogger");
        verifyAccessLogHasExpectedInfo(
            downstreamAccessLogger.accessLogs.get(0),
            "POST " + bad404Path,
            "500",
            null,
            null
        );

        // Metrics for the request should have been finalized.
        waitUntilCollectionHasSize(downstreamMetrics.metricsEvents, 2, 1000, "downstreamMetrics");
        assertThat(downstreamMetrics.metricsEvents).isEqualTo(Arrays.asList(REQUEST_RECEIVED, RESPONSE_WRITE_FAILED));
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void proxy_endpoint_call_with_initial_chunk_send_failure_causes_last_ditch_error_response_to_be_returned(
        boolean sendBigPayload
    ) throws IOException {
        // given
        proxyResponseSender.failOnFirstChunkResponse = true;

        int payloadSize = (sendBigPayload) ? 12_000 : 0;
        String payload = generatePayload(payloadSize);

        // when
        ExtractableResponse<?> response =
            given()
                .config(noRetryRestAssuredConfig())
                .baseUri("http://127.0.0.1")
                .port(proxyPort)
                .basePath(ProxyServerConfig.PROXY_MATCHING_PATH)
                .body(payload)
                .log().headers()
            .when()
                .post()
            .then()
                .log().status().log().headers()
                .extract();

        // then
        // Response payload and headers should match the error we expect.
        verifyErrorReceived(response.asString(), response.statusCode(), SampleCoreApiError.GENERIC_SERVICE_ERROR);

        String errorId = response.header("error_uid");
        assertThat(errorId).isNotBlank();
        assertThat(response.asString()).contains("\"error_id\":\"" + errorId + "\"");

        String traceIdResponseHeader = response.header(TraceHeaders.TRACE_ID);
        assertThat(traceIdResponseHeader).isNotBlank();

        // We should find three completed spans - the downstream server span, the proxy client span, and the proxy
        //      server span. Make sure we got them all, with the expected data in the proxy spans (since we're testing
        //      proxy behavior here).
        waitUntilSpanRecorderHasExpectedNumSpans(spanRecorder, 3);
        assertThat(spanRecorder.completedSpans).hasSize(3);
        // Sanity check the first two spans are the downstream span and the proxy client span.
        sanityCheckDownstreamAndProxyClientSpanForProxyRequest(traceIdResponseHeader);
        // The last span is the one we're really interested in - the proxy server span. This should represent the error
        //      we got.
        verifySpanHasExpectedInfo(
            spanRecorder.completedSpans.get(2),
            traceIdResponseHeader,
            SpanPurpose.SERVER,
            "POST " + ProxyServerConfig.PROXY_MATCHING_PATH,
            500,
            "Intentional ResponseSender failure for first chunk proxy response"
        );

        // We should find a completed access log for the request on the proxy server, with the expected info
        //      representing the error.
        waitUntilCollectionHasSize(proxyAccessLogger.accessLogs, 1, 1000, "proxyAccessLogger");
        assertThat(proxyAccessLogger.accessLogs).hasSize(1);
        verifyAccessLogHasExpectedInfo(
            proxyAccessLogger.accessLogs.get(0),
            "POST " + ProxyServerConfig.PROXY_MATCHING_PATH,
            "500",
            errorId,
            traceIdResponseHeader
        );

        // Metrics for the proxy request should have been finalized.
        waitUntilCollectionHasSize(proxyMetrics.metricsEvents, 2, 1000, "proxyMetrics");
        assertThat(proxyMetrics.metricsEvents).isEqualTo(Arrays.asList(REQUEST_RECEIVED, RESPONSE_SENT));
    }

    private void sanityCheckDownstreamAndProxyClientSpanForProxyRequest(String expectedTraceId) {
        verifySpanHasExpectedInfo(
            spanRecorder.completedSpans.get(0),
            expectedTraceId,
            SpanPurpose.SERVER,
            "POST " + DownstreamEndpoint.DS_MATCHING_PATH,
            200,
            null
        );
        verifySpanHasExpectedInfo(
            spanRecorder.completedSpans.get(1),
            expectedTraceId,
            SpanPurpose.CLIENT,
            "proxy-POST " + ProxyServerConfig.PROXY_MATCHING_PATH,
            200,
            null
        );
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void proxy_endpoint_call_with_all_chunk_send_and_full_response_failures_causes_connection_to_be_closed(
        boolean sendBigPayload
    ) {
        // given
        proxyResponseSender.failOnFirstChunkResponse = true;
        proxyResponseSender.failOnContentChunks = true;
        proxyResponseSender.failOnAnyFullResponse = true;

        // In order for the connection closing to actually cause a problem, we need content length to be non-zero.
        //      Otherwise, the headers chunk with content-length of 0 is sent, the connection is closed, and that's
        //      a valid HTTP response (you can finalize a HTTP response with a connection closing as long as the
        //      content-length header value and the actual bytes of content sent matches).
        int payloadSize = (sendBigPayload) ? 12_000 : 1;
        String payload = generatePayload(payloadSize);

        // when
        Throwable ex = catchThrowable(
            () -> given()
                .config(noRetryRestAssuredConfig())
                .baseUri("http://127.0.0.1")
                .port(proxyPort)
                .basePath(ProxyServerConfig.PROXY_MATCHING_PATH)
                .body(payload)
                .log().headers()
            .when()
                .post()
            .then()
                .log().status().log().headers()
                .extract());

        // then
        // We should have received no response, not even partial, so it should be a connection closed exception.
        verifyConnectionClosedException(ex);

        // We should find three completed spans - the downstream server span, the proxy client span, and the proxy
        //      server span. Make sure we got them all, with the expected data in the proxy spans (since we're testing
        //      proxy behavior here).
        waitUntilSpanRecorderHasExpectedNumSpans(spanRecorder, 3);
        assertThat(spanRecorder.completedSpans).hasSize(3);
        // Sanity check the first two spans are the downstream span and the proxy client span.
        sanityCheckDownstreamAndProxyClientSpanForProxyRequest(null);
        // The last span is the one we're really interested in - the proxy server span. It should represent the
        //      connection closing error.
        verifySpanHasExpectedInfo(
            spanRecorder.completedSpans.get(2),
            null,
            SpanPurpose.SERVER,
            "POST " + ProxyServerConfig.PROXY_MATCHING_PATH,
            500,
            "Intentional ResponseSender failure for first chunk proxy response"
        );

        // We should find a completed access log for the request on the proxy server, with the expected info
        //      representing the error.
        waitUntilCollectionHasSize(proxyAccessLogger.accessLogs, 1, 1000, "proxyAccessLogger");
        assertThat(proxyAccessLogger.accessLogs).hasSize(1);
        verifyAccessLogHasExpectedInfo(
            proxyAccessLogger.accessLogs.get(0),
            "POST " + ProxyServerConfig.PROXY_MATCHING_PATH,
            "500",
            null,
            null
        );

        // Metrics for the proxy request should have been finalized.
        waitUntilCollectionHasSize(proxyMetrics.metricsEvents, 2, 1000, "proxyMetrics");
        assertThat(proxyMetrics.metricsEvents).isEqualTo(Arrays.asList(REQUEST_RECEIVED, RESPONSE_WRITE_FAILED));
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void proxy_endpoint_call_with_content_chunk_send_failure_after_header_chunk_success_causes_connection_to_be_closed(
        boolean sendBigPayload
    ) {
        // given
        proxyResponseSender.failOnContentChunks = true;

        // In order for the connection closing to actually cause a problem, we need content length to be non-zero.
        //      Otherwise, the headers chunk with content-length of 0 is sent, the connection is closed, and that's
        //      a valid HTTP response (you can finalize a HTTP response with a connection closing as long as the
        //      content-length header value and the actual bytes of content sent matches).
        int payloadSize = (sendBigPayload) ? 12_000 : 1;
        String payload = generatePayload(payloadSize);

        // when
        Throwable ex = catchThrowable(
            () -> {
                ExtractableResponse<?> response = given()
                    .config(noRetryRestAssuredConfig())
                    .baseUri("http://127.0.0.1")
                    .port(proxyPort)
                    .basePath(ProxyServerConfig.PROXY_MATCHING_PATH)
                    .body(payload)
                    .log().headers()
                .when()
                    .post()
                .then()
                    .log().status().log().headers()
                    .extract();
                // Asking the response for its body should trigger the exception if it wasn't already triggered.
                response.asString();
            });

        // then
        // The connection should have been closed after the headers chunk was sent, but before the response could be
        //      completed. This may result in a partial response exception, or a ConnectionClosedException directly,
        //      depending on how RestAssured handled the response.
        Throwable ccEx;
        if (ex instanceof ResponseParseException) {
            ccEx = ex.getCause();
        }
        else {
            ccEx = ex;
        }
        assertThat(ccEx)
            .isInstanceOf(ConnectionClosedException.class)
            .hasMessageStartingWith("Premature end of Content-Length delimited message body");

        // We should find three completed spans - the downstream server span, the proxy client span, and the proxy
        //      server span. Make sure we got them all, with the expected data in the proxy spans (since we're testing
        //      proxy behavior here).
        waitUntilSpanRecorderHasExpectedNumSpans(spanRecorder, 3);
        assertThat(spanRecorder.completedSpans).hasSize(3);
        // Sanity check the first two spans are the downstream span and the proxy client span.
        sanityCheckDownstreamAndProxyClientSpanForProxyRequest(null);
        // The last span is the one we're really interested in - the proxy server span. It will have a 200 HTTP status
        //      code because the headers chunk was successfully sent, but it will also have an error tag because it
        //      didn't fully complete the response successfully.
        verifySpanHasExpectedInfo(
            spanRecorder.completedSpans.get(2),
            null,
            SpanPurpose.SERVER,
            "POST " + ProxyServerConfig.PROXY_MATCHING_PATH,
            200,
            "Intentional ResponseSender failure - content chunks seen: 1"
        );

        // We should find a completed access log for the request on the proxy server, with the expected info
        //      representing the error.
        waitUntilCollectionHasSize(proxyAccessLogger.accessLogs, 1, 1000, "proxyAccessLogger");
        assertThat(proxyAccessLogger.accessLogs).hasSize(1);
        verifyAccessLogHasExpectedInfo(
            proxyAccessLogger.accessLogs.get(0),
            "POST " + ProxyServerConfig.PROXY_MATCHING_PATH,
            "200",
            null,
            null
        );

        // Metrics for the proxy request should have been finalized.
        waitUntilCollectionHasSize(proxyMetrics.metricsEvents, 2, 1000, "proxyMetrics");
        assertThat(proxyMetrics.metricsEvents).isEqualTo(Arrays.asList(REQUEST_RECEIVED, RESPONSE_WRITE_FAILED));
    }

    static class ExplodingResponseSender extends ResponseSender {

        int fullResponsesSeen = 0;
        boolean failOnFirstFullResponse = false;
        boolean failOnAnyFullResponse = false;

        boolean firstChunkSeen = false;
        int contentChunksSeen = 0;
        boolean failOnFirstChunkResponse = false;
        boolean failOnContentChunks = false;

        public ExplodingResponseSender() {
            super(null, null, DefaultRiposteDistributedTracingConfigImpl.getDefaultInstance());
        }

        @Override
        public void sendFullResponse(
            ChannelHandlerContext ctx, RequestInfo<?> requestInfo, ResponseInfo<?> responseInfo, ObjectMapper serializer
        ) throws JsonProcessingException {
            fullResponsesSeen++;
            if (failOnAnyFullResponse || (failOnFirstFullResponse && fullResponsesSeen == 1)) {
                throw new RuntimeException("Intentional ResponseSender failure - full responses seen: " + fullResponsesSeen);
            }
            super.sendFullResponse(ctx, requestInfo, responseInfo, serializer);
        }

        @Override
        public void sendResponseChunk(
            ChannelHandlerContext ctx, RequestInfo<?> requestInfo, ResponseInfo<?> responseInfo,
            ChunkedOutboundMessage msg
        ) {
            Logger logger = LoggerFactory.getLogger(this.getClass());
            logger.info("In sendResponseChunk, msg: " + msg);
            if (msg instanceof OutboundMessageSendHeadersChunkFromResponseInfo) {
                firstChunkSeen = true;
                if (failOnFirstChunkResponse) {
                    throw new RuntimeException("Intentional ResponseSender failure for first chunk proxy response");
                }
            }
            else {
                contentChunksSeen++;
                if (failOnContentChunks) {
                    throw new RuntimeException(
                        "Intentional ResponseSender failure - content chunks seen: " + contentChunksSeen);
                }
            }

            super.sendResponseChunk(ctx, requestInfo, responseInfo, msg);
        }

        public void resetFailures() {
            fullResponsesSeen = 0;
            failOnFirstFullResponse = false;
            failOnAnyFullResponse = false;

            firstChunkSeen = false;
            contentChunksSeen = 0;
            failOnFirstChunkResponse = false;
            failOnContentChunks = false;
        }
    }

    static class TrackingAccessLogger extends AccessLogger {
        final List<String> accessLogs = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected @NotNull String generateFinalAccessLogMessage(
            @NotNull RequestInfo<?> request, @Nullable HttpResponse finalResponseObject,
            @Nullable ResponseInfo responseInfo, @Nullable Long elapsedTimeMillis
        ) {
            String result = super.generateFinalAccessLogMessage(
                request, finalResponseObject, responseInfo, elapsedTimeMillis
            );
            accessLogs.add(result);
            return result;
        }
    }

    static class TrackingMetricsListener implements MetricsListener {
        final List<ServerMetricsEvent> metricsEvents = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onEvent(
            @NotNull ServerMetricsEvent event, @Nullable Object value
        ) {
            metricsEvents.add(event);
        }
    }

    private static class ExplodingResponseSenderPipelineHook implements PipelineCreateHook {
        private final ExplodingResponseSender explodingResponseSender;

        private ExplodingResponseSenderPipelineHook(
            ExplodingResponseSender explodingResponseSender
        ) {
            this.explodingResponseSender = explodingResponseSender;
        }

        @Override
        public void executePipelineCreateHook(@NotNull ChannelPipeline pipeline) {
            pipeline.replace(
                RESPONSE_SENDER_HANDLER_NAME,
                RESPONSE_SENDER_HANDLER_NAME,
                new ResponseSenderHandler(explodingResponseSender)
            );
        }
    }

    private static class ProxyServerConfig implements ServerConfig {
        static final String PROXY_MATCHING_PATH = "/proxy";

        private final int port;
        private final Collection<Endpoint<?>> endpoints;
        final ExplodingResponseSender explodingResponseSender;
        final TrackingAccessLogger trackingAccessLogger;
        final TrackingMetricsListener trackingMetricsListener;

        private ProxyServerConfig(int port, int downstreamPort) {
            this.port = port;
            endpoints = singleton(
                new SimpleProxyRouterEndpoint(
                    Matcher.match(PROXY_MATCHING_PATH),
                    "127.0.0.1",
                    downstreamPort,
                    DownstreamEndpoint.DS_MATCHING_PATH,
                    false
                )
            );
            this.explodingResponseSender = new ExplodingResponseSender();
            this.trackingAccessLogger = new TrackingAccessLogger();
            this.trackingMetricsListener = new TrackingMetricsListener();
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
            return singletonList(new ExplodingResponseSenderPipelineHook(explodingResponseSender));
        }

        @Override
        public @Nullable AccessLogger accessLogger() {
            return trackingAccessLogger;
        }

        @Override
        public @Nullable MetricsListener metricsListener() {
            return trackingMetricsListener;
        }
    }

    private static class DownstreamServerConfig implements ServerConfig {

        private final int port;
        private final Collection<Endpoint<?>> endpoints = singleton(new DownstreamEndpoint());
        final ExplodingResponseSender explodingResponseSender;
        final TrackingAccessLogger trackingAccessLogger;
        final TrackingMetricsListener trackingMetricsListener;

        private DownstreamServerConfig(int port) {
            this.port = port;
            this.explodingResponseSender = new ExplodingResponseSender();
            this.trackingAccessLogger = new TrackingAccessLogger();
            this.trackingMetricsListener = new TrackingMetricsListener();
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
            return singletonList(new ExplodingResponseSenderPipelineHook(explodingResponseSender));
        }

        @Override
        public @Nullable AccessLogger accessLogger() {
            return trackingAccessLogger;
        }

        @Override
        public @Nullable MetricsListener metricsListener() {
            return trackingMetricsListener;
        }
    }

    private static class DownstreamEndpoint extends StandardEndpoint<String, String> {

        static final String DS_MATCHING_PATH = "/downstreamEndpoint";
        private final Matcher matcher = Matcher.match(DS_MATCHING_PATH);

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<String> request, @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(request.getContent()).withDesiredContentWriterMimeType("text/plain").build()
            );
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return matcher;
        }
    }

}
