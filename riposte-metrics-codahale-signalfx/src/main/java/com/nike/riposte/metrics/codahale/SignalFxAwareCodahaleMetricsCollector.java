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
import com.codahale.metrics.SlidingTimeWindowArrayReservoir;
import com.codahale.metrics.Timer;
import com.signalfx.codahale.metrics.MetricBuilder;
import com.signalfx.codahale.reporter.MetricMetadata;
import com.signalfx.codahale.reporter.MetricMetadata.BuilderTagger;
import com.signalfx.codahale.reporter.MetricMetadata.Tagger;
import com.signalfx.codahale.reporter.MetricMetadata.TaggerBase;
import com.signalfx.codahale.reporter.SignalFxReporter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    protected final @NotNull MetricMetadata metricMetadata;
    protected final @NotNull MetricBuilder<Timer> timerBuilder;
    protected final @NotNull MetricBuilder<Histogram> histogramBuilder;

    /**
     * Creates a new instance with a *new* {@link MetricRegistry} - this means you should be careful to retrieve it
     * via {@link #getMetricRegistry()} and pass it around anywhere else you need a metric registry in your application.
     * The {@link SignalFxReporterFactory} is used to get the {@link SignalFxReporter#getMetricMetadata()} and reporting
     * frequency.
     *
     * <p>Note that {@link Timer}s and {@link Histogram}s created from this class will have {@link
     * SlidingTimeWindowArrayReservoir} reservoirs that match the given {@link SignalFxReporterFactory#getInterval()} and
     * {@link SignalFxReporterFactory#getTimeUnit()}.
     *
     * @param sfxReporterFactory The {@link SignalFxReporterFactory} to use to get the {@link
     * SignalFxReporter#getMetricMetadata()} and reporting frequency.
     */
    public SignalFxAwareCodahaleMetricsCollector(@NotNull SignalFxReporterFactory sfxReporterFactory) {
        this(new MetricRegistry(), sfxReporterFactory);
    }

    /**
     * Creates a new instance with the given {@link MetricRegistry}, and using the given {@link SignalFxReporterFactory}
     * to retrieve the {@link SignalFxReporter#getMetricMetadata()} and reporting frequency.
     *
     * <p>Note that {@link Timer}s and {@link Histogram}s created from this class will have {@link
     * SlidingTimeWindowArrayReservoir} reservoirs that match the given {@link SignalFxReporterFactory#getInterval()} and
     * {@link SignalFxReporterFactory#getTimeUnit()}.
     *
     * @param sfxReporterFactory The {@link SignalFxReporterFactory} to use to get the {@link
     * SignalFxReporter#getMetricMetadata()} and reporting frequency.
     */
    public SignalFxAwareCodahaleMetricsCollector(@NotNull MetricRegistry metricRegistry,
                                                 @NotNull SignalFxReporterFactory sfxReporterFactory) {
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
    @SuppressWarnings("ConstantConditions")
    public SignalFxAwareCodahaleMetricsCollector(@NotNull MetricRegistry metricRegistry,
                                                 @NotNull MetricMetadata metricMetadata,
                                                 @NotNull MetricBuilder<Timer> timerBuilder,
                                                 @NotNull MetricBuilder<Histogram> histogramBuilder) {
        super(metricRegistry);

        if (metricMetadata == null) {
            throw new IllegalArgumentException("metricMetadata cannot be null.");
        }
        if (timerBuilder == null) {
            throw new IllegalArgumentException("timerBuilder cannot be null.");
        }
        if (histogramBuilder == null) {
            throw new IllegalArgumentException("histogramBuilder cannot be null.");
        }

        this.metricMetadata = metricMetadata;
        this.timerBuilder = timerBuilder;
        this.histogramBuilder = histogramBuilder;
    }

    @Override
    public @NotNull Timer getNamedTimer(@NotNull String timerName) {
        return getNamedTimer(timerName, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final @NotNull Timer getNamedTimer(
        @NotNull String timerName,
        @Nullable Pair<String, String>... dimensions
    ) {
        return getNamedTimer(timerName, convertDimensionsToList(dimensions));
    }

    public @NotNull Timer getNamedTimer(
        @NotNull String timerName,
        @Nullable Iterable<Pair<String, String>> dimensions
    ) {
        return getNamedMetric(timerName, timerBuilder, dimensions);
    }

    @Override
    public @NotNull Meter getNamedMeter(@NotNull String meterName) {
        return getNamedMeter(meterName, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final @NotNull Meter getNamedMeter(
        @NotNull String meterName,
        @Nullable Pair<String, String>... dimensions
    ) {
        return getNamedMeter(meterName, convertDimensionsToList(dimensions));
    }

    public @NotNull Meter getNamedMeter(
        @NotNull String meterName,
        @Nullable Iterable<Pair<String, String>> dimensions
    ) {
        return getNamedMetric(meterName, MetricBuilder.METERS, dimensions);
    }

    @Override
    public @NotNull Counter getNamedCounter(@NotNull String counterName) {
        return getNamedCounter(counterName, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final @NotNull Counter getNamedCounter(
        @NotNull String counterName,
        @Nullable Pair<String, String>... dimensions
    ) {
        return getNamedCounter(counterName, convertDimensionsToList(dimensions));
    }

    public @NotNull Counter getNamedCounter(
        @NotNull String counterName,
        @Nullable Iterable<Pair<String, String>> dimensions
    ) {
        return getNamedMetric(counterName, MetricBuilder.COUNTERS, dimensions);
    }

    @Override
    public @NotNull Histogram getNamedHistogram(@NotNull String histogramName) {
        return getNamedHistogram(histogramName, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final @NotNull Histogram getNamedHistogram(
        @NotNull String histogramName,
        @Nullable Pair<String, String>... dimensions
    ) {
        return getNamedHistogram(histogramName, convertDimensionsToList(dimensions));
    }

    public @NotNull Histogram getNamedHistogram(
        @NotNull String histogramName,
        @Nullable Iterable<Pair<String, String>> dimensions
    ) {
        return getNamedMetric(histogramName, histogramBuilder, dimensions);
    }

    public final <M extends Metric> @NotNull M getNamedMetric(
        @NotNull String metricName,
        @NotNull MetricBuilder<M> builder
    ) {
        return getNamedMetric(metricName, builder, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final <M extends Metric> @NotNull M getNamedMetric(
        @NotNull String metricName,
        @NotNull MetricBuilder<M> builder,
        @Nullable Pair<String, String>... dimensions
    ) {
        return getNamedMetric(metricName, builder, convertDimensionsToList(dimensions));
    }

    public <M extends Metric> @NotNull M getNamedMetric(
        @NotNull String metricName,
        @NotNull MetricBuilder<M> builder,
        @Nullable Iterable<Pair<String, String>> dimensions
    ) {
        BuilderTagger<M> builderTagger = metricMetadata.forBuilder(builder)
                                                       .withMetricName(metricName);
        builderTagger = addDimensions(builderTagger, dimensions);
        return builderTagger.createOrGet(metricRegistry);
    }

    @Override
    public <M extends Metric> @NotNull M registerNamedMetric(@NotNull String metricName, @NotNull M metric) {
        return registerNamedMetric(metricName, metric, (Iterable<Pair<String, String>>)null);
    }

    @SafeVarargs
    public final <M extends Metric> @NotNull M registerNamedMetric(
        @NotNull String metricName,
        @NotNull M metric,
        @Nullable Pair<String, String>... dimensions
    ) {
        return registerNamedMetric(metricName, metric, convertDimensionsToList(dimensions));
    }
    
    public <M extends Metric> @NotNull M registerNamedMetric(
        @NotNull String metricName,
        @NotNull M metric,
        @Nullable Iterable<Pair<String, String>> dimensions
    ) {
        Tagger<M> metricTagger = metricMetadata.forMetric(metric)
                                               .withMetricName(metricName);
        addDimensions(metricTagger, dimensions);
        return metricTagger.register(metricRegistry);
    }

    protected List<Pair<String, String>> convertDimensionsToList(@Nullable Pair<String, String>[] dimensions) {
        return (dimensions == null) ? null : Arrays.asList(dimensions);
    }

    protected <M extends Metric, T extends TaggerBase<M, ?>> @NotNull T addDimensions(
        @NotNull T builder, @Nullable Iterable<Pair<String, String>> dimensions
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
