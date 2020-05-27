package com.nike.riposte.metrics.codahale.impl;

import com.nike.internal.util.StringUtils;
import com.nike.riposte.metrics.codahale.CodahaleMetricsListener;
import com.nike.riposte.metrics.codahale.EndpointMetricsHandler;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reservoir;
import com.codahale.metrics.SlidingTimeWindowArrayReservoir;
import com.codahale.metrics.Timer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Default implementation of {@link EndpointMetricsHandler} that uses Graphite-style naming conventions and assumes
 * no dimension metadata for the metrics. Think of this as the metrics firehose with really verbose metric names -
 * you'll get *all* the data, and all the info about what the metric is measuring is embedded in the metric name.
 *
 * <p>Note that by default regular {@link Timer}s will be created and used, which means they will track all-time data
 * (this is because by default {@link Timer} uses {@link ExponentiallyDecayingReservoir} which tries to weight itself
 * towards newer data but gives no guarantees, and also expects a normal distribution to guarantee its accuracy, which
 * is not usually how endpoint latency is distributed). You can customize the timer behavior by using the
 * {@link EndpointMetricsHandlerDefaultImpl#EndpointMetricsHandlerDefaultImpl(Supplier)} constructor, passing in a
 * custom {@link Supplier} for generating {@link Timer}s. For example you could use the {@link Timer#Timer(Reservoir)}
 * constructor and pass in a {@link SlidingTimeWindowArrayReservoir to get much better accuracy over whatever rolling
 * time window you choose.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class EndpointMetricsHandlerDefaultImpl implements EndpointMetricsHandler {

    protected final String prefix = CodahaleMetricsListener.class.getSimpleName();

    // Aggregate timing metrics on requests broken out by HTTP method.
    protected Timer getRequests;
    protected Timer postRequests;
    protected Timer putRequests;
    protected Timer deleteRequests;
    protected Timer otherRequests;

    // Aggregate timing metrics across all endpoints.
    protected Timer requests;
    // Aggregate metrics across all responses.
    protected Meter[] responses;

    protected static final String ROUTING_ERROR_MAP_KEY = "500-routing-error";
    protected static final String METHOD_NOT_ALLOWED_MAP_KEY = "405-method-not-allowed";
    protected static final String ENDPOINT_NOT_FOUND_MAP_KEY = "404-not-found";
    protected static final String NO_ENDPOINT_SHORT_CIRCUIT_KEY = "short-circuit";
    // Per-endpoint request and response metrics.
    protected Map<String, Timer> endpointRequestsTimers = new HashMap<>();
    protected Map<String, Meter[]> endpointResponsesMeters = new HashMap<>();

    protected final Supplier<Timer> requestTimerGenerator;
    public static final Supplier<Timer> DEFAULT_REQUEST_TIMER_GENERATOR = Timer::new;

    /**
     * Create a default instance that uses {@link #DEFAULT_REQUEST_TIMER_GENERATOR} for creating new timers. Use the
     * other constructor if you want to customize timer creation. See the class-level javadocs for this class for
     * info on why you might want to customize timer creation.
     */
    public EndpointMetricsHandlerDefaultImpl() {
        this(null);
    }

    /**
     * Create a new instance that uses the given supplier for creating {@link Timer}s. See the class-level javadocs for
     * this class for info on why you might want to customize timer creation.
     *
     * @param requestTimerGenerator The supplier that should be used to generate new {@link Timer}s.
     */
    public EndpointMetricsHandlerDefaultImpl(Supplier<Timer> requestTimerGenerator) {
        if (requestTimerGenerator == null)
            requestTimerGenerator = DEFAULT_REQUEST_TIMER_GENERATOR;
        
        this.requestTimerGenerator = requestTimerGenerator;
    }

    @Override
    public void setupEndpointsMetrics(ServerConfig config, MetricRegistry metricRegistry) {
        if (config == null)
            throw new IllegalArgumentException("ServerConfig cannot be null");

        if (metricRegistry == null)
            throw new IllegalArgumentException("MetricRegistry cannot be null");

        setupEndpointSpecificMetrics(config, metricRegistry);
        setupEndpointAggregateMetrics(metricRegistry);
    }

    protected Timer createAndRegisterRequestTimer(String name, MetricRegistry registry) {
        Timer newTimer = requestTimerGenerator.get();
        return registry.register(name, newTimer);
    }

    protected void setupEndpointSpecificMetrics(ServerConfig config, MetricRegistry metricRegistry) {
        endpointRequestsTimers = new HashMap<>();
        endpointResponsesMeters = new HashMap<>();
        for (Endpoint<?> endpoint : config.appEndpoints()) {
            String timerAndMeterMapKeyForEndpoint = getTimerAndMeterMapKeyForEndpoint(endpoint);
            String name = name(prefix, "endpoints." + endpoint.getClass().getName().replace(".", "-") + "-"
                                       + timerAndMeterMapKeyForEndpoint);

            endpointRequestsTimers.put(timerAndMeterMapKeyForEndpoint, createAndRegisterRequestTimer(name, metricRegistry));

            endpointResponsesMeters.put(
                timerAndMeterMapKeyForEndpoint,
                new Meter[]{
                    metricRegistry.meter(name(name, "1xx-responses")), // 1xx
                    metricRegistry.meter(name(name, "2xx-responses")), // 2xx
                    metricRegistry.meter(name(name, "3xx-responses")), // 3xx
                    metricRegistry.meter(name(name, "4xx-responses")), // 4xx
                    metricRegistry.meter(name(name, "5xx-responses"))  // 5xx
                });
        }

        // Add stuff for 404 requests
        {
            String notFoundNameId = name(prefix, "endpoints." + ENDPOINT_NOT_FOUND_MAP_KEY);
            endpointRequestsTimers
                .put(ENDPOINT_NOT_FOUND_MAP_KEY, createAndRegisterRequestTimer(notFoundNameId, metricRegistry));
            endpointResponsesMeters.put(ENDPOINT_NOT_FOUND_MAP_KEY, new Meter[]{
                metricRegistry.meter(name(notFoundNameId, "4xx-responses"))});
        }

        // Add stuff for 405 requests
        {
            String methodNotAllowedNameId = name(prefix, "endpoints." + METHOD_NOT_ALLOWED_MAP_KEY);
            endpointRequestsTimers
                .put(METHOD_NOT_ALLOWED_MAP_KEY, createAndRegisterRequestTimer(methodNotAllowedNameId, metricRegistry));
            endpointResponsesMeters.put(METHOD_NOT_ALLOWED_MAP_KEY, new Meter[]{
                metricRegistry.meter(name(methodNotAllowedNameId, "4xx-responses"))});
        }

        // Add stuff for 500 routing error requests
        {
            String routingErrorNameId = name(prefix, "endpoints." + ROUTING_ERROR_MAP_KEY);
            endpointRequestsTimers
                .put(ROUTING_ERROR_MAP_KEY, createAndRegisterRequestTimer(routingErrorNameId, metricRegistry));
            endpointResponsesMeters.put(ROUTING_ERROR_MAP_KEY, new Meter[]{
                metricRegistry.meter(name(routingErrorNameId, "5xx-responses"))});
        }

        // Add stuff for miscellaneous short circuits
        {
            String shortCircuitNameId = name(prefix, "endpoints." + NO_ENDPOINT_SHORT_CIRCUIT_KEY);
            endpointRequestsTimers
                .put(NO_ENDPOINT_SHORT_CIRCUIT_KEY, createAndRegisterRequestTimer(shortCircuitNameId, metricRegistry));
            endpointResponsesMeters.put(NO_ENDPOINT_SHORT_CIRCUIT_KEY, new Meter[]{
                metricRegistry.meter(name(shortCircuitNameId, "1xx-responses")), // 1xx
                metricRegistry.meter(name(shortCircuitNameId, "2xx-responses")), // 2xx
                metricRegistry.meter(name(shortCircuitNameId, "3xx-responses")), // 3xx
                metricRegistry.meter(name(shortCircuitNameId, "4xx-responses")), // 4xx
                metricRegistry.meter(name(shortCircuitNameId, "5xx-responses"))  // 5xx
            });
        }
    }

    protected void setupEndpointAggregateMetrics(MetricRegistry metricRegistry) {
        // codahale
        this.requests = createAndRegisterRequestTimer(name(prefix, "requests"), metricRegistry);
        this.responses = new Meter[]{
            metricRegistry.meter(name(prefix, "1xx-responses")), // 1xx
            metricRegistry.meter(name(prefix, "2xx-responses")), // 2xx
            metricRegistry.meter(name(prefix, "3xx-responses")), // 3xx
            metricRegistry.meter(name(prefix, "4xx-responses")), // 4xx
            metricRegistry.meter(name(prefix, "5xx-responses"))  // 5xx
        };
        this.getRequests = createAndRegisterRequestTimer(name(prefix, "get-requests"), metricRegistry);
        this.postRequests = createAndRegisterRequestTimer(name(prefix, "post-requests"), metricRegistry);
        this.putRequests = createAndRegisterRequestTimer(name(prefix, "put-requests"), metricRegistry);
        this.deleteRequests = createAndRegisterRequestTimer(name(prefix, "delete-requests"), metricRegistry);
        this.otherRequests = createAndRegisterRequestTimer(name(prefix, "other-requests"), metricRegistry);

    }

    protected String getMatchingHttpMethodsAsCombinedString(Endpoint<?> endpoint) {
        if (endpoint.requestMatcher().isMatchAllMethods())
            return "ALL";

        return StringUtils.join(endpoint.requestMatcher().matchingMethods(), ",");
    }
    
    protected String getTimerAndMeterMapKeyForEndpoint(Endpoint<?> endpoint) {
        String methodsString = getMatchingHttpMethodsAsCombinedString(endpoint);
        //TODO: this might be odd for multi-path endpoints
        return methodsString + "-" + endpoint.requestMatcher().matchingPathTemplates();
    }

    @Override
    public void handleRequest(RequestInfo<?> requestInfo,
                              ResponseInfo<?> responseInfo,
                              HttpProcessingState httpState,
                              int responseHttpStatusCode,
                              int responseHttpStatusCodeXXValue,
                              long requestElapsedTimeMillis) {
        // meter request times
        requests.update(requestElapsedTimeMillis, TimeUnit.MILLISECONDS);
        requestTimer(requestInfo.getMethod()).update(requestElapsedTimeMillis, TimeUnit.MILLISECONDS);

        Endpoint<?> endpoint = httpState.getEndpointForExecution();
        final boolean is500RoutingError = responseHttpStatusCode >= 500 && endpoint == null;
        final boolean is405MethodNotAllowed =
            responseHttpStatusCode == HttpResponseStatus.METHOD_NOT_ALLOWED.code() && endpoint == null;
        final boolean is404NotFound =
            responseHttpStatusCode == HttpResponseStatus.NOT_FOUND.code() && endpoint == null;
        String endpointMapKey;
        if (endpoint != null)
            endpointMapKey = getTimerAndMeterMapKeyForEndpoint(endpoint);
        else if (is500RoutingError)
            endpointMapKey = ROUTING_ERROR_MAP_KEY;
        else if (is405MethodNotAllowed)
            endpointMapKey = METHOD_NOT_ALLOWED_MAP_KEY;
        else if (is404NotFound)
            endpointMapKey = ENDPOINT_NOT_FOUND_MAP_KEY;
        else {
            // Throw it into the catch-all short circuit bucket
            endpointMapKey = NO_ENDPOINT_SHORT_CIRCUIT_KEY;
        }

        endpointRequestsTimers.get(endpointMapKey).update(requestElapsedTimeMillis, TimeUnit.MILLISECONDS);

        // meter response codes
        if (responseHttpStatusCodeXXValue >= 1 && responseHttpStatusCodeXXValue <= 5) {
            responses[responseHttpStatusCodeXXValue - 1].mark();
            Meter[] responseMeterArray = endpointResponsesMeters.get(endpointMapKey);
            Meter responseMeter =
                (endpoint == null && !NO_ENDPOINT_SHORT_CIRCUIT_KEY.equals(endpointMapKey))
                ? responseMeterArray[0]
                : responseMeterArray[responseHttpStatusCodeXXValue - 1];
            responseMeter.mark();
        }
    }

    private Timer requestTimer(HttpMethod m) {
        if (m == null) {
            return otherRequests;
        }
        else {
            if (m.equals(HttpMethod.GET))
                return getRequests;
            else if (m.equals(HttpMethod.POST))
                return postRequests;
            else if (m.equals(HttpMethod.PUT))
                return putRequests;
            else if (m.equals(HttpMethod.DELETE))
                return deleteRequests;
            else
                return otherRequests;
        }
    }

    public Timer getGetRequests() {
        return getRequests;
    }

    public Timer getPostRequests() {
        return postRequests;
    }

    public Timer getPutRequests() {
        return putRequests;
    }

    public Timer getDeleteRequests() {
        return deleteRequests;
    }

    public Timer getOtherRequests() {
        return otherRequests;
    }

    public Timer getRequests() {
        return requests;
    }

    public Meter[] getResponses() {
        return responses;
    }

    public Map<String, Timer> getEndpointRequestsTimers() {
        return endpointRequestsTimers;
    }

    public Map<String, Meter[]> getEndpointResponsesMeters() {
        return endpointResponsesMeters;
    }
}
