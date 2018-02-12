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
import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reservoir;
import com.codahale.metrics.SlidingTimeWindowReservoir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.codahale.metrics.MetricRegistry.name;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ServerConfigMetricNames.BOSS_THREADS;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ServerConfigMetricNames.ENDPOINTS;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ServerConfigMetricNames.MAX_REQUEST_SIZE_IN_BYTES;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ServerConfigMetricNames.WORKER_THREADS;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ServerStatisticsMetricNames.FAILED_REQUESTS;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ServerStatisticsMetricNames.INFLIGHT_REQUESTS;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ServerStatisticsMetricNames.PROCESSED_REQUESTS;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ServerStatisticsMetricNames.REQUEST_SIZES;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ServerStatisticsMetricNames.RESPONSE_SIZES;
import static com.nike.riposte.metrics.codahale.CodahaleMetricsListener.ServerStatisticsMetricNames.RESPONSE_WRITE_FAILED;

/**
 * Codahale-based {@link MetricsListener}. <b>Two things must occur during app startup for this class to be fully
 * functional:</b>
 *
 * <pre>
 * <ul>
 *     <li>
 *          You must expose the singleton instance of this class for your app via
 *          {@link ServerConfig#metricsListener()}. If you fail to do this then no metrics will ever be updated.
 *     </li>
 *     <li>
 *          {@link #initEndpointAndServerConfigMetrics(ServerConfig)} must be called independently at some point during
 *          app startup once you have a handle on the finalized {@link ServerConfig} that has all its values set and
 *          exposes all its endpoints via {@link ServerConfig#appEndpoints()}. If you fail to do this then you will not
 *          receive any server config or per-endpoint metrics.
 *     </li>
 * </ul>
 * </pre>
 *
 * It's recommended that you use the {@link Builder} for creating an instance of this class which will let you adjust
 * any behavior options.
 */
@SuppressWarnings("WeakerAccess")
public class CodahaleMetricsListener implements MetricsListener {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final CodahaleMetricsCollector metricsCollector;

    // Aggregate metrics around "server statistics" (requests and data that the server is handling) - not related to
    //      specific endpoints. These are generic enough that we can handle them here in this class and don't need to
    //      farm them out to something separate like EndpointMetricsHandler.
    protected Counter inflightRequests;
    protected Counter processedRequests;
    protected Counter failedRequests;
    protected Counter responseWriteFailed;
    protected Histogram responseSizes;
    protected Histogram requestSizes;

    // Endpoint related metrics are handled by a EndpointMetricsHandler impl.
    protected final EndpointMetricsHandler endpointMetricsHandler;

    protected final boolean includeServerConfigMetrics;

    protected final MetricNamingStrategy<ServerStatisticsMetricNames> serverStatsMetricNamingStrategy;
    protected final MetricNamingStrategy<ServerConfigMetricNames> serverConfigMetricNamingStrategy;

    public static final Supplier<Histogram> DEFAULT_REQUEST_AND_RESPONSE_SIZE_HISTOGRAM_SUPPLIER =
        () -> new Histogram(new ExponentiallyDecayingReservoir());

    protected final Supplier<Histogram> requestAndResponseSizeHistogramSupplier;

    /**
     * Constructor that uses the given {@link CodahaleMetricsCollector} and defaults for everything else -
     * see the {@link CodahaleMetricsListener#CodahaleMetricsListener(CodahaleMetricsCollector, EndpointMetricsHandler,
     * boolean, MetricNamingStrategy, MetricNamingStrategy, Supplier) kitchen sink constructor} for info on defaults.
     *
     * @param cmc A {@link CodahaleMetricsCollector} instance which houses the registry to which server metrics will be
     * sent. Cannot be null - an {@link IllegalArgumentException} will be thrown if you pass in null.
     */
    public CodahaleMetricsListener(CodahaleMetricsCollector cmc) {
        this(cmc, null, false, null, null, null);
    }

    /**
     * The kitchen sink constructor that allows you to specify all the behavior settings. It's recommended that you
     * use the {@link #newBuilder(CodahaleMetricsCollector)} to construct instances rather than directly calling
     * this constructor.
     *
     * @param cmc A {@link CodahaleMetricsCollector} instance which houses the registry to which server metrics will be
     * sent. Cannot be null - an {@link IllegalArgumentException} will be thrown if you pass in null.
     * @param endpointMetricsHandler The {@link EndpointMetricsHandler} that should be used to track and report
     * endpoint metrics. This can be null - if you pass null a new {@link EndpointMetricsHandlerDefaultImpl} will be
     * used.
     * @param includeServerConfigMetrics Pass in true to have some of the {@link ServerConfig} values exposed via metric
     * gauges, or false to exclude those server config value metrics. These values don't change and are easily logged so
     * creating metrics for them is usually wasteful, therefore it's recommended that you pass in false to exclude them
     * unless you're sure you need them.
     * @param serverStatsMetricNamingStrategy The naming strategy that should be used for the server statistics metrics.
     * This can be null - if it is null then a new {@link DefaultMetricNamingStrategy} will be used.
     * @param serverConfigMetricNamingStrategy The naming strategy that should be used for the {@link ServerConfig}
     * gauge metrics. This can be null - if it is null then a new {@link DefaultMetricNamingStrategy} will be used.
     */
    public CodahaleMetricsListener(CodahaleMetricsCollector cmc,
                                   EndpointMetricsHandler endpointMetricsHandler,
                                   boolean includeServerConfigMetrics,
                                   MetricNamingStrategy<ServerStatisticsMetricNames> serverStatsMetricNamingStrategy,
                                   MetricNamingStrategy<ServerConfigMetricNames> serverConfigMetricNamingStrategy,
                                   Supplier<Histogram> requestAndResponseSizeHistogramSupplier) {
        if (null == cmc) {
            throw new IllegalArgumentException("cmc is required");
        }

        if (endpointMetricsHandler == null)
            endpointMetricsHandler = new EndpointMetricsHandlerDefaultImpl();

        if (serverStatsMetricNamingStrategy == null)
            serverStatsMetricNamingStrategy = MetricNamingStrategy.defaultImpl();

        if (serverConfigMetricNamingStrategy == null) {
            serverConfigMetricNamingStrategy = new DefaultMetricNamingStrategy<>(
                ServerConfig.class.getSimpleName(), DefaultMetricNamingStrategy.DEFAULT_WORD_DELIMITER
            );
        }

        if (requestAndResponseSizeHistogramSupplier == null)
            requestAndResponseSizeHistogramSupplier = DEFAULT_REQUEST_AND_RESPONSE_SIZE_HISTOGRAM_SUPPLIER;

        this.metricsCollector = cmc;
        this.endpointMetricsHandler = endpointMetricsHandler;
        this.includeServerConfigMetrics = includeServerConfigMetrics;
        this.serverStatsMetricNamingStrategy = serverStatsMetricNamingStrategy;
        this.serverConfigMetricNamingStrategy = serverConfigMetricNamingStrategy;
        this.requestAndResponseSizeHistogramSupplier = requestAndResponseSizeHistogramSupplier;

        addServerStatisticsMetrics();
    }

    /**
     * @param cmc The {@link CodahaleMetricsCollector} that should be used. This is a required field and must be
     * non-null by the time {@link Builder#build()} is called or an {@link IllegalArgumentException} will be thrown.
     *
     * @return A new builder for {@link CodahaleMetricsListener}.
     */
    public static Builder newBuilder(CodahaleMetricsCollector cmc) {
        return new Builder(cmc);
    }

    /**
     * Initialize the endpoint and server config metrics. Note that the server config values will not be added if
     * {@link #includeServerConfigMetrics} is false, however {@link
     * EndpointMetricsHandler#setupEndpointsMetrics(ServerConfig, MetricRegistry)} will always be called.
     *
     * @param config The {@link ServerConfig} that contains the endpoints and server config values.
     */
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
        this.inflightRequests = metricsCollector.getNamedCounter(
            serverStatsMetricNamingStrategy.nameFor(INFLIGHT_REQUESTS)
        );
        this.processedRequests = metricsCollector.getNamedCounter(
            serverStatsMetricNamingStrategy.nameFor(PROCESSED_REQUESTS)
        );
        this.failedRequests = metricsCollector.getNamedCounter(
            serverStatsMetricNamingStrategy.nameFor(FAILED_REQUESTS)
        );
        this.responseWriteFailed = metricsCollector.getNamedCounter(
            serverStatsMetricNamingStrategy.nameFor(RESPONSE_WRITE_FAILED)
        );

        this.responseSizes = metricsCollector.registerNamedMetric(
            serverStatsMetricNamingStrategy.nameFor(RESPONSE_SIZES),
            requestAndResponseSizeHistogramSupplier.get()
        );

        this.requestSizes = metricsCollector.registerNamedMetric(
            serverStatsMetricNamingStrategy.nameFor(REQUEST_SIZES),
            requestAndResponseSizeHistogramSupplier.get()
        );
    }

    /**
     * Adds metrics related to the given ServerConfig - usually gauges so you can inspect how the ServerConfig was setup.
     * Usually not needed - better to log this info on startup.
     */
    protected void addServerConfigMetrics(ServerConfig config) {
        // add server config gauges
        metricsCollector.registerNamedMetric(serverConfigMetricNamingStrategy.nameFor(BOSS_THREADS),
                                             (Gauge<Integer>)config::numBossThreads);

        metricsCollector.registerNamedMetric(serverConfigMetricNamingStrategy.nameFor(WORKER_THREADS),
                                             (Gauge<Integer>)config::numWorkerThreads);

        metricsCollector.registerNamedMetric(serverConfigMetricNamingStrategy.nameFor(MAX_REQUEST_SIZE_IN_BYTES),
                                             (Gauge<Integer>)config::maxRequestSizeInBytes);

        List<String> endpointsList =
            config.appEndpoints()
                  .stream()
                  .map(
                      endpoint -> endpoint.getClass().getName() +
                                  "-" + getMatchingHttpMethodsAsCombinedString(endpoint) +
                                  "-" + endpoint.requestMatcher().matchingPathTemplates()
                  )
                  .collect(Collectors.toList());

        metricsCollector.registerNamedMetric(serverConfigMetricNamingStrategy.nameFor(ENDPOINTS),
                                             (Gauge<List<String>>)() -> endpointsList);
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
                inflightRequests.dec();
                processedRequests.inc();
                responseWriteFailed.inc();

                if (logger.isDebugEnabled()) {
                    logger.debug("inflightRequests decremented after response write failure");
                }
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
                processedRequests.inc();

                // Make sure HttpProcessingState, RequestInfo, and ResponseInfo are populated with the things we need.
                RequestInfo<?> requestInfo = httpState.getRequestInfo();
                if (requestInfo == null) {
                    logger.error("Metrics Error: httpState.getRequestInfo() is null");
                    return;
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("inflightRequests decremented {} - URI {} - Thread {}", inflightRequests.getCount(),
                                 requestInfo.getUri(), Thread.currentThread().toString());
                }
                
                ResponseInfo<?> responseInfo = httpState.getResponseInfo();
                if (responseInfo == null) {
                    logger.error("Metrics Error: httpState.getResponseInfo() is null");
                    return;
                }

                // Response end time should already be set by now, but just in case it hasn't (i.e. due to an exception
                //      preventing any response from being sent)...
                httpState.setResponseEndTimeNanosToNowIfNotAlreadySet();
                Long requestElapsedTimeMillis = httpState.calculateTotalRequestTimeMillis();
                if (requestElapsedTimeMillis == null) {
                    // This should only happen if httpState.getRequestStartTimeNanos() is null,
                    //      which means AccessLogStartHandler never executed for a Netty HttpRequest message. Something
                    //      went really wrong with this request.
                    logger.error(
                        "Metrics Error: httpState.calculateTotalRequestTimeMillis() is null. "
                        + "httpState.getRequestStartTimeNanos(): " + httpState.getRequestStartTimeNanos()
                    );
                    return;
                }

                final int responseHttpStatusCode =
                    responseInfo.getHttpStatusCodeWithDefault(ResponseSender.DEFAULT_HTTP_STATUS_CODE);
                final int responseHttpStatusCodeXXValue = responseHttpStatusCode / 100;

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

    public enum ServerStatisticsMetricNames {
        INFLIGHT_REQUESTS,
        PROCESSED_REQUESTS,
        FAILED_REQUESTS,
        RESPONSE_WRITE_FAILED,
        REQUEST_SIZES,
        RESPONSE_SIZES
    }

    public enum ServerConfigMetricNames {
        BOSS_THREADS,
        WORKER_THREADS,
        MAX_REQUEST_SIZE_IN_BYTES,
        ENDPOINTS
    }

    /**
     * Interface describing a naming strategy for metric names. You can customize the names of the metrics created and
     * tracked by {@link CodahaleMetricsListener} by implementing this interface and passing your custom impl when
     * creating your app's {@link CodahaleMetricsListener}.
     *
     * @param <T> The enum representing the metric names that this instance knows how to handle.
     */
    @FunctionalInterface
    public interface MetricNamingStrategy<T extends Enum> {
        String nameFor(T metricNameEnum);

        /**
         * @return A default {@link DefaultMetricNamingStrategy}.
         */
        static <T extends Enum> MetricNamingStrategy<T> defaultImpl() {
            return new DefaultMetricNamingStrategy<>();
        }

        /**
         * @return A {@link DefaultMetricNamingStrategy} that has no prefix but is otherwise default.
         */
        static <T extends Enum> MetricNamingStrategy<T> defaultNoPrefixImpl() {
            return defaultImpl(null, DefaultMetricNamingStrategy.DEFAULT_WORD_DELIMITER);
        }

        /**
         * @return A {@link DefaultMetricNamingStrategy} with the given prefix and word delimiter settings.
         */
        static <T extends Enum> MetricNamingStrategy<T> defaultImpl(String prefix, String wordDelimiter) {
            return new DefaultMetricNamingStrategy<>(prefix, wordDelimiter);
        }
    }

    /**
     * Default implementation of {@link MetricNamingStrategy}. This impl takes the enum passed into {@link
     * #nameFor(Enum)} and lowercases its name to generate the name for the metric. There are two options you can use
     * to adjust this class' behavior:
     *
     * <ul>
     *     <li>
     *          You can set an optional {@link #prefix} that will be prepended to the metric name, with a dot separating
     *          the prefix and the rest of the metric name. If you want to omit the prefix entirely then pass in null
     *          or the empty string.
     *     </li>
     *     <li>
     *         You can set an optional {@link #wordDelimiter} that will replace any underscores found in the metric
     *         enum names. By default this is set to "_", effectively leaving underscores as they are. If you want to
     *         omit the delimiter entirely then pass in null or the empty string.
     *     </li>
     * </ul>
     *
     * @param <T> The enum representing the metric names that this instance knows how to handle.
     */
    public static class DefaultMetricNamingStrategy<T extends Enum> implements MetricNamingStrategy<T> {
        public static final String DEFAULT_PREFIX = CodahaleMetricsListener.class.getSimpleName();
        public static final String DEFAULT_WORD_DELIMITER = "_";

        protected final String prefix;
        protected final String wordDelimiter;

        public DefaultMetricNamingStrategy() {
            this(DEFAULT_PREFIX, DEFAULT_WORD_DELIMITER);
        }

        public DefaultMetricNamingStrategy(String prefix, String wordDelimiter) {
            if (wordDelimiter == null)
                wordDelimiter = "";

            this.prefix = prefix;
            this.wordDelimiter = wordDelimiter;
        }

        @Override
        public String nameFor(T metricNameEnum) {
            return name(prefix, metricNameEnum.name().toLowerCase().replace("_", wordDelimiter));
        }
    }

    /**
     * Builder class for {@link CodahaleMetricsListener}.
     */
    public static class Builder {

        private CodahaleMetricsCollector metricsCollector;
        private EndpointMetricsHandler endpointMetricsHandler;
        private boolean includeServerConfigMetrics = false;
        private MetricNamingStrategy<ServerStatisticsMetricNames> serverStatsMetricNamingStrategy;
        private MetricNamingStrategy<ServerConfigMetricNames> serverConfigMetricNamingStrategy;
        private Supplier<Histogram> requestAndResponseSizeHistogramSupplier;

        private Builder(CodahaleMetricsCollector cmc) {
            this.metricsCollector = cmc;
        }

        /**
         * Sets the {@link CodahaleMetricsCollector} instance which houses the registry to which server metrics will be
         * sent. Cannot be null - a {@link IllegalArgumentException} will be thrown when {@link #build()} is called
         * if this is null.
         *
         * @param metricsCollector
         *     the {@code metricsCollector} to set
         *
         * @return A reference to this Builder.
         */
        public Builder withMetricsCollector(CodahaleMetricsCollector metricsCollector) {
            this.metricsCollector = metricsCollector;
            return this;
        }

        /**
         * Sets the {@link EndpointMetricsHandler} that should be used for tracking and reporting endpoint metrics.
         * This can be null - a new {@link EndpointMetricsHandlerDefaultImpl} will be used if you don't specify a
         * custom instance.
         *
         * @param endpointMetricsHandler
         *     the {@code endpointMetricsHandler} to set
         *
         * @return A reference to this Builder.
         */
        public Builder withEndpointMetricsHandler(EndpointMetricsHandler endpointMetricsHandler) {
            this.endpointMetricsHandler = endpointMetricsHandler;
            return this;
        }

        /**
         * Pass in true to have some of the {@link ServerConfig} values exposed via metric gauges, or false to exclude
         * those server config value metrics. These values don't change and are easily logged so creating metrics for
         * them is usually wasteful, therefore it's recommended that you leave this as false to exclude them unless
         * you're sure you need them (false will be used by default if you don't specify anything).
         *
         * @param includeServerConfigMetrics
         *     the {@code includeServerConfigMetrics} to set
         *
         * @return A reference to this Builder.
         */
        public Builder withIncludeServerConfigMetrics(boolean includeServerConfigMetrics) {
            this.includeServerConfigMetrics = includeServerConfigMetrics;
            return this;
        }

        /**
         * Sets the naming strategy that should be used for the server statistics metrics. This can be null - if it is
         * null then a new {@link DefaultMetricNamingStrategy} will be used.
         *
         * @param serverStatsMetricNamingStrategy
         *     the {@code serverStatsMetricNamingStrategy} to set
         *
         * @return a reference to this Builder
         */
        public Builder withServerStatsMetricNamingStrategy(
            MetricNamingStrategy<ServerStatisticsMetricNames> serverStatsMetricNamingStrategy
        ) {
            this.serverStatsMetricNamingStrategy = serverStatsMetricNamingStrategy;
            return this;
        }

        /**
         * Sets the naming strategy that should be used for the {@link ServerConfig} gauge metrics. This can be null -
         * if it is null then a new {@link DefaultMetricNamingStrategy} will be used.
         *
         * @param serverConfigMetricNamingStrategy
         *     the {@code serverConfigMetricNamingStrategy} to set
         *
         * @return a reference to this Builder
         */
        public Builder withServerConfigMetricNamingStrategy(
            MetricNamingStrategy<ServerConfigMetricNames> serverConfigMetricNamingStrategy
        ) {
            this.serverConfigMetricNamingStrategy = serverConfigMetricNamingStrategy;
            return this;
        }

        /**
         * Sets the supplier that should be used to create the request-size and response-size Histogram metrics. You
         * might want to set this in order to change the {@link Reservoir} type used by the histograms to a {@link
         * SlidingTimeWindowReservoir} with the sliding window time values that match your reporter's update frequency.
         * i.e. if you passed in a supplier like this:
         * {@code () -> new Histogram(new SlidingTimeWindowReservoir(10L, TimeUnit.SECONDS))} then the request-size and
         * response-size histograms generated would always give you data for *only* the 10 seconds previous to whenever
         * you requested the data. This can be null - if it is null then {@link
         * #DEFAULT_REQUEST_AND_RESPONSE_SIZE_HISTOGRAM_SUPPLIER} will be used.
         *
         * @param requestAndResponseSizeHistogramSupplier The supplier to use when generating the request-size and
         * response-size {@link Histogram}s.
         * @return a reference to this Builder
         */
        public Builder withRequestAndResponseSizeHistogramSupplier(
            Supplier<Histogram> requestAndResponseSizeHistogramSupplier
        ) {
            this.requestAndResponseSizeHistogramSupplier = requestAndResponseSizeHistogramSupplier;
            return this;
        }

        /**
         * @return a {@link CodahaleMetricsListener} built with parameters from this builder.
         */
        public CodahaleMetricsListener build() {
            return new CodahaleMetricsListener(
                metricsCollector, endpointMetricsHandler, includeServerConfigMetrics, serverStatsMetricNamingStrategy,
                serverConfigMetricNamingStrategy, requestAndResponseSizeHistogramSupplier
            );
        }

    }
}
