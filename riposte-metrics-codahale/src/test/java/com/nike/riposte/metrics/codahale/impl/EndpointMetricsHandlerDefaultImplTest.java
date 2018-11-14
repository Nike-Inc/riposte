package com.nike.riposte.metrics.codahale.impl;

import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
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
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

import static com.codahale.metrics.MetricRegistry.name;
import static com.nike.riposte.metrics.codahale.impl.EndpointMetricsHandlerDefaultImpl.ENDPOINT_NOT_FOUND_MAP_KEY;
import static com.nike.riposte.metrics.codahale.impl.EndpointMetricsHandlerDefaultImpl.METHOD_NOT_ALLOWED_MAP_KEY;
import static com.nike.riposte.metrics.codahale.impl.EndpointMetricsHandlerDefaultImpl.NO_ENDPOINT_SHORT_CIRCUIT_KEY;
import static com.nike.riposte.metrics.codahale.impl.EndpointMetricsHandlerDefaultImpl.ROUTING_ERROR_MAP_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link EndpointMetricsHandlerDefaultImpl}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class EndpointMetricsHandlerDefaultImplTest {

    private EndpointMetricsHandlerDefaultImpl instance;

    private ServerConfig serverConfig;
    private MetricRegistry metricRegistryMock;

    private Map<String, Timer> registeredTimers;
    private Map<String, Meter> registeredMeterMocks;
    private Map<String, Counter> registeredCounterMocks;
    private Map<String, Histogram> registeredHistogramMocks;
    private Map<String, Gauge> registeredGauges;

    HttpProcessingState state;

    RequestInfo<?> requestInfoMock;
    ResponseInfo<?> responseInfoMock;

    @Before
    public void beforeMethod() {
        instance = spy(new EndpointMetricsHandlerDefaultImpl());

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

        setupMetricRegistryMock();

        requestInfoMock = mock(RequestInfo.class);
        responseInfoMock = mock(ResponseInfo.class);

        state = new HttpProcessingState();

        state.setRequestInfo(requestInfoMock);
        state.setResponseInfo(responseInfoMock, null);
        state.setRequestStartTime(Instant.now());

        instance.setupEndpointsMetrics(serverConfig, metricRegistryMock);
    }

    private void setupMetricRegistryMock() {
        metricRegistryMock = mock(MetricRegistry.class);

        registeredTimers = new HashMap<>();
        doAnswer(invocation -> {
            Timer originalTimer = (Timer) invocation.callRealMethod();
            return spy(originalTimer);
        }).when(instance).createAndRegisterRequestTimer(anyString(), any(MetricRegistry.class));

        doThrow(new RuntimeException(
            "All timer creation should go through createAndRegisterRequestTimer(), *not* metricRegistry.timer(...)."
        )).when(metricRegistryMock).timer(anyString());

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
            Metric metric = invocation.getArgumentAt(1, Metric.class);
            if (metric instanceof Gauge)
                registeredGauges.put(name, (Gauge)metric);
            else if (metric instanceof Timer)
                registeredTimers.put(name, (Timer)metric);
            else
                throw new RuntimeException("Expected Gauge or Timer, but received: " + metric.getClass().getName());
            
            return metric;
        }).when(metricRegistryMock).register(anyString(), any(Metric.class));
    }

    @Test
    public void default_constructor_sets_up_instance_as_expected() {
        // when
        EndpointMetricsHandlerDefaultImpl newImpl = new EndpointMetricsHandlerDefaultImpl();

        // then
        assertThat(newImpl.requestTimerGenerator)
            .isSameAs(EndpointMetricsHandlerDefaultImpl.DEFAULT_REQUEST_TIMER_GENERATOR);
    }

    @Test
    public void one_arg_constructor_sets_up_instance_as_expected() {
        // given
        Supplier<Timer> timerSupplier = mock(Supplier.class);

        // when
        EndpointMetricsHandlerDefaultImpl newImpl = new EndpointMetricsHandlerDefaultImpl(timerSupplier);

        // then
        assertThat(newImpl.requestTimerGenerator).isSameAs(timerSupplier);
    }

    @Test
    public void createAndRegisterRequestTimer_should_use_requestTimerGenerator() {
        // given
        Timer timerMock = mock(Timer.class);
        Supplier<Timer> timerSupplier = () -> timerMock;
        EndpointMetricsHandlerDefaultImpl newImpl = new EndpointMetricsHandlerDefaultImpl(timerSupplier);

        // when
        Timer result = newImpl.createAndRegisterRequestTimer("foo", metricRegistryMock);

        // then
        assertThat(result).isSameAs(timerMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void setupEndpointsMetrics_sets_up_metrics_as_expected(boolean customizeTimerCreation) {
        // given
        EndpointMetricsHandlerDefaultImpl newImpl = spy(new EndpointMetricsHandlerDefaultImpl());
        String expectedTimerPrefix = customizeTimerCreation
                                     ? UUID.randomUUID().toString() + newImpl.prefix
                                     : newImpl.prefix;
        doAnswer(invocation -> {
            if (!customizeTimerCreation)
                return invocation.callRealMethod();

            // We're supposed to customize the timer creation for the purposes of this test to verify that all timer
            //      creation goes through the createAndRegisterRequestTimer() method.
            String name = invocation.getArgumentAt(0, String.class);
            // Replace the normal prefix with the customized prefix. This adds the non-standard-ness that lets us verify
            //      the custom createAndRegisterRequestTimer() was used.
            name = name.replace(newImpl.prefix, expectedTimerPrefix);
            MetricRegistry registry = invocation.getArgumentAt(1, MetricRegistry.class);
            return registry.register(name, mock(Timer.class));
        }).when(newImpl).createAndRegisterRequestTimer(anyString(), any(MetricRegistry.class));

        // when
        newImpl.setupEndpointsMetrics(serverConfig, metricRegistryMock);

        // then
        // Verify aggregate overall requests and responses metrics are setup with expected names
        assertThat(newImpl.getRequests()).isSameAs(newImpl.requests);
        assertThat(newImpl.requests).isEqualTo(registeredTimers.get(name(expectedTimerPrefix, "requests")));
        assertThat(mockingDetails(newImpl.requests).isMock()).isEqualTo(customizeTimerCreation);

        assertThat(newImpl.getResponses()).isSameAs(newImpl.responses);
        assertThat(newImpl.responses.length).isEqualTo(5);
        assertThat(newImpl.responses).isEqualTo(new Meter[]{
            registeredMeterMocks.get(name(newImpl.prefix, "1xx-responses")),
            registeredMeterMocks.get(name(newImpl.prefix, "2xx-responses")),
            registeredMeterMocks.get(name(newImpl.prefix, "3xx-responses")),
            registeredMeterMocks.get(name(newImpl.prefix, "4xx-responses")),
            registeredMeterMocks.get(name(newImpl.prefix, "5xx-responses"))
        });

        // Verify aggregate HTTP-method request metrics are setup with expected names
        assertThat(newImpl.getGetRequests()).isSameAs(newImpl.getRequests);
        assertThat(newImpl.getRequests).isEqualTo(registeredTimers.get(name(expectedTimerPrefix, "get-requests")));
        assertThat(mockingDetails(newImpl.getRequests).isMock()).isEqualTo(customizeTimerCreation);

        assertThat(newImpl.getPostRequests()).isSameAs(newImpl.postRequests);
        assertThat(newImpl.postRequests).isEqualTo(registeredTimers.get(name(expectedTimerPrefix, "post-requests")));
        assertThat(mockingDetails(newImpl.postRequests).isMock()).isEqualTo(customizeTimerCreation);

        assertThat(newImpl.getPutRequests()).isSameAs(newImpl.putRequests);
        assertThat(newImpl.putRequests).isEqualTo(registeredTimers.get(name(expectedTimerPrefix, "put-requests")));
        assertThat(mockingDetails(newImpl.putRequests).isMock()).isEqualTo(customizeTimerCreation);

        assertThat(newImpl.getDeleteRequests()).isSameAs(newImpl.deleteRequests);
        assertThat(newImpl.deleteRequests).isEqualTo(registeredTimers.get(name(expectedTimerPrefix, "delete-requests")));
        assertThat(mockingDetails(newImpl.deleteRequests).isMock()).isEqualTo(customizeTimerCreation);

        assertThat(newImpl.getOtherRequests()).isSameAs(newImpl.otherRequests);
        assertThat(newImpl.otherRequests).isEqualTo(registeredTimers.get(name(expectedTimerPrefix, "other-requests")));
        assertThat(mockingDetails(newImpl.otherRequests).isMock()).isEqualTo(customizeTimerCreation);

        // Verify per-endpoint request and response metrics are setup with expected names
        serverConfig.appEndpoints().forEach(endpoint -> {
            String timerAndMeterMapKeyForEndpoint = newImpl.getTimerAndMeterMapKeyForEndpoint(endpoint);
            String name = name(newImpl.prefix, "endpoints." + endpoint.getClass().getName().replace(".", "-") + "-"
                                                + timerAndMeterMapKeyForEndpoint);
            String timerName = name.replace(newImpl.prefix, expectedTimerPrefix);

            Timer expectedTimer = registeredTimers.get(timerName);
            assertThat(expectedTimer).isNotNull();
            assertThat(newImpl.endpointRequestsTimers.get(timerAndMeterMapKeyForEndpoint)).isSameAs(expectedTimer);
            assertThat(mockingDetails(expectedTimer).isMock()).isEqualTo(customizeTimerCreation);

            Meter[] expectedMeters = new Meter[5];
            for (int i = 0; i < 5; i++) {
                String nextMeterName = name(name, (i + 1) + "xx-responses");
                Meter nextMeter = registeredMeterMocks.get(nextMeterName);
                assertThat(nextMeter).isNotNull();
                expectedMeters[i] = nextMeter;
            }

            assertThat(newImpl.endpointResponsesMeters.get(timerAndMeterMapKeyForEndpoint)).isEqualTo(expectedMeters);
        });

        // Ditto for aggregate metrics for 404, 405, 500, and other short-circuit type responses that never hit an endpoint

        // 404 metrics
        {
            String notFoundNameId = name(newImpl.prefix, "endpoints." + ENDPOINT_NOT_FOUND_MAP_KEY);
            String timerNotFoundNameId = notFoundNameId.replace(newImpl.prefix, expectedTimerPrefix);
            Timer expected404Timer = registeredTimers.get(timerNotFoundNameId);
            assertThat(expected404Timer).isNotNull();
            assertThat(newImpl.endpointRequestsTimers.get(ENDPOINT_NOT_FOUND_MAP_KEY)).isSameAs(expected404Timer);
            assertThat(mockingDetails(expected404Timer).isMock()).isEqualTo(customizeTimerCreation);

            Meter expected404Meter = registeredMeterMocks.get(name(notFoundNameId, "4xx-responses"));
            assertThat(expected404Meter).isNotNull();
            assertThat(newImpl.endpointResponsesMeters.get(ENDPOINT_NOT_FOUND_MAP_KEY)).isEqualTo(
                new Meter[]{expected404Meter}
            );
        }

        // 405 metrics
        {
            String methodNotAllowedNameId = name(newImpl.prefix, "endpoints." + METHOD_NOT_ALLOWED_MAP_KEY);
            String timerMethodNotAllowedNameId = methodNotAllowedNameId.replace(newImpl.prefix, expectedTimerPrefix);
            Timer expected405Timer = registeredTimers.get(timerMethodNotAllowedNameId);
            assertThat(expected405Timer).isNotNull();
            assertThat(newImpl.endpointRequestsTimers.get(METHOD_NOT_ALLOWED_MAP_KEY)).isSameAs(expected405Timer);
            assertThat(mockingDetails(expected405Timer).isMock()).isEqualTo(customizeTimerCreation);

            Meter expected405Meter = registeredMeterMocks.get(name(methodNotAllowedNameId, "4xx-responses"));
            assertThat(expected405Meter).isNotNull();
            assertThat(newImpl.endpointResponsesMeters.get(METHOD_NOT_ALLOWED_MAP_KEY)).isEqualTo(
                new Meter[]{expected405Meter}
            );
        }

        // 500 routing error metrics
        {
            String routingErrorNameId = name(newImpl.prefix, "endpoints." + ROUTING_ERROR_MAP_KEY);
            String timerRoutingErrorNameId = routingErrorNameId.replace(newImpl.prefix, expectedTimerPrefix);
            Timer expected500Timer = registeredTimers.get(timerRoutingErrorNameId);
            assertThat(expected500Timer).isNotNull();
            assertThat(newImpl.endpointRequestsTimers.get(ROUTING_ERROR_MAP_KEY)).isSameAs(expected500Timer);
            assertThat(mockingDetails(expected500Timer).isMock()).isEqualTo(customizeTimerCreation);

            Meter expected500Meter = registeredMeterMocks.get(name(routingErrorNameId, "5xx-responses"));
            assertThat(expected500Meter).isNotNull();
            assertThat(newImpl.endpointResponsesMeters.get(ROUTING_ERROR_MAP_KEY)).isEqualTo(
                new Meter[]{expected500Meter}
            );
        }

        // Misc no-endpoint short-circuit response metrics
        {
            String shortCircuitNameId = name(newImpl.prefix, "endpoints." + NO_ENDPOINT_SHORT_CIRCUIT_KEY);
            String timerShortCircuitNameId = shortCircuitNameId.replace(newImpl.prefix, expectedTimerPrefix);
            Timer expectedShortCircuitTimer = registeredTimers.get(timerShortCircuitNameId);
            assertThat(expectedShortCircuitTimer).isNotNull();
            assertThat(newImpl.endpointRequestsTimers.get(NO_ENDPOINT_SHORT_CIRCUIT_KEY)).isSameAs(expectedShortCircuitTimer);
            assertThat(mockingDetails(expectedShortCircuitTimer).isMock()).isEqualTo(customizeTimerCreation);

            Meter[] expectedShortCircuitMeters = new Meter[5];
            for (int i = 0; i < 5; i++) {
                String nextMeterName = name(shortCircuitNameId, (i + 1) + "xx-responses");
                Meter nextMeter = registeredMeterMocks.get(nextMeterName);
                assertThat(nextMeter).isNotNull();
                expectedShortCircuitMeters[i] = nextMeter;
            }
            assertThat(newImpl.endpointResponsesMeters.get(NO_ENDPOINT_SHORT_CIRCUIT_KEY)).isEqualTo(
                expectedShortCircuitMeters
            );
        }
    }

    @Test
    public void setupEndpointsMetrics_throws_IllegalArgumentException_if_serverConfig_is_null() {
        // given
        EndpointMetricsHandlerDefaultImpl newImpl = new EndpointMetricsHandlerDefaultImpl();

        // when
        Throwable ex = catchThrowable(() -> newImpl.setupEndpointsMetrics(null, metricRegistryMock));

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("ServerConfig cannot be null");
    }

    @Test
    public void setupEndpointsMetrics_throws_IllegalArgumentException_if_metricRegistry_is_null() {
        // given
        EndpointMetricsHandlerDefaultImpl newImpl = new EndpointMetricsHandlerDefaultImpl();

        // when
        Throwable ex = catchThrowable(() -> newImpl.setupEndpointsMetrics(serverConfig, null));

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("MetricRegistry cannot be null");
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
    public void handleRequest_works_as_expected_for_request_that_hit_an_endpoint(
        String requestMethodStr, int responseStatusCode
    ) throws InterruptedException {
        // given
        HttpMethod requestMethod = (requestMethodStr == null) ? null : HttpMethod.valueOf(requestMethodStr);
        doReturn(requestMethod).when(requestInfoMock).getMethod();

        Endpoint<?> endpoint = serverConfig.appEndpoints().iterator().next();
        String matchingPathTemplate = "/" + UUID.randomUUID().toString();
        state.setEndpointForExecution(endpoint, matchingPathTemplate);

        String endpointTimerAndMeterKey = instance.getTimerAndMeterMapKeyForEndpoint(endpoint);

        int responseHttpStatusCodeXXValue = responseStatusCode / 100;
        long elapsedTimeMillis = 42;

        // when
        instance.handleRequest(
            requestInfoMock, responseInfoMock, state, responseStatusCode, responseHttpStatusCodeXXValue,
            elapsedTimeMillis
        );

        // then
        // The all-requests timer should be updated with the elapsed time of the request
        verify(instance.requests).update(elapsedTimeMillis, TimeUnit.MILLISECONDS);

        // The timer for the relevant HTTP method for this request should be updated with the elapsed time of the request
        verify(expectedRequestTimer(requestMethod, instance)).update(elapsedTimeMillis, TimeUnit.MILLISECONDS);

        {
            // The timer for the endpoint for this request should be updated with the elapsed time of the request
            Timer expectedEndpointTimerUsed = instance.endpointRequestsTimers.get(endpointTimerAndMeterKey);
            assertThat(expectedEndpointTimerUsed).isNotNull();

            verify(expectedEndpointTimerUsed).update(elapsedTimeMillis, TimeUnit.MILLISECONDS);
        }

        final int httpStatusCodeXXValue = responseStatusCode / 100;
        if (httpStatusCodeXXValue >= 1 && httpStatusCodeXXValue <= 5) {
            // Inside the normal 1xx-5xx response codes.

            // The correct 1xx, 2xx, 3xx, 4xx, or 5xx meter for all requests should be marked.
            verify(instance.responses[httpStatusCodeXXValue - 1]).mark();

            // The correct 1xx, 2xx, 3xx, 4xx, or 5xx meter for this request's endpoint should be marked.
            Meter[] endpointResponseMeterArray = instance.endpointResponsesMeters.get(endpointTimerAndMeterKey);
            assertThat(endpointResponseMeterArray).isNotNull();
            verify(endpointResponseMeterArray[httpStatusCodeXXValue - 1]).mark();
        }
        else {
            // Outside the normal 1xx-5xx response codes, so none of the response meters should have been modified.
            instance.endpointResponsesMeters.values().forEach(
                meterArray -> Stream.of(meterArray).forEach(Mockito::verifyZeroInteractions)
            );
        }
    }

    @DataProvider(value = {
        "200",
        "404",
        "405",
        "500"
    })
    @Test
    public void handleRequest_works_as_expected_for_request_that_do_not_hit_an_endpoint(int responseStatusCode) {
        // given
        state.setEndpointForExecution(null, null);

        String expectedTimerKey;
        switch(responseStatusCode) {
            case 404:
                expectedTimerKey = ENDPOINT_NOT_FOUND_MAP_KEY;
                break;
            case 405:
                expectedTimerKey = METHOD_NOT_ALLOWED_MAP_KEY;
                break;
            case 500:
                expectedTimerKey = ROUTING_ERROR_MAP_KEY;
                break;
            default:
                expectedTimerKey = NO_ENDPOINT_SHORT_CIRCUIT_KEY;
        }

        Timer expectedEndpointTimerToUpdate = instance.endpointRequestsTimers.get(expectedTimerKey);
        assertThat(expectedEndpointTimerToUpdate).isNotNull();
        
        int responseHttpStatusCodeXXValue = responseStatusCode / 100;
        long elapsedTimeMillis = 42;

        // when
        instance.handleRequest(
            requestInfoMock, responseInfoMock, state, responseStatusCode, responseHttpStatusCodeXXValue,
            elapsedTimeMillis
        );

        // then
        // The special timer for this use case should be updated with the elapsed time of the request
        verify(expectedEndpointTimerToUpdate).update(elapsedTimeMillis, TimeUnit.MILLISECONDS);

        final int httpStatusCodeXXValue = responseStatusCode / 100;
        // The correct 1xx, 2xx, 3xx, 4xx, or 5xx meter for all requests should be marked.
        verify(instance.responses[httpStatusCodeXXValue - 1]).mark();

        // The correct 1xx, 2xx, 3xx, 4xx, or 5xx meter for this request's non-endpoint should be marked.
        Meter[] nonEndpointResponseMeterArray = instance.endpointResponsesMeters.get(expectedTimerKey);
        assertThat(nonEndpointResponseMeterArray).isNotNull();
        int meterIndexToUse = (NO_ENDPOINT_SHORT_CIRCUIT_KEY.equals(expectedTimerKey)) ? httpStatusCodeXXValue - 1 : 0;
        verify(nonEndpointResponseMeterArray[meterIndexToUse]).mark();
    }

    private Timer expectedRequestTimer(HttpMethod m, EndpointMetricsHandlerDefaultImpl impl) {
        if (m == null) {
            return impl.otherRequests;
        }
        else {
            if (m.equals(HttpMethod.GET))
                return impl.getRequests;
            else if (m.equals(HttpMethod.POST))
                return impl.postRequests;
            else if (m.equals(HttpMethod.PUT))
                return impl.putRequests;
            else if (m.equals(HttpMethod.DELETE))
                return impl.deleteRequests;
            else
                return impl.otherRequests;
        }
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