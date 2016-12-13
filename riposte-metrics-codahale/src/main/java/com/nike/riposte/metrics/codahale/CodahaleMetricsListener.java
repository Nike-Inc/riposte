package com.nike.riposte.metrics.codahale;

import com.nike.internal.util.StringUtils;
import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.metrics.ServerMetricsEvent;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Codahale based metrics listener that create all metrics and also handles the metrics events.
 */
@SuppressWarnings("WeakerAccess")
public class CodahaleMetricsListener implements MetricsListener {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final String prefix = this.getClass().getSimpleName();

    protected final CodahaleMetricsCollector metricsCollector;

    //from RxNetty
    protected Counter inflightRequests;
    protected Counter processedRequests;
    protected Counter failedRequests;
    protected Counter responseWriteFailed;
    protected Histogram responseSizes;
    protected Histogram requestSizes;

    //from Codahale
    protected Timer getRequests;
    protected Timer postRequests;
    protected Timer putRequests;
    protected Timer deleteRequests;
    protected Timer otherRequests;

    // the requests handled by this handler, excluding active
    protected Timer requests;

    protected Meter[] responses;

    protected static final String ROUTING_ERROR_MAP_KEY = "500-routing-error";
    protected static final String METHOD_NOT_ALLOWED_MAP_KEY = "405-method-not-allowed";
    protected static final String ENDPOINT_NOT_FOUND_MAP_KEY = "404-not-found";
    protected static final String NO_ENDPOINT_SHORT_CIRCUIT_KEY = "short-circuit";
    protected Map<String, Timer> endpointRequestsTimers = new HashMap<>();
    protected Map<String, Meter[]> endpointResponsesMeters = new HashMap<>();


    /**
     * @param cmc
     *     A CodahaleMetricsCollector instance which houses the registry to which server metrics will be sent
     */
    public CodahaleMetricsListener(CodahaleMetricsCollector cmc) {
        if (null == cmc) {
            throw new IllegalArgumentException("cmc is required");
        }
        this.metricsCollector = cmc;

        addNettyMetrics();

        addCodahaleMetrics();
    }


    public void initServerConfigMetrics(ServerConfig config) {
        addServerConfigMetrics(config);
        addEndpointsMetrics(config);
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

    private void addEndpointsMetrics(ServerConfig config) {
        endpointRequestsTimers = new HashMap<>();
        endpointResponsesMeters = new HashMap<>();
        for (Endpoint<?> endpoint : config.appEndpoints()) {
            String timerAndMeterMapKeyForEndpoint = getTimerAndMeterMapKeyForEndpoint(endpoint);
            String name = name(prefix, "endpoints." + endpoint.getClass().getName().replace(".", "-") + "-"
                                       + timerAndMeterMapKeyForEndpoint);

            endpointRequestsTimers.put(
                timerAndMeterMapKeyForEndpoint,
                metricsCollector.getMetricRegistry().timer(name));

            endpointResponsesMeters.put(
                timerAndMeterMapKeyForEndpoint,
                new Meter[]{
                    metricsCollector.getMetricRegistry().meter(name(name, "1xx-responses")), // 1xx
                    metricsCollector.getMetricRegistry().meter(name(name, "2xx-responses")), // 2xx
                    metricsCollector.getMetricRegistry().meter(name(name, "3xx-responses")), // 3xx
                    metricsCollector.getMetricRegistry().meter(name(name, "4xx-responses")), // 4xx
                    metricsCollector.getMetricRegistry().meter(name(name, "5xx-responses"))  // 5xx
                });
        }

        // Add stuff for 404 requests
        {
            String notFoundNameId = name(prefix, "endpoints." + ENDPOINT_NOT_FOUND_MAP_KEY);
            endpointRequestsTimers
                .put(ENDPOINT_NOT_FOUND_MAP_KEY, metricsCollector.getMetricRegistry().timer(notFoundNameId));
            endpointResponsesMeters.put(ENDPOINT_NOT_FOUND_MAP_KEY, new Meter[]{
                metricsCollector.getMetricRegistry().meter(name(notFoundNameId, "4xx-responses"))});
        }

        // Add stuff for 405 requests
        {
            String methodNotAllowedNameId = name(prefix, "endpoints." + METHOD_NOT_ALLOWED_MAP_KEY);
            endpointRequestsTimers
                .put(METHOD_NOT_ALLOWED_MAP_KEY, metricsCollector.getMetricRegistry().timer(methodNotAllowedNameId));
            endpointResponsesMeters.put(METHOD_NOT_ALLOWED_MAP_KEY, new Meter[]{
                metricsCollector.getMetricRegistry().meter(name(methodNotAllowedNameId, "4xx-responses"))});
        }

        // Add stuff for 500 routing error requests
        {
            String routingErrorNameId = name(prefix, "endpoints." + ROUTING_ERROR_MAP_KEY);
            endpointRequestsTimers
                .put(ROUTING_ERROR_MAP_KEY, metricsCollector.getMetricRegistry().timer(routingErrorNameId));
            endpointResponsesMeters.put(ROUTING_ERROR_MAP_KEY, new Meter[]{
                metricsCollector.getMetricRegistry().meter(name(routingErrorNameId, "5xx-responses"))});
        }

        // Add stuff for miscellaneous short circuits
        {
            String shortCircuitNameId = name(prefix, "endpoints." + NO_ENDPOINT_SHORT_CIRCUIT_KEY);
            endpointRequestsTimers
                .put(NO_ENDPOINT_SHORT_CIRCUIT_KEY, metricsCollector.getMetricRegistry().timer(shortCircuitNameId));
            endpointResponsesMeters.put(NO_ENDPOINT_SHORT_CIRCUIT_KEY, new Meter[]{
                metricsCollector.getMetricRegistry().meter(name(shortCircuitNameId, "1xx-responses")), // 1xx
                metricsCollector.getMetricRegistry().meter(name(shortCircuitNameId, "2xx-responses")), // 2xx
                metricsCollector.getMetricRegistry().meter(name(shortCircuitNameId, "3xx-responses")), // 3xx
                metricsCollector.getMetricRegistry().meter(name(shortCircuitNameId, "4xx-responses")), // 4xx
                metricsCollector.getMetricRegistry().meter(name(shortCircuitNameId, "5xx-responses"))  // 5xx
            });
        }
    }

    private void addCodahaleMetrics() {
        // codahale
        this.requests = metricsCollector.getMetricRegistry().timer(name(prefix, "requests"));
        this.responses = new Meter[]{
            metricsCollector.getMetricRegistry().meter(name(prefix, "1xx-responses")), // 1xx
            metricsCollector.getMetricRegistry().meter(name(prefix, "2xx-responses")), // 2xx
            metricsCollector.getMetricRegistry().meter(name(prefix, "3xx-responses")), // 3xx
            metricsCollector.getMetricRegistry().meter(name(prefix, "4xx-responses")), // 4xx
            metricsCollector.getMetricRegistry().meter(name(prefix, "5xx-responses"))  // 5xx
        };
        this.getRequests = metricsCollector.getMetricRegistry().timer(name(prefix, "get-requests"));
        this.postRequests = metricsCollector.getMetricRegistry().timer(name(prefix, "post-requests"));
        this.putRequests = metricsCollector.getMetricRegistry().timer(name(prefix, "put-requests"));
        this.deleteRequests = metricsCollector.getMetricRegistry().timer(name(prefix, "delete-requests"));
        this.otherRequests = metricsCollector.getMetricRegistry().timer(name(prefix, "other-requests"));

    }

    private void addNettyMetrics() {
        // netty
        this.inflightRequests = metricsCollector.getMetricRegistry().counter(name(prefix, "inflight-requests"));
        this.processedRequests = metricsCollector.getMetricRegistry().counter(name(prefix, "processed-requests"));
        this.failedRequests = metricsCollector.getMetricRegistry().counter(name(prefix, "failed-requests"));
        this.responseWriteFailed = metricsCollector.getMetricRegistry().counter(name(prefix, "response-write-failed"));

        this.responseSizes = metricsCollector.getMetricRegistry().histogram(name(prefix, "response-sizes"));
        this.requestSizes = metricsCollector.getMetricRegistry().histogram(name(prefix, "request-sizes"));
    }

    private void addServerConfigMetrics(ServerConfig config) {
        // add server config gauges
        Gauge<Integer> bossThreadsGauge = config::numBossThreads;
        metricsCollector.getMetricRegistry()
                        .register(name(ServerConfig.class.getSimpleName(), "bossThreads"), bossThreadsGauge);

        Gauge<Integer> workerThreadsGauge = config::numWorkerThreads;
        metricsCollector.getMetricRegistry()
                        .register(name(ServerConfig.class.getSimpleName(), "workerThreads"), workerThreadsGauge);

        Gauge<Integer> maxRequestSizeInBytesGauge = config::maxRequestSizeInBytes;
        metricsCollector.getMetricRegistry().register(name(ServerConfig.class.getSimpleName(), "maxRequestSizeInBytes"),
                                                      maxRequestSizeInBytesGauge);

        List<String> endpointsList = config.appEndpoints()
                                           .stream()
                                           .map(
                                               endpoint -> endpoint.getClass().getName() +
                                                           "-" + getMatchingHttpMethodsAsCombinedString(endpoint) +
                                                           "-" + endpoint.requestMatcher().matchingPathTemplates()
                                           )
                                           .collect(Collectors.toList());
        Gauge<List<String>> endpointsGauge = () -> endpointsList;

        metricsCollector.getMetricRegistry()
                        .register(name(ServerConfig.class.getSimpleName(), "endpoints"), endpointsGauge);
    }

    @Override
    public void onEvent(ServerMetricsEvent event, Object value) {
        try {
            if (ServerMetricsEvent.REQUEST_RECEIVED.equals(event)) {
                inflightRequests.inc();
                if (logger.isDebugEnabled()) {
                    logger.debug("inflightRequests incremented {} - thread {}", inflightRequests.getCount(),
                                 Thread.currentThread().toString());
                }
            }
            else if (ServerMetricsEvent.RESPONSE_WRITE_FAILED.equals(event)) {
                responseWriteFailed.inc();
            }
            else if (ServerMetricsEvent.RESPONSE_SENT.equals(event)) {
                HttpProcessingState httpState;
                if (value instanceof HttpProcessingState) {
                    httpState = (HttpProcessingState) value;
                }
                else {
                    logger.error("Metrics Error: value is not an HttpProcessingState");
                    return;
                }

                inflightRequests.dec();
                RequestInfo<?> requestInfo = httpState.getRequestInfo();
                if (logger.isDebugEnabled()) {
                    logger.debug("inflightRequests decremented {} - URI {} - Thread {}", inflightRequests.getCount(),
                                 requestInfo.getUri(), Thread.currentThread().toString());
                }
                processedRequests.inc();

                // check state is populated
                ResponseInfo<?> responseInfo = httpState.getResponseInfo();
                if (responseInfo == null) {
                    logger.error("Metrics Error: httpState.getResponseInfo() is null");
                    return;
                }
                if (httpState.getRequestStartTime() == null) {
                    logger.error("Metrics Error: httpState.getRequestStartTime() is null");
                    return;
                }

                // meter request times
                final long elapsedTimeMillis =
                    Instant.now().toEpochMilli() - httpState.getRequestStartTime().toEpochMilli();
                requests.update(elapsedTimeMillis, TimeUnit.MILLISECONDS);
                requestTimer(requestInfo.getMethod()).update(elapsedTimeMillis, TimeUnit.MILLISECONDS);

                final int responseHttpStatusCode =
                    responseInfo.getHttpStatusCodeWithDefault(ResponseSender.DEFAULT_HTTP_STATUS_CODE);
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

                endpointRequestsTimers.get(endpointMapKey).update(elapsedTimeMillis, TimeUnit.MILLISECONDS);

                // meter response codes
                final int httpStatusCodeXXValue = responseHttpStatusCode / 100;
                if (httpStatusCodeXXValue >= 1 && httpStatusCodeXXValue <= 5) {
                    responses[httpStatusCodeXXValue - 1].mark();
                    Meter[] responseMeterArray = endpointResponsesMeters.get(endpointMapKey);
                    Meter responseMeter =
                        (endpoint == null && !NO_ENDPOINT_SHORT_CIRCUIT_KEY.equals(endpointMapKey))
                        ? responseMeterArray[0]
                        : responseMeterArray[httpStatusCodeXXValue - 1];
                    responseMeter.mark();
                }

                // If the http response status is greater than or equal to 400 then it should be marked as a
                //      failed request.
                if (httpStatusCodeXXValue >= 4) {
                    failedRequests.inc();
                }

                // meter request/response sizes
                requestSizes.update(requestInfo.getRawContentLengthInBytes());
                // TODO: Maybe add another metric for the raw uncompressed response length?
                responseSizes
                    .update(responseInfo.getFinalContentLength() == null ? 0 : responseInfo.getFinalContentLength());
            }
            else {
                logger.error("Metrics Error: unknown metrics event " + event);
            }
        }
        catch (Throwable t) {
            logger.error("Metrics Error: ", t);
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

    public Counter getInflightRequests() {
        return inflightRequests;
    }

    public Counter getProcessedRequests() {
        return processedRequests;
    }

    public Counter getFailedRequests() {
        return failedRequests;
    }

    public Counter getResponseWriteFailed() {
        return responseWriteFailed;
    }

    public Histogram getResponseSizes() {
        return responseSizes;
    }

    public Histogram getRequestSizes() {
        return requestSizes;
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

    public MetricRegistry getMetricRegistry() {
        return metricsCollector.getMetricRegistry();
    }

}
