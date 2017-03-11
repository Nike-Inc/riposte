package com.nike.riposte.metrics.codahale;

import com.nike.internal.util.StringUtils;
import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.metrics.codahale.impl.EndpointMetricsHandlerDefaultImpl;
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
import com.codahale.metrics.MetricRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Codahale based metrics listener that create all metrics and also handles the metrics events.
 */
@SuppressWarnings("WeakerAccess")
public class CodahaleMetricsListener implements MetricsListener {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final String prefix = this.getClass().getSimpleName();

    protected final CodahaleMetricsCollector metricsCollector;

    // Aggregate metrics around "server statistics" (requests and data that the server is handling) - not related to
    //      specific endpoints. Inspired by metrics exposed by RxNetty. These are generic enough that we can handle
    //      them here in this class and don't need to farm them out to something separate like EndpointMetricsHandler.
    protected Counter inflightRequests;
    protected Counter processedRequests;
    protected Counter failedRequests;
    protected Counter responseWriteFailed;
    protected Histogram responseSizes;
    protected Histogram requestSizes;

    // Endpoint related metrics are handled by a EndpointMetricsHandler impl.
    protected final EndpointMetricsHandler endpointMetricsHandler;

    protected final boolean includeServerConfigMetrics;

    /**
     * Constructor that uses the given {@link CodahaleMetricsCollector}, but uses a default {@link
     * EndpointMetricsHandlerDefaultImpl} for the endpoint metrics handling and excludes metric gauges that expose
     * {@link ServerConfig} values. If you need different behavior please use one of the other constructors that allows
     * you to override the default behavior.
     *
     * @param cmc A {@link CodahaleMetricsCollector} instance which houses the registry to which server metrics will be
     * sent.
     */
    public CodahaleMetricsListener(CodahaleMetricsCollector cmc) {
        this(cmc, new EndpointMetricsHandlerDefaultImpl());
    }

    /**
     * Constructor that uses the given {@link CodahaleMetricsCollector} and {@link EndpointMetricsHandler}, but excludes
     * metric gauges that expose {@link ServerConfig} values by default. If you need different behavior please use one
     * of the other constructors that allows you to override the default behavior.
     *
     * @param cmc A {@link CodahaleMetricsCollector} instance which houses the registry to which server metrics will be
     * sent.
     * @param endpointMetricsHandler The {@link EndpointMetricsHandler} that should be used to tracking and reporting
     * endpoint metrics.
     */
    public CodahaleMetricsListener(CodahaleMetricsCollector cmc, EndpointMetricsHandler endpointMetricsHandler) {
        this(cmc, endpointMetricsHandler, false);
    }

    /**
     * The kitchen sink constructor that allows you to specify all the behavior settings.
     *
     * @param cmc A {@link CodahaleMetricsCollector} instance which houses the registry to which server metrics will be
     * sent.
     * @param endpointMetricsHandler The {@link EndpointMetricsHandler} that should be used to tracking and reporting
     * endpoint metrics.
     * @param includeServerConfigMetrics Pass in true to have some of the {@link ServerConfig} values exposed via metric
     * gauges, or false to exclude those server config value metrics. These are usually easily logged, making metrics
     * for them wasteful, so it's recommended that you pass in false to exclude them unless you're sure you need them.
     */
    public CodahaleMetricsListener(CodahaleMetricsCollector cmc,
                                   EndpointMetricsHandler endpointMetricsHandler,
                                   boolean includeServerConfigMetrics) {
        if (null == cmc) {
            throw new IllegalArgumentException("cmc is required");
        }
        this.metricsCollector = cmc;

        this.endpointMetricsHandler = endpointMetricsHandler;
        this.includeServerConfigMetrics = includeServerConfigMetrics;

        addServerStatisticsMetrics();
    }

    public void initEndpointAndServerConfigMetrics(ServerConfig config) {
        if (includeServerConfigMetrics)
            addServerConfigMetrics(config);
        
        endpointMetricsHandler.setupEndpointsMetrics(config, metricsCollector.getMetricRegistry());
    }

    protected String getMatchingHttpMethodsAsCombinedString(Endpoint<?> endpoint) {
        if (endpoint.requestMatcher().isMatchAllMethods())
            return "ALL";

        return StringUtils.join(endpoint.requestMatcher().matchingMethods(), ",");
    }

    protected void addServerStatisticsMetrics() {
        // netty
        this.inflightRequests = metricsCollector.getMetricRegistry().counter(name(prefix, "inflight-requests"));
        this.processedRequests = metricsCollector.getMetricRegistry().counter(name(prefix, "processed-requests"));
        this.failedRequests = metricsCollector.getMetricRegistry().counter(name(prefix, "failed-requests"));
        this.responseWriteFailed = metricsCollector.getMetricRegistry().counter(name(prefix, "response-write-failed"));

        this.responseSizes = metricsCollector.getMetricRegistry().histogram(name(prefix, "response-sizes"));
        this.requestSizes = metricsCollector.getMetricRegistry().histogram(name(prefix, "request-sizes"));
    }

    /**
     * Adds metrics related to the given ServerConfig - usually gauges so you can inspect how the ServerConfig was setup.
     * Usually not needed - better to log this info on startup.
     */
    protected void addServerConfigMetrics(ServerConfig config) {
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

                final int responseHttpStatusCode =
                    responseInfo.getHttpStatusCodeWithDefault(ResponseSender.DEFAULT_HTTP_STATUS_CODE);
                final int responseHttpStatusCodeXXValue = responseHttpStatusCode / 100;
                // TODO: We (maybe) shouldn't calculate the elapsed time here (?) -
                //       there should (maybe) be a listener that gets executed when the response finishes
                //       and updates the HTTP state with a new requestEndTime variable.
                final long requestElapsedTimeMillis =
                    Instant.now().toEpochMilli() - httpState.getRequestStartTime().toEpochMilli();

                endpointMetricsHandler.handleRequest(
                    requestInfo, responseInfo, httpState, responseHttpStatusCode, responseHttpStatusCodeXXValue,
                    requestElapsedTimeMillis
                );

                // If the http response status is greater than or equal to 400 then it should be marked as a
                //      failed request.
                if (responseHttpStatusCodeXXValue >= 4) {
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

    public MetricRegistry getMetricRegistry() {
        return metricsCollector.getMetricRegistry();
    }

    public CodahaleMetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public EndpointMetricsHandler getEndpointMetricsHandler() {
        return endpointMetricsHandler;
    }
}
