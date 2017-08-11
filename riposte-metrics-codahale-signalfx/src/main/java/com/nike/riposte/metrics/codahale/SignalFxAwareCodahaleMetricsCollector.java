package com.nike.riposte.metrics.codahale;

import com.nike.internal.util.Pair;
import com.nike.riposte.metrics.codahale.contrib.SignalFxReporterFactory;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.RollingWindowHistogramBuilder;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.RollingWindowTimerBuilder;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SlidingTimeWindowReservoir;
import com.codahale.metrics.Timer;
import com.signalfx.codahale.metrics.MetricBuilder;
import com.signalfx.codahale.reporter.MetricMetadata;
import com.signalfx.codahale.reporter.MetricMetadata.BuilderTagger;
import com.signalfx.codahale.reporter.MetricMetadata.Tagger;
import com.signalfx.codahale.reporter.MetricMetadata.TaggerBase;
import com.signalfx.codahale.reporter.SignalFxReporter;

import java.util.Arrays;
import java.util.List;

/**
 * An extension of {@link CodahaleMetricsCollector} that is SignalFx-aware and will use the {@link
 * SignalFxReporter#getMetricMetadata()} when dealing with metrics so that they will be tagged with the appropriate
 * global/unique dimensions.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class SignalFxAwareCodahaleMetricsCollector extends CodahaleMetricsCollector {

    protected final MetricMetadata metricMetadata;
    protected final MetricBuilder<Timer> timerBuilder;
    protected final MetricBuilder<Histogram> histogramBuilder;

    /**
     * Creates a new instance with a *new* {@link MetricRegistry} - this means you should be careful to retrieve it
     * via {@link #getMetricRegistry()} and pass it around anywhere else you need a metric registry in your application.
     * The {@link SignalFxReporterFactory} is used to get the {@link SignalFxReporter#getMetricMetadata()} and reporting
     * frequency.
     *
     * <p>Note that {@link Timer}s and {@link Histogram}s created from this class will have {@link
     * SlidingTimeWindowReservoir} reservoirs that match the given {@link SignalFxReporterFactory#getInterval()} and
     * {@link SignalFxReporterFactory#getTimeUnit()}.
     *
     * @param sfxReporterFactory The {@link SignalFxReporterFactory} to use to get the {@link
     * SignalFxReporter#getMetricMetadata()} and reporting frequency.
     */
    public SignalFxAwareCodahaleMetricsCollector(SignalFxReporterFactory sfxReporterFactory) {
        this(new MetricRegistry(), sfxReporterFactory);
    }

    /**
     * Creates a new instance with the given {@link MetricRegistry}, and using the given {@link SignalFxReporterFactory}
     * to retrieve the {@link SignalFxReporter#getMetricMetadata()} and reporting frequency.
     *
     * <p>Note that {@link Timer}s and {@link Histogram}s created from this class will have {@link
     * SlidingTimeWindowReservoir} reservoirs that match the given {@link SignalFxReporterFactory#getInterval()} and
     * {@link SignalFxReporterFactory#getTimeUnit()}.
     *
     * @param sfxReporterFactory The {@link SignalFxReporterFactory} to use to get the {@link
     * SignalFxReporter#getMetricMetadata()} and reporting frequency.
     */
    public SignalFxAwareCodahaleMetricsCollector(MetricRegistry metricRegistry,
                                                 SignalFxReporterFactory sfxReporterFactory) {
        this(metricRegistry,
             sfxReporterFactory.getReporter(metricRegistry).getMetricMetadata(),
             new RollingWindowTimerBuilder(sfxReporterFactory.getInterval(),
                                           sfxReporterFactory.getTimeUnit()),
             new RollingWindowHistogramBuilder(sfxReporterFactory.getInterval(),
                                               sfxReporterFactory.getTimeUnit())
        );
    }

    /**
     * Creates a new instance with the given values.
     *
     * @param metricRegistry The {@link MetricRegistry} to use when building metrics.
     * @param metricMetadata The SignalFx {@link MetricMetadata} to use to build metrics.
     * @param timerBuilder The timer builder for building new timers. It's recommended that this be a {@link
     * RollingWindowTimerBuilder} that matches the reporting frequency of your app's {@link SignalFxReporter}.
     * @param histogramBuilder The histogram builder for building new histograms. It's recommended that this be a {@link
     * RollingWindowHistogramBuilder} that matches the reporting frequency of your app's {@link SignalFxReporter}.
     */
    public SignalFxAwareCodahaleMetricsCollector(MetricRegistry metricRegistry,
                                                 MetricMetadata metricMetadata,
                                                 MetricBuilder<Timer> timerBuilder,
                                                 MetricBuilder<Histogram> histogramBuilder) {
        super(metricRegistry);
        this.metricMetadata = metricMetadata;
        this.timerBuilder = timerBuilder;
        this.histogramBuilder = histogramBuilder;
    }

    @Override
    public Timer getNamedTimer(String timerName) {
        return getNamedTimer(timerName, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final Timer getNamedTimer(String timerName, Pair<String, String>... dimensions) {
        return getNamedTimer(timerName, convertDimensionsToList(dimensions));
    }

    public Timer getNamedTimer(String timerName, Iterable<Pair<String, String>> dimensions) {
        return getNamedMetric(timerName, timerBuilder, dimensions);
    }

    @Override
    public Meter getNamedMeter(String meterName) {
        return getNamedMeter(meterName, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final Meter getNamedMeter(String meterName, Pair<String, String>... dimensions) {
        return getNamedMeter(meterName, convertDimensionsToList(dimensions));
    }

    public Meter getNamedMeter(String meterName, Iterable<Pair<String, String>> dimensions) {
        return getNamedMetric(meterName, MetricBuilder.METERS, dimensions);
    }

    @Override
    public Counter getNamedCounter(String counterName) {
        return getNamedCounter(counterName, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final Counter getNamedCounter(String counterName, Pair<String, String>... dimensions) {
        return getNamedCounter(counterName, convertDimensionsToList(dimensions));
    }

    public Counter getNamedCounter(String counterName, Iterable<Pair<String, String>> dimensions) {
        return getNamedMetric(counterName, MetricBuilder.COUNTERS, dimensions);
    }

    @Override
    public Histogram getNamedHistogram(String histogramName) {
        return getNamedHistogram(histogramName, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final Histogram getNamedHistogram(String histogramName, Pair<String, String>... dimensions) {
        return getNamedHistogram(histogramName, convertDimensionsToList(dimensions));
    }

    public Histogram getNamedHistogram(String histogramName, Iterable<Pair<String, String>> dimensions) {
        return getNamedMetric(histogramName, histogramBuilder, dimensions);
    }

    public final <M extends Metric> M getNamedMetric(String metricName, MetricBuilder<M> builder) {
        return getNamedMetric(metricName, builder, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final <M extends Metric> M getNamedMetric(
        String metricName, MetricBuilder<M> builder, Pair<String, String>... dimensions
    ) {
        return getNamedMetric(metricName, builder, convertDimensionsToList(dimensions));
    }

    public <M extends Metric> M getNamedMetric(
        String metricName, MetricBuilder<M> builder, Iterable<Pair<String, String>> dimensions
    ) {
        BuilderTagger<M> builderTagger = metricMetadata.forBuilder(builder)
                                                       .withMetricName(metricName);
        builderTagger = addDimensions(builderTagger, dimensions);
        return builderTagger.createOrGet(metricRegistry);
    }

    @Override
    public <M extends Metric> M registerNamedMetric(String metricName, M metric) {
        return registerNamedMetric(metricName, metric, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final <M extends Metric> M registerNamedMetric(String metricName,
                                                          M metric,
                                                          Pair<String, String>... dimensions) {
        return registerNamedMetric(metricName, metric, convertDimensionsToList(dimensions));
    }
    
    public <M extends Metric> M registerNamedMetric(String metricName,
                                                    M metric,
                                                    Iterable<Pair<String, String>> dimensions) {
        Tagger<M> metricTagger = metricMetadata.forMetric(metric)
                                               .withMetricName(metricName);
        addDimensions(metricTagger, dimensions);
        return metricTagger.register(metricRegistry);
    }

    protected List<Pair<String, String>> convertDimensionsToList(Pair<String, String>[] dimensions) {
        return (dimensions == null) ? null : Arrays.asList(dimensions);
    }

    protected <M extends Metric, T extends TaggerBase<M, ?>> T addDimensions(
        T builder, Iterable<Pair<String, String>> dimensions
    ) {
        if (dimensions == null)
            return builder;

        for (Pair<String, String> dimension : dimensions) {
            //noinspection unchecked
            builder = (T) builder.withDimension(dimension.getKey(), dimension.getValue());
        }

        return builder;
    }
}
