package com.nike.riposte.metrics.codahale;

import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.metrics.ServerMetricsEvent;
import com.nike.riposte.util.Matcher;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.internal.util.reflection.Whitebox;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

import static com.codahale.metrics.MetricRegistry.name;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ENDPOINT_NOT_FOUND_MAP_KEY;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.METHOD_NOT_ALLOWED_MAP_KEY;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.NO_ENDPOINT_SHORT_CIRCUIT_KEY;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ROUTING_ERROR_MAP_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link CodahaleMetricsListener}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class CodahaleMetricsListenerTest {

    private CodahaleMetricsListener listener;
    private CodahaleMetricsCollector cmcMock;
    private MetricRegistry metricRegistryMock;

    private Map<String, Timer> registeredTimerMocks;
    private Map<String, Meter> registeredMeterMocks;
    private Map<String, Counter> registeredCounterMocks;
    private Map<String, Histogram> registeredHistogramMocks;

    private Map<String, Gauge> registeredGauges;

    private ServerConfig serverConfig;

    HttpProcessingState state;

    RequestInfo<?> requestInfoMock;
    ResponseInfo<?> responseInfoMock;

    @Before
    public void beforeMethod() {
        setupMetricRegistryAndCodahaleMetricsCollector();

        listener = new CodahaleMetricsListener(cmcMock);

        serverConfig = new ServerConfig() {
            private final List<Endpoint<?>> endpoints = Arrays.asList(
                new DummyEndpoint(Matcher.match("/foo")),
                new DummyEndpoint(Matcher.match("/bar", HttpMethod.POST, HttpMethod.PUT)),
                new DummyEndpoint(Matcher.multiMatch(Arrays.asList("/multiFoo", "/multiBar"))),
                new DummyEndpoint(Matcher.multiMatch(Arrays.asList("/multiBaz", "/multiBat"),
                                                     HttpMethod.PATCH, HttpMethod.OPTIONS))
            );

            @Override
            public Collection<Endpoint<?>> appEndpoints() {
                return endpoints;
            }

            @Override
            public int numBossThreads() {
                return 3;
            }

            @Override
            public int numWorkerThreads() {
                return 42;
            }

            @Override
            public int maxRequestSizeInBytes() {
                return 42434445;
            }
        };
        listener.initServerConfigMetrics(serverConfig);

        requestInfoMock = mock(RequestInfo.class);
        responseInfoMock = mock(ResponseInfo.class);

        state = new HttpProcessingState();

        state.setRequestInfo(requestInfoMock);
        state.setResponseInfo(responseInfoMock);
        state.setRequestStartTime(Instant.now());
    }

    private void setupMetricRegistryAndCodahaleMetricsCollector() {
        metricRegistryMock = mock(MetricRegistry.class);
        cmcMock = mock(CodahaleMetricsCollector.class);

        doReturn(metricRegistryMock).when(cmcMock).getMetricRegistry();

        registeredTimerMocks = new HashMap<>();
        doAnswer(invocation -> {
            String name = invocation.getArgumentAt(0, String.class);
            Timer timerMock = mock(Timer.class);
            registeredTimerMocks.put(name, timerMock);
            return timerMock;
        }).when(metricRegistryMock).timer(anyString());

        registeredMeterMocks = new HashMap<>();
        doAnswer(invocation -> {
            String name = invocation.getArgumentAt(0, String.class);
            Meter meterMock = mock(Meter.class);
            registeredMeterMocks.put(name, meterMock);
            return meterMock;
        }).when(metricRegistryMock).meter(anyString());

        registeredCounterMocks = new HashMap<>();
        doAnswer(invocation -> {
            String name = invocation.getArgumentAt(0, String.class);
            Counter counterMock = mock(Counter.class);
            registeredCounterMocks.put(name, counterMock);
            return counterMock;
        }).when(metricRegistryMock).counter(anyString());

        registeredHistogramMocks = new HashMap<>();
        doAnswer(invocation -> {
            String name = invocation.getArgumentAt(0, String.class);
            Histogram histogramMock = mock(Histogram.class);
            registeredHistogramMocks.put(name, histogramMock);
            return histogramMock;
        }).when(metricRegistryMock).histogram(anyString());

        registeredGauges = new HashMap<>();
        doAnswer(invocation -> {
            String name = invocation.getArgumentAt(0, String.class);
            Gauge gauge = invocation.getArgumentAt(1, Gauge.class);
            registeredGauges.put(name, gauge);
            return gauge;
        }).when(metricRegistryMock).register(anyString(), any(Metric.class));
    }

    @Test
    public void constructor_sets_fields_as_expected() {
        // given
        setupMetricRegistryAndCodahaleMetricsCollector();

        // when
        CodahaleMetricsListener instance = new CodahaleMetricsListener(cmcMock);

        // then
        assertThat(instance.metricsCollector).isSameAs(cmcMock);
        assertThat(instance.inflightRequests).isNotNull();
        assertThat(instance.processedRequests).isNotNull();
        assertThat(instance.failedRequests).isNotNull();
        assertThat(instance.responseWriteFailed).isNotNull();
        assertThat(instance.responseSizes).isNotNull();
        assertThat(instance.requestSizes).isNotNull();
        assertThat(instance.getRequests).isNotNull();
        assertThat(instance.postRequests).isNotNull();
        assertThat(instance.putRequests).isNotNull();
        assertThat(instance.deleteRequests).isNotNull();
        assertThat(instance.otherRequests).isNotNull();
        assertThat(instance.requests).isNotNull();
        assertThat(instance.responses)
            .isNotNull()
            .hasSize(5);
        assertThat(instance.endpointRequestsTimers)
            .isNotNull()
            .isEmpty();
        assertThat(instance.endpointResponsesMeters)
            .isNotNull()
            .isEmpty();
        assertThat(registeredGauges).isEmpty();
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_passed_null() {
        // when
        Throwable ex = catchThrowable(() -> new CodahaleMetricsListener(null));

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void initServerConfigMetrics_adds_expected_metrics() {
        // given
        setupMetricRegistryAndCodahaleMetricsCollector();
        CodahaleMetricsListener instance = new CodahaleMetricsListener(cmcMock);

        String expectedBossThreadsGaugeName = name(ServerConfig.class.getSimpleName(), "bossThreads");
        String expectedWorkerThreadsGaugeName = name(ServerConfig.class.getSimpleName(), "workerThreads");
        String expectedMaxRequestSizeInBytesGaugeName = name(ServerConfig.class.getSimpleName(), "maxRequestSizeInBytes");
        String expectedEndpointsListGaugeName = name(ServerConfig.class.getSimpleName(), "endpoints");

        List<String> expectedEndpointsListValue =
            serverConfig.appEndpoints().stream()
                        .map(
                            endpoint -> endpoint.getClass().getName()
                                        + "-" + instance.getMatchingHttpMethodsAsCombinedString(endpoint)
                                        + "-" + endpoint.requestMatcher().matchingPathTemplates()
                        )
                        .collect(Collectors.toList());

        // when
        instance.initServerConfigMetrics(serverConfig);

        // then
        // Metrics for server config values
        assertThat(registeredGauges).containsKey(expectedBossThreadsGaugeName);
        assertThat(registeredGauges.get(expectedBossThreadsGaugeName).getValue())
            .isEqualTo(serverConfig.numBossThreads());

        assertThat(registeredGauges).containsKey(expectedWorkerThreadsGaugeName);
        assertThat(registeredGauges.get(expectedWorkerThreadsGaugeName).getValue())
            .isEqualTo(serverConfig.numWorkerThreads());

        assertThat(registeredGauges).containsKey(expectedMaxRequestSizeInBytesGaugeName);
        assertThat(registeredGauges.get(expectedMaxRequestSizeInBytesGaugeName).getValue())
            .isEqualTo(serverConfig.maxRequestSizeInBytes());

        assertThat(registeredGauges).containsKey(expectedEndpointsListGaugeName);
        assertThat(registeredGauges.get(expectedEndpointsListGaugeName).getValue())
            .isEqualTo(expectedEndpointsListValue);

        // Per-endpoint request and response metrics
        serverConfig.appEndpoints().forEach(endpoint -> {
            String timerAndMeterMapKeyForEndpoint = instance.getTimerAndMeterMapKeyForEndpoint(endpoint);
            String name = name(instance.prefix, "endpoints." + endpoint.getClass().getName().replace(".", "-") + "-"
                                       + timerAndMeterMapKeyForEndpoint);

            Timer expectedTimer = registeredTimerMocks.get(name);
            assertThat(expectedTimer).isNotNull();
            assertThat(instance.endpointRequestsTimers.get(timerAndMeterMapKeyForEndpoint)).isSameAs(expectedTimer);

            Meter[] expectedMeters = new Meter[5];
            for (int i = 0; i < 5; i++) {
                String nextMeterName = name(name, (i + 1) + "xx-responses");
                Meter nextMeter = registeredMeterMocks.get(nextMeterName);
                assertThat(nextMeter).isNotNull();
                expectedMeters[i] = nextMeter;
            }

            assertThat(instance.endpointResponsesMeters.get(timerAndMeterMapKeyForEndpoint)).isEqualTo(expectedMeters);
        });

        // Aggregate metrics for 404, 405, 500, and other short-circuit type responses that never hit an endpoint
        // 404 metrics
        {
            String notFoundNameId = name(instance.prefix, "endpoints." + ENDPOINT_NOT_FOUND_MAP_KEY);
            Timer expected404Timer = registeredTimerMocks.get(notFoundNameId);
            assertThat(expected404Timer).isNotNull();
            assertThat(instance.endpointRequestsTimers.get(ENDPOINT_NOT_FOUND_MAP_KEY)).isSameAs(expected404Timer);
            Meter expected404Meter = registeredMeterMocks.get(name(notFoundNameId, "4xx-responses"));
            assertThat(expected404Meter).isNotNull();
            assertThat(instance.endpointResponsesMeters.get(ENDPOINT_NOT_FOUND_MAP_KEY)).isEqualTo(
                new Meter[]{expected404Meter}
            );
        }

        // 405 metrics
        {
            String methodNotAllowedNameId = name(instance.prefix, "endpoints." + METHOD_NOT_ALLOWED_MAP_KEY);
            Timer expected405Timer = registeredTimerMocks.get(methodNotAllowedNameId);
            assertThat(expected405Timer).isNotNull();
            assertThat(instance.endpointRequestsTimers.get(METHOD_NOT_ALLOWED_MAP_KEY)).isSameAs(expected405Timer);
            Meter expected405Meter = registeredMeterMocks.get(name(methodNotAllowedNameId, "4xx-responses"));
            assertThat(expected405Meter).isNotNull();
            assertThat(instance.endpointResponsesMeters.get(METHOD_NOT_ALLOWED_MAP_KEY)).isEqualTo(
                new Meter[]{expected405Meter}
            );
        }

        // 500 routing error metrics
        {
            String routingErrorNameId = name(instance.prefix, "endpoints." + ROUTING_ERROR_MAP_KEY);
            Timer expected500Timer = registeredTimerMocks.get(routingErrorNameId);
            assertThat(expected500Timer).isNotNull();
            assertThat(instance.endpointRequestsTimers.get(ROUTING_ERROR_MAP_KEY)).isSameAs(expected500Timer);
            Meter expected500Meter = registeredMeterMocks.get(name(routingErrorNameId, "5xx-responses"));
            assertThat(expected500Meter).isNotNull();
            assertThat(instance.endpointResponsesMeters.get(ROUTING_ERROR_MAP_KEY)).isEqualTo(
                new Meter[]{expected500Meter}
            );
        }

        // Misc no-endpoint short-circuit response metrics
        {
            String shortCircuitNameId = name(instance.prefix, "endpoints." + NO_ENDPOINT_SHORT_CIRCUIT_KEY);
            Timer expectedShortCircuitTimer = registeredTimerMocks.get(shortCircuitNameId);
            assertThat(expectedShortCircuitTimer).isNotNull();
            assertThat(instance.endpointRequestsTimers.get(NO_ENDPOINT_SHORT_CIRCUIT_KEY)).isSameAs(expectedShortCircuitTimer);
            Meter[] expectedShortCircuitMeters = new Meter[5];
            for (int i = 0; i < 5; i++) {
                String nextMeterName = name(shortCircuitNameId, (i + 1) + "xx-responses");
                Meter nextMeter = registeredMeterMocks.get(nextMeterName);
                assertThat(nextMeter).isNotNull();
                expectedShortCircuitMeters[i] = nextMeter;
            }
            assertThat(instance.endpointResponsesMeters.get(NO_ENDPOINT_SHORT_CIRCUIT_KEY)).isEqualTo(
                expectedShortCircuitMeters
            );
        }
    }

    @Test
    public void onEvent_works_as_expected_for_REQUEST_RECEIVED() {
        // given
        ServerMetricsEvent event = ServerMetricsEvent.REQUEST_RECEIVED;

        // when
        listener.onEvent(event, null);

        // then
        verify(listener.inflightRequests).inc();
    }

    @Test
    public void onEvent_works_as_expected_for_RESPONSE_WRITE_FAILED() {
        // given
        ServerMetricsEvent event = ServerMetricsEvent.RESPONSE_WRITE_FAILED;

        // when
        listener.onEvent(event, null);

        // then
        verify(listener.responseWriteFailed).inc();
    }

    @DataProvider(value = {
        "GET    |   99",
        "GET    |   142",
        "GET    |   242",
        "GET    |   342",
        "GET    |   404",
        "GET    |   405",
        "GET    |   442",
        "GET    |   500",
        "GET    |   542",
        "GET    |   600",
        "POST   |   99",
        "POST   |   142",
        "POST   |   242",
        "POST   |   342",
        "POST   |   404",
        "POST   |   405",
        "POST   |   442",
        "POST   |   500",
        "POST   |   542",
        "POST   |   600",
        "PUT    |   99",
        "PUT    |   142",
        "PUT    |   242",
        "PUT    |   342",
        "PUT    |   404",
        "PUT    |   405",
        "PUT    |   442",
        "PUT    |   500",
        "PUT    |   542",
        "PUT    |   600",
        "DELETE |   99",
        "DELETE |   142",
        "DELETE |   242",
        "DELETE |   342",
        "DELETE |   404",
        "DELETE |   405",
        "DELETE |   442",
        "DELETE |   500",
        "DELETE |   542",
        "DELETE |   600",
        "PATCH  |   99",
        "PATCH  |   142",
        "PATCH  |   242",
        "PATCH  |   342",
        "PATCH  |   404",
        "PATCH  |   405",
        "PATCH  |   442",
        "PATCH  |   500",
        "PATCH  |   542",
        "PATCH  |   600",
        "null   |   99",
        "null   |   142",
        "null   |   242",
        "null   |   342",
        "null   |   404",
        "null   |   405",
        "null   |   442",
        "null   |   500",
        "null   |   542",
        "null   |   600"
    }, splitBy = "\\|")
    @Test
    public void onEvent_works_as_expected_for_RESPONSE_SENT_with_endpoint(
        String requestMethodStr, int responseStatusCode
    ) throws InterruptedException {
        // given
        ServerMetricsEvent event = ServerMetricsEvent.RESPONSE_SENT;

        HttpMethod requestMethod = (requestMethodStr == null) ? null : HttpMethod.valueOf(requestMethodStr);
        doReturn(requestMethod).when(requestInfoMock).getMethod();

        Endpoint<?> endpoint = serverConfig.appEndpoints().iterator().next();
        state.setEndpointForExecution(endpoint);
        String endpointTimerAndMeterKey = listener.getTimerAndMeterMapKeyForEndpoint(endpoint);

        doReturn(responseStatusCode).when(responseInfoMock).getHttpStatusCodeWithDefault(ResponseSender.DEFAULT_HTTP_STATUS_CODE);

        int requestRawContentLengthBytes = (int)(Math.random() * 10000);
        doReturn(requestRawContentLengthBytes).when(requestInfoMock).getRawContentLengthInBytes();

        long finalResponseContentLength = (long)(Math.random() * 10000);
        doReturn(finalResponseContentLength).when(responseInfoMock).getFinalContentLength();

        Thread.sleep((long)(Math.random() * 25));

        // when
        long beforeCallEpochMillis = Instant.now().toEpochMilli();
        listener.onEvent(event, state);
        long afterCallEpochMillis = Instant.now().toEpochMilli();

        // then
        long minElapsedTime = beforeCallEpochMillis - state.getRequestStartTime().toEpochMilli();
        long maxElapsedTime = afterCallEpochMillis - state.getRequestStartTime().toEpochMilli();

        // Inflight requests counter decremented
        verify(listener.inflightRequests).dec();
        // Processed requests counter incremented
        verify(listener.processedRequests).inc();

        {
            // The all-requests timer should be updated with the elapsed time of the request
            ArgumentCaptor<Long> elapsedTimeArgCaptor = ArgumentCaptor.forClass(Long.class);
            verify(listener.requests).update(elapsedTimeArgCaptor.capture(), eq(TimeUnit.MILLISECONDS));
            long actualElapsedTimeUsed = elapsedTimeArgCaptor.getValue();
            assertThat(actualElapsedTimeUsed).isBetween(minElapsedTime, maxElapsedTime);
        }

        {
            // The timer for the relevant HTTP method for this request should be updated with the elapsed time of the request
            ArgumentCaptor<Long> elapsedTimeArgCaptor = ArgumentCaptor.forClass(Long.class);
            verify(expectedRequestTimer(requestMethod, listener)).update(elapsedTimeArgCaptor.capture(),
                                                                         eq(TimeUnit.MILLISECONDS));
            long actualElapsedTimeUsed = elapsedTimeArgCaptor.getValue();
            assertThat(actualElapsedTimeUsed).isBetween(minElapsedTime, maxElapsedTime);
        }

        {
            // The timer for the endpoint for this request should be updated with the elapsed time of the request
            Timer expectedEndpointTimerUsed = listener.endpointRequestsTimers.get(endpointTimerAndMeterKey);
            assertThat(expectedEndpointTimerUsed).isNotNull();

            ArgumentCaptor<Long> elapsedTimeArgCaptor = ArgumentCaptor.forClass(Long.class);
            verify(expectedEndpointTimerUsed).update(elapsedTimeArgCaptor.capture(), eq(TimeUnit.MILLISECONDS));
            long actualElapsedTimeUsed = elapsedTimeArgCaptor.getValue();
            assertThat(actualElapsedTimeUsed).isBetween(minElapsedTime, maxElapsedTime);
        }

        final int httpStatusCodeXXValue = responseStatusCode / 100;
        if (httpStatusCodeXXValue >= 1 && httpStatusCodeXXValue <= 5) {
            // Inside the normal 1xx-5xx response codes.

            // The correct 1xx, 2xx, 3xx, 4xx, or 5xx meter for all requests should be marked.
            verify(listener.responses[httpStatusCodeXXValue - 1]).mark();

            // The correct 1xx, 2xx, 3xx, 4xx, or 5xx meter for this request's endpoint should be marked.
            Meter[] endpointResponseMeterArray = listener.endpointResponsesMeters.get(endpointTimerAndMeterKey);
            assertThat(endpointResponseMeterArray).isNotNull();
            verify(endpointResponseMeterArray[httpStatusCodeXXValue - 1]).mark();
        }
        else {
            // Outside the normal 1xx-5xx response codes, so none of the response meters should have been modified.
            listener.endpointResponsesMeters.values().forEach(
                meterArray -> Stream.of(meterArray).forEach(Mockito::verifyZeroInteractions)
            );
        }

        // If response code is greater than or equal to 400, then the failed requests counter should be incremented.
        if (responseStatusCode >= 400)
            verify(listener.failedRequests).inc();

        // Request and response size histograms should be updated with the relevant values from the request and response.
        verify(listener.requestSizes).update(requestRawContentLengthBytes);
        verify(listener.responseSizes).update(finalResponseContentLength);
    }

    @DataProvider(value = {
        "200",
        "404",
        "405",
        "500"
    })
    @Test
    public void onEvent_updates_appropriate_timers_and_meters_for_RESPONSE_SENT_without_endpoint(int responseStatusCode) {
        // given
        ServerMetricsEvent event = ServerMetricsEvent.RESPONSE_SENT;
        state.setEndpointForExecution(null);
        doReturn(responseStatusCode).when(responseInfoMock).getHttpStatusCodeWithDefault(ResponseSender.DEFAULT_HTTP_STATUS_CODE);

        String expectedTimerKey;
        switch(responseStatusCode) {
            case 404:
                expectedTimerKey = CodahaleMetricsListener.ENDPOINT_NOT_FOUND_MAP_KEY;
                break;
            case 405:
                expectedTimerKey = CodahaleMetricsListener.METHOD_NOT_ALLOWED_MAP_KEY;
                break;
            case 500:
                expectedTimerKey = CodahaleMetricsListener.ROUTING_ERROR_MAP_KEY;
                break;
            default:
                expectedTimerKey = CodahaleMetricsListener.NO_ENDPOINT_SHORT_CIRCUIT_KEY;
        }
        Timer expectedTimerToUpdate = listener.endpointRequestsTimers.get(expectedTimerKey);
        assertThat(expectedTimerToUpdate).isNotNull();

        // when
        long beforeCallEpochMillis = Instant.now().toEpochMilli();
        listener.onEvent(event, state);
        long afterCallEpochMillis = Instant.now().toEpochMilli();

        // then
        long minElapsedTime = beforeCallEpochMillis - state.getRequestStartTime().toEpochMilli();
        long maxElapsedTime = afterCallEpochMillis - state.getRequestStartTime().toEpochMilli();

        // The special timer for this use case should be updated with the elapsed time of the request
        ArgumentCaptor<Long> elapsedTimeArgCaptor = ArgumentCaptor.forClass(Long.class);
        verify(expectedTimerToUpdate).update(elapsedTimeArgCaptor.capture(), eq(TimeUnit.MILLISECONDS));
        long actualElapsedTimeUsed = elapsedTimeArgCaptor.getValue();
        assertThat(actualElapsedTimeUsed).isBetween(minElapsedTime, maxElapsedTime);

        final int httpStatusCodeXXValue = responseStatusCode / 100;
        // The correct 1xx, 2xx, 3xx, 4xx, or 5xx meter for all requests should be marked.
        verify(listener.responses[httpStatusCodeXXValue - 1]).mark();

        // The correct 1xx, 2xx, 3xx, 4xx, or 5xx meter for this request's non-endpoint should be marked.
        Meter[] nonEndpointResponseMeterArray = listener.endpointResponsesMeters.get(expectedTimerKey);
        assertThat(nonEndpointResponseMeterArray).isNotNull();
        int meterIndexToUse = (NO_ENDPOINT_SHORT_CIRCUIT_KEY.equals(expectedTimerKey)) ? httpStatusCodeXXValue - 1 : 0;
        verify(nonEndpointResponseMeterArray[meterIndexToUse]).mark();
    }

    private Timer expectedRequestTimer(HttpMethod m, CodahaleMetricsListener cml) {
        if (m == null) {
            return cml.otherRequests;
        }
        else {
            if (m.equals(HttpMethod.GET))
                return cml.getRequests;
            else if (m.equals(HttpMethod.POST))
                return cml.postRequests;
            else if (m.equals(HttpMethod.PUT))
                return cml.putRequests;
            else if (m.equals(HttpMethod.DELETE))
                return cml.deleteRequests;
            else
                return cml.otherRequests;
        }
    }

    @Test
    public void onEvent_should_do_nothing_if_passed_RESPONSE_SENT_with_value_object_that_is_not_a_HttpProcessingState() {
        // when
        listener.onEvent(ServerMetricsEvent.RESPONSE_SENT, new Object());

        // then
        verifyZeroInteractions(listener.inflightRequests, listener.processedRequests);
    }

    @Test
    public void onEvent_should_short_circuit_for_RESPONSE_SENT_if_response_info_is_null() {
        // given
        state.setResponseInfo(null);

        // when
        listener.onEvent(ServerMetricsEvent.RESPONSE_SENT, state);

        // then
        // Inflight requests and processed requests counters should still be adjusted properly
        verify(listener.inflightRequests).dec();
        verify(listener.processedRequests).inc();
        // But we should short circuit immediately afterward
        verifyZeroInteractions(listener.requests);
    }

    @Test
    public void onEvent_should_short_circuit_for_RESPONSE_SENT_if_request_start_time_is_null() {
        // given
        state.setRequestStartTime(null);

        // when
        listener.onEvent(ServerMetricsEvent.RESPONSE_SENT, state);

        // then
        // Inflight requests and processed requests counters should still be adjusted properly
        verify(listener.inflightRequests).dec();
        verify(listener.processedRequests).inc();
        // But we should short circuit immediately afterward
        verifyZeroInteractions(listener.requests);
    }

    @Test
    public void onEvent_for_RESPONSE_SENT_updates_responseSizes_histogram_with_0_if_getFinalContentLength_is_null() {
        // given
        doReturn(null).when(responseInfoMock).getFinalContentLength();

        // when
        listener.onEvent(ServerMetricsEvent.RESPONSE_SENT, state);

        // then
        verify(listener.responseSizes).update(0L);
    }

    @Test
    public void onEvent_does_nothing_if_event_type_is_unknown() {
        // given
        ServerMetricsEvent event = null;
        Logger loggerMock = mock(Logger.class);
        doReturn(false).when(loggerMock).isDebugEnabled();
        Whitebox.setInternalState(listener, "logger", loggerMock);

        // when
        listener.onEvent(event, null);

        // then
        verifyZeroInteractions(listener.inflightRequests, listener.responseWriteFailed, listener.processedRequests);
        verify(loggerMock).error("Metrics Error: unknown metrics event " + event);
    }

    @Test
    public void onEvent_gracefully_handles_thrown_exceptions() {
        // given
        ServerMetricsEvent event = ServerMetricsEvent.RESPONSE_SENT;
        RuntimeException ex = new RuntimeException("kaboom");
        doThrow(ex).when(listener.inflightRequests).dec();
        Logger loggerMock = mock(Logger.class);
        doReturn(false).when(loggerMock).isDebugEnabled();
        Whitebox.setInternalState(listener, "logger", loggerMock);

        // when
        listener.onEvent(event, state);

        // then
        verifyZeroInteractions(listener.processedRequests); // Should have blown up before the processedRequests stuff.
        verify(loggerMock).error("Metrics Error: ", ex);
    }

    @Test
    public void getMetricRegistry_delegates_to_metricsCollector() {
        // given
        MetricRegistry mrMock = mock(MetricRegistry.class);
        doReturn(mrMock).when(cmcMock).getMetricRegistry();

        // when
        MetricRegistry result = listener.getMetricRegistry();

        // then
        assertThat(result).isSameAs(mrMock);
    }

    @Test
    public void code_coverage_hoops() {
        // jump!
        // Account for the logger.isDebugEnabled() branches.
        Logger loggerMock = mock(Logger.class);
        doReturn(false).when(loggerMock).isDebugEnabled();
        Whitebox.setInternalState(listener, "logger", loggerMock);
        listener.onEvent(ServerMetricsEvent.REQUEST_RECEIVED, null);
        listener.onEvent(ServerMetricsEvent.RESPONSE_SENT, state);
    }

    private static class DummyEndpoint extends StandardEndpoint<Void, Void> {

        private final Matcher matcher;

        public DummyEndpoint(Matcher matcher) {
            this.matcher = matcher;
        }

        @Override
        public Matcher requestMatcher() {
            return matcher;
        }

        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<Void> request,
                                                             Executor longRunningTaskExecutor,
                                                             ChannelHandlerContext ctx) {
            return null;
        }
    }
}