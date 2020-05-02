package com.nike.riposte.metrics.codahale.impl;

import com.nike.internal.util.Pair;
import com.nike.riposte.metrics.codahale.EndpointMetricsHandler;
import com.nike.riposte.metrics.codahale.contrib.SignalFxReporterFactory;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reservoir;
import com.codahale.metrics.SlidingTimeWindowArrayReservoir;
import com.codahale.metrics.Timer;
import com.signalfx.codahale.metrics.MetricBuilder;
import com.signalfx.codahale.reporter.MetricMetadata;
import com.signalfx.codahale.reporter.SignalFxReporter;

import java.util.concurrent.TimeUnit;

/**
 * SignalFx implementation of {@link EndpointMetricsHandler} that tries to narrow things down to only a few metrics
 * with several relevant dimensions, ideal for building dashboards in the SignalFx UI. By default this handler
 * tracks a dimensioned {@link Timer} named "requests" that tracks endpoint latency over time, with the dimensions
 * described in {@link DefaultMetricDimensionConfigurator DefaultMetricDimensionConfigurator}'s javadocs.
 *
 * <p>You can adjust metric names and/or dimensions by using the {@link
 * SignalFxEndpointMetricsHandler#SignalFxEndpointMetricsHandler(MetricMetadata, MetricRegistry, MetricBuilder,
 * MetricDimensionConfigurator) kitchen-sink constructor} and passing in a custom {@link MetricDimensionConfigurator
 * MetricDimensionConfigurator}s for controlling metric naming and dimensioning behavior. By default
 * {@link #DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR} will be used.
 *
 * <p>Additionally you can control the construction of the {@link Timer} (i.e. to specify the {@link Reservoir} that
 * the timer uses) by passing in a custom {@link MetricBuilder} to the kitchen sink constructor, although it's
 * recommended that you use a {@link RollingWindowTimerBuilder RollingWindowTimerBuilder} configured to match the
 * reporting frequency of your {@link SignalFxReporter}.
 *
 * <p>For example in the use case where you want the default metric naming and dimensioning, but you want to also
 * include additional custom dimensions based some app-specific criteria, you can take advantage of the {@link
 * MetricDimensionConfigurator#chainedWith(MetricDimensionConfigurator)} method:
 *
 * <pre>
 *      new SignalFxEndpointMetricsHandler(
 *          someSignalFxReporterMetricMetadata,
 *          metricRegistry,
 *          new RollingWindowTimerBuilder(reportingFrequencyInterval, reportingTimeUnit),
 *          DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR.chainedWith(someCustomRequestTimerConfigurator)
 *      );
 * </pre>
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class SignalFxEndpointMetricsHandler implements EndpointMetricsHandler {

    protected final MetricMetadata metricMetadata;
    protected final MetricRegistry metricRegistry;

    protected final MetricBuilder<Timer> requestTimerBuilder;
    protected final MetricDimensionConfigurator<Timer> requestTimerDimensionConfigurator;

    /**
     * The default name used for the dimensioned {@link Timer} that tracks endpoint latency.
     */
    public static final String DEFAULT_REQUEST_LATENCY_TIMER_METRIC_NAME = "requests";

    /**
     * The default {@link MetricDimensionConfigurator} that configures the dimensioned {@link Timer} for tracking
     * endpoint latency. The default dimensions included are described in the {@link DefaultMetricDimensionConfigurator}
     * javadocs.
     */
    public static final DefaultMetricDimensionConfigurator<Timer> DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR =
        new DefaultMetricDimensionConfigurator<>(DEFAULT_REQUEST_LATENCY_TIMER_METRIC_NAME);

    /**
     * The default recommended constructor when you don't have any special customizations you want to do. This will
     * setup the endpoint timer with the default dimensions (using {@link
     * #DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR}), and will set it up to use a {@link
     * SlidingTimeWindowArrayReservoir} that matches the given {@link SignalFxReporterFactory#getInterval()} and
     * {@link SignalFxReporterFactory#getTimeUnit()} reporting rate so that the values seen in SignalFx will be accurate
     * and sensible.
     *
     * <p>For more flexibility, please see the {@link
     * SignalFxEndpointMetricsHandler#SignalFxEndpointMetricsHandler(MetricMetadata, MetricRegistry, MetricBuilder,
     * MetricDimensionConfigurator)} constructor.
     *
     * @param signalFxReporterFactory The {@link SignalFxReporterFactory} that will be used for this application.
     * Cannot be null.
     * @param metricRegistry The {@link MetricRegistry} being used for the application that the endpoint timers should
     * be registered under. Cannot be null.
     */
    public SignalFxEndpointMetricsHandler(SignalFxReporterFactory signalFxReporterFactory,
                                          MetricRegistry metricRegistry) {
        this(extractSignalFxReporterFromFactory(signalFxReporterFactory, metricRegistry),
             Pair.of(signalFxReporterFactory.getInterval(), signalFxReporterFactory.getTimeUnit()),
             metricRegistry
        );
    }

    /**
     * Create a new instance for the given {@link SignalFxReporter} reporting with the given frequency. The default
     * metric dimension configurator will be used to set up the endpoint timer dimensions:
     * {@link #DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR}.
     *
     * <p>IMPORTANT NOTE: The given {@code reportingFrequency} must match whatever you used when starting the given
     * {@link SignalFxReporter} (see {@link SignalFxReporter#start(long, TimeUnit)}), or else the data reported to
     * SignalFx may not make sense.
     *
     * @param signalFxReporter The {@link SignalFxReporter} to use for metric metadata. Cannot be null.
     * @param reportingFrequency The frequency that the given {@code signalFxReporter} reports its data to SignalFx.
     * Cannot be null, and the individual values inside the pair cannot be null.
     * @param metricRegistry The {@link MetricRegistry} being used for the application that the endpoint timers should
     * be registered under. Cannot be null.
     */
    public SignalFxEndpointMetricsHandler(SignalFxReporter signalFxReporter,
                                          Pair<Long, TimeUnit> reportingFrequency,
                                          MetricRegistry metricRegistry) {
        this(extractMetricMetadataFromSignalFxReporter(signalFxReporter),
             metricRegistry,
             generateDefaultTimerMetricBuilder(reportingFrequency),
             null);
    }

    /**
     * The kitchen-sink constructor - creates a new instance for the given {@link MetricMetadata}, {@link
     * MetricRegistry}, {@link MetricBuilder}, and {@link MetricDimensionConfigurator}. This constructor allows maximum
     * flexibility in controlling how the endpoint timers behave and operate.
     *
     * @param signalFxReporterMetricMetadata The {@link SignalFxReporter#getMetricMetadata()} to use for metric
     * metadata. Cannot be null.
     * @param metricRegistry The {@link MetricRegistry} being used for the application that the endpoint timers should
     * be registered under. Cannot be null.
     * @param requestTimerBuilder The {@link MetricBuilder} responsible for creating the endpoint timer. It's
     * recommended that you use a {@link RollingWindowTimerBuilder} with a rolling window time value that matches the
     * frequency with which the {@link SignalFxReporter} reports its data back to SignalFx, but if you have a need for
     * something else you can control the {@link Timer} creation with this argument. Cannot be null.
     * @param customRequestTimerDimensionConfigurator A custom {@link MetricDimensionConfigurator} controlling the
     * metric name and dimensions for the {@link Timer} that tracks endpoint latency, or null if you want to use the
     * default {@link #DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR}.
     */
    public SignalFxEndpointMetricsHandler(MetricMetadata signalFxReporterMetricMetadata,
                                          MetricRegistry metricRegistry,
                                          MetricBuilder<Timer> requestTimerBuilder,
                                          MetricDimensionConfigurator<Timer> customRequestTimerDimensionConfigurator
    ) {
        if (signalFxReporterMetricMetadata == null)
            throw new IllegalArgumentException("signalFxReporterMetricMetadata cannot be null");

        if (metricRegistry == null)
            throw new IllegalArgumentException("metricRegistry cannot be null");

        if (requestTimerBuilder == null)
            throw new IllegalArgumentException("requestTimerBuilder cannot be null");

        if (customRequestTimerDimensionConfigurator == null)
            customRequestTimerDimensionConfigurator = DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR;
        
        this.metricMetadata = signalFxReporterMetricMetadata;
        this.metricRegistry = metricRegistry;
        this.requestTimerBuilder = requestTimerBuilder;
        this.requestTimerDimensionConfigurator = customRequestTimerDimensionConfigurator;
    }

    protected static SignalFxReporter extractSignalFxReporterFromFactory(SignalFxReporterFactory factory,
                                                                         MetricRegistry metricRegistry) {
        if (factory == null)
            throw new IllegalArgumentException("SignalFxReporterFactory cannot be null");

        if (metricRegistry == null)
            throw new IllegalArgumentException("MetricRegistry cannot be null");

        return factory.getReporter(metricRegistry);
    }

    protected static MetricMetadata extractMetricMetadataFromSignalFxReporter(SignalFxReporter reporter) {
        if (reporter  == null)
            throw new IllegalArgumentException("SignalFxReporter cannot be null");

        return reporter.getMetricMetadata();
    }

    protected static MetricBuilder<Timer> generateDefaultTimerMetricBuilder(Pair<Long, TimeUnit> reportingFrequency) {
        if (reportingFrequency == null)
            throw new IllegalArgumentException("reportingFrequency cannot be null");

        if (reportingFrequency.getLeft() == null)
            throw new IllegalArgumentException("reportingFrequency amount cannot be null");

        if (reportingFrequency.getRight() == null)
            throw new IllegalArgumentException("reportingFrequency TimeUnit cannot be null");

        return new RollingWindowTimerBuilder(reportingFrequency.getLeft(), reportingFrequency.getRight());
    }

    @Override
    public void setupEndpointsMetrics(ServerConfig config, MetricRegistry metricRegistry) {
        // Nothing to do for SignalFx - we dynamically create/grab the correct metrics at request time.
    }

    @Override
    public void handleRequest(RequestInfo<?> requestInfo,
                              ResponseInfo<?> responseInfo,
                              HttpProcessingState httpState,
                              int responseHttpStatusCode,
                              int responseHttpStatusCodeXXValue,
                              long requestElapsedTimeMillis) {
        Endpoint<?> endpoint = httpState.getEndpointForExecution();
        String endpointClass = (endpoint == null) ? "NONE" : endpoint.getClass().getName();
        String method = (requestInfo.getMethod() == null) ? "NONE" : requestInfo.getMethod().name();
        String matchingPathTemplate = (httpState.getMatchingPathTemplate() == null)
                                      ? "NONE"
                                      : httpState.getMatchingPathTemplate();

        Timer latencyTimer = requestTimerDimensionConfigurator.setupMetricWithDimensions(
            metricMetadata.forBuilder(requestTimerBuilder),
            requestInfo,
            responseInfo,
            httpState,
            responseHttpStatusCode,
            responseHttpStatusCodeXXValue,
            requestElapsedTimeMillis,
            endpoint,
            endpointClass,
            method,
            matchingPathTemplate
        ).createOrGet(metricRegistry);

        latencyTimer.update(requestElapsedTimeMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * A {@link MetricBuilder} for {@link Timer}s that constructs the timer to have a {@link SlidingTimeWindowArrayReservoir}
     * with the given sliding window time values. i.e. if you constructed an instance of this class via {@code
     * new RollingWindowTimerBuilder(10L, TimeUnit.SECONDS} then a timer generated with that instance would always give
     * you data for *only* the 10 seconds previous to whenever you requested the data.
     */
    public static class RollingWindowTimerBuilder implements MetricBuilder<Timer> {
        protected final long amount;
        protected final TimeUnit timeUnit;

        public RollingWindowTimerBuilder(long amount, TimeUnit timeUnit) {
            this.amount = amount;
            this.timeUnit = timeUnit;
        }

        @Override
        public Timer newMetric() {
            return new Timer(new SlidingTimeWindowArrayReservoir(amount, timeUnit));
        }

        @Override
        public boolean isInstance(Metric metric) {
            return Timer.class.isInstance(metric);
        }
    }

    /**
     * A {@link MetricBuilder} for {@link Histogram}s that constructs the histogram to have a {@link
     * SlidingTimeWindowArrayReservoir} with the given sliding window time values. i.e. if you constructed an instance of
     * this class via {@code new RollingWindowHistogramBuilder(10L, TimeUnit.SECONDS} then a histogram generated with
     * that instance would always give you data for *only* the 10 seconds previous to whenever you requested the data.
     */
    public static class RollingWindowHistogramBuilder implements MetricBuilder<Histogram> {
        protected final long amount;
        protected final TimeUnit timeUnit;

        public RollingWindowHistogramBuilder(long amount, TimeUnit timeUnit) {
            this.amount = amount;
            this.timeUnit = timeUnit;
        }

        @Override
        public Histogram newMetric() {
            return new Histogram(new SlidingTimeWindowArrayReservoir(amount, timeUnit));
        }

        @Override
        public boolean isInstance(Metric metric) {
            return Histogram.class.isInstance(metric);
        }
    }

    /**
     * Functional interface for taking in a raw SignalFx {@link MetricMetadata.BuilderTagger} and configuring it
     * with metric name and dimensions (and anything else exposed by that builder). Implementations should use the
     * fluent API of the builder and let it return itself, e.g.:
     * <pre>
     *      MetricMetadata.BuilderTagger&lt;T> setupMetricWithDimensions(MetricMetadata.BuilderTagger&lt;T> rawBuilder, otherargs ...) {
     *          return rawBuilder
     *              .withMetricName("someName")
     *              .withDimension("foo_dimension", calculateFooForRequest(otherargs));
     *      }
     * </pre>
     *
     * @param <T> The type of metric being configured.
     */
    @FunctionalInterface
    public interface MetricDimensionConfigurator<T extends Metric> {

        /**
         * Takes in information about the request and returns a {@link MetricMetadata.BuilderTagger} that will be used
         * to create or get the appropriately-dimensioned timer.
         *
         * @param rawBuilder The brand new {@link MetricMetadata.BuilderTagger}, ready for dimensioning. You should
         * return this using the fluent method API to add metric name, dimensions, etc, i.e.
         * {@code return rawBuilder.withMetricName(...).withDimension(...).with...;}. Will never be null.
         * @param requestInfo The {@link RequestInfo} for the request. Will never be null.
         * @param responseInfo The {@link ResponseInfo} for the response associated with the request. Will never be
         * null.
         * @param httpState The {@link HttpProcessingState} associated with the request. Will never be null.
         * @param responseHttpStatusCode The HTTP status code sent back in the response.
         * @param responseHttpStatusCodeXXValue Which category the response status code falls into,
         * i.e. 1xx, 2xx, 3xx, 4xx, 5xx, etc. This is just a convenience for
         * {@code (int)(responseHttpStatusCode / 100)}.
         * @param elapsedTimeMillis How long the request took in milliseconds.
         * @param endpoint The endpoint that handled the request. This may be null in short-circuit cases where no
         * endpoint was called.
         * @param endpointClass The endpoint class name, or "NONE" in short-circuit cases where no endpoint was called.
         * @param method The HTTP method of the request, or "NONE" if requestInfo.getMethod() is null (this should never
         * happen in practice).
         * @param matchingPathTemplate The URI/path template for the request.
         * @return The {@link MetricMetadata.BuilderTagger} that should be used to create or get the
         * appropriately-dimensioned timer. Implementations should likely use the given rawBuilder with fluent method
         * calls as intended, i.e. {@code return rawBuilder.withMetricName(...).withDimension(...).with...;}
         */
        MetricMetadata.BuilderTagger<T> setupMetricWithDimensions(
            MetricMetadata.BuilderTagger<T> rawBuilder,
            RequestInfo<?> requestInfo,
            ResponseInfo<?> responseInfo,
            HttpProcessingState httpState,
            int responseHttpStatusCode,
            int responseHttpStatusCodeXXValue,
            long elapsedTimeMillis,
            Endpoint<?> endpoint,
            String endpointClass,
            String method,
            String matchingPathTemplate
        );

        /**
         * This method allows you to chain multiple {@link MetricDimensionConfigurator}s together into a single
         * instance. This instance's {@code setupMetricWithDimensions(...)} will be executed before the passed-in
         * argument's {@code setupMetricWithDimensions(...)}. See {@link ChainedMetricDimensionConfigurator} for more
         * details. Note that a new object will be created and returned - this instance and the passed-in instance
         * are not modified.
         *
         * @param chainMe The {@link MetricDimensionConfigurator} that should be chained after this one.
         * @return A new {@link ChainedMetricDimensionConfigurator} that will chain this instance with the given
         * argument.
         */
        default MetricDimensionConfigurator<T> chainedWith(MetricDimensionConfigurator<T> chainMe) {
            return new ChainedMetricDimensionConfigurator<>(this, chainMe);
        }

    }

    /**
     * The default {@link MetricDimensionConfigurator} for endpoint metrics. The metric will have the following
     * dimensions:
     * <pre>
     * <ul>
     *     <li>HTTP Status code for the response.</li>
     *     <li>
     *         Template-ized URI/path of the request. i.e. if the endpoint matcher is setup to match "/foo/bar/{id}/**"
     *         and a request comes in to "/foo/bar/42/baz?thing=stuff", then the dimension would be the path template
     *         ("/foo/bar/{id}/**"), *not* the individual instance ("/foo/bar/42/baz?thing=stuff").
     *     </li>
     *     <li>HTTP method of the request.</li>
     *     <li>Name of the Endpoint class that handled the request.</li>
     * </ul>
     * </pre>
     *
     * @param <T> The type of metric being configured.
     */
    public static class DefaultMetricDimensionConfigurator<T extends Metric> implements MetricDimensionConfigurator<T> {

        /**
         * The default key name for the HTTP status code dimension.
         */
        public static final String DEFAULT_RESPONSE_CODE_DIMENSION_KEY = "response_code";
        /**
         * The default key name for the URI dimension.
         */
        public static final String DEFAULT_URI_DIMENSION_KEY = "uri";
        /**
         * The default key name for the HTTP method dimension.
         */
        public static final String DEFAULT_METHOD_DIMENSION_KEY = "method";
        /**
         * The default key name for the Endpoint class dimension.
         */
        public static final String DEFAULT_ENDPOINT_CLASS_DIMENSION_KEY = "endpoint_class";

        protected final String metricName;
        protected final String responseCodeDimensionKey;
        protected final String uriDimensionKey;
        protected final String methodDimensionKey;
        protected final String endpointClassKey;

        /**
         * Creates a new instance with the given metric name and default dimension key names. If you want to customize
         * dimension key names please use the {@link
         * DefaultMetricDimensionConfigurator#DefaultMetricDimensionConfigurator(String, String, String, String, String)
         * kitchen sink constructor}.
         *
         * @param metricName The name the metric should have.
         */
        public DefaultMetricDimensionConfigurator(String metricName) {
            this(metricName, DEFAULT_RESPONSE_CODE_DIMENSION_KEY, DEFAULT_URI_DIMENSION_KEY,
                 DEFAULT_METHOD_DIMENSION_KEY, DEFAULT_ENDPOINT_CLASS_DIMENSION_KEY);
        }

        /**
         * The kitchen sink constructor - allows you to set all values.
         *
         * @param metricName The name the metric should have.
         * @param responseCodeDimensionKey The key name you want to use for the HTTP status code dimension.
         * @param uriDimensionKey The key name you want to use for the URI dimension.
         * @param methodDimensionKey The key name you want to use for the
         * @param endpointClassKey The key name you want to use for the
         */
        public DefaultMetricDimensionConfigurator(String metricName,
                                                  String responseCodeDimensionKey,
                                                  String uriDimensionKey,
                                                  String methodDimensionKey,
                                                  String endpointClassKey) {
            this.metricName = metricName;
            this.responseCodeDimensionKey = responseCodeDimensionKey;
            this.uriDimensionKey = uriDimensionKey;
            this.methodDimensionKey = methodDimensionKey;
            this.endpointClassKey = endpointClassKey;
        }

        @Override
        public MetricMetadata.BuilderTagger<T> setupMetricWithDimensions(
            MetricMetadata.BuilderTagger<T> rawBuilder,
            RequestInfo<?> requestInfo,
            ResponseInfo<?> responseInfo,
            HttpProcessingState httpState,
            int responseHttpStatusCode,
            int responseHttpStatusCodeXXValue,
            long elapsedTimeMillis,
            Endpoint<?> endpoint,
            String endpointClass,
            String method,
            String matchingPathTemplate
        ) {
            return rawBuilder
                .withMetricName(metricName)
                .withDimension(responseCodeDimensionKey, String.valueOf(responseHttpStatusCode))
                .withDimension(uriDimensionKey, matchingPathTemplate)
                .withDimension(methodDimensionKey, method)
                .withDimension(endpointClassKey, endpointClass);
        }
    }

    /**
     * This class allows you to chain multiple {@link MetricDimensionConfigurator}s together into a single instance.
     * e.g.:
     * <pre>
     *      MetricDimensionConfigurator&lt;Timer> timerConfiguratorA = ...;
     *      MetricDimensionConfigurator&lt;Timer> timerConfiguratorB = ...;
     *      MetricDimensionConfigurator&lt;Timer> chainedTimerConfigurator =
     *          new ChainedMetricDimensionConfigurator<>(timerConfiguratorA, timerConfiguratorB);
     * </pre>
     * In this case, when {@code chainedTimerConfigurator.setupMetricWithDimensions(...)} is called, the returned
     * {@link MetricMetadata.BuilderTagger} will be the result of calling {@code setupMetricWithDimensions(...)} on
     * {@code timerConfiguratorA}, then {@code timerConfiguratorB}.
     *
     * @param <T> The type of metric being configured.
     */
    public static class ChainedMetricDimensionConfigurator<T extends Metric> implements MetricDimensionConfigurator<T> {

        protected final MetricDimensionConfigurator<T> firstConfigurator;
        protected final MetricDimensionConfigurator<T> secondConfigurator;

        public ChainedMetricDimensionConfigurator(
            MetricDimensionConfigurator<T> firstConfigurator,
            MetricDimensionConfigurator<T> secondConfigurator
        ) {
            this.firstConfigurator = firstConfigurator;
            this.secondConfigurator = secondConfigurator;
        }

        @Override
        public MetricMetadata.BuilderTagger<T> setupMetricWithDimensions(MetricMetadata.BuilderTagger<T> rawBuilder,
                                                                         RequestInfo<?> requestInfo,
                                                                         ResponseInfo<?> responseInfo,
                                                                         HttpProcessingState httpState,
                                                                         int responseHttpStatusCode,
                                                                         int responseHttpStatusCodeXXValue,
                                                                         long elapsedTimeMillis, Endpoint<?> endpoint,
                                                                         String endpointClass, String method,
                                                                         String matchingPathTemplate) {
            // Execute the first configurator.
            MetricMetadata.BuilderTagger<T> withFirstConfiguration = firstConfigurator.setupMetricWithDimensions(
                rawBuilder, requestInfo, responseInfo, httpState, responseHttpStatusCode, responseHttpStatusCodeXXValue,
                elapsedTimeMillis, endpoint, endpointClass, method, matchingPathTemplate
            );

            // Execute the second configurator with the result of the first.
            return secondConfigurator.setupMetricWithDimensions(
                withFirstConfiguration, requestInfo, responseInfo, httpState, responseHttpStatusCode,
                responseHttpStatusCodeXXValue, elapsedTimeMillis, endpoint, endpointClass, method, matchingPathTemplate
            );
        }

    }
}
