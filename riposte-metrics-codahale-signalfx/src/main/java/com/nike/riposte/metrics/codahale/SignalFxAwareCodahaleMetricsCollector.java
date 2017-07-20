package com.nike.riposte.metrics.codahale;

import com.nike.riposte.metrics.codahale.contrib.SignalFxReporterFactory;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.RollingWindowHistogramBuilder;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.RollingWindowTimerBuilder;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SlidingTimeWindowReservoir;
import com.codahale.metrics.Timer;
import com.signalfx.codahale.metrics.MetricBuilder;
import com.signalfx.codahale.reporter.MetricMetadata;
import com.signalfx.codahale.reporter.SignalFxReporter;

/**
 * An extension of {@link CodahaleMetricsCollector} that is SignalFx-aware and will use the {@link
 * SignalFxReporter#getMetricMetadata()} when dealing with metrics so that they will be tagged with the appropriate
 * global/unique dimensions.
 *
 * @author Nic Munroe
 */
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
        return metricMetadata.forBuilder(timerBuilder)
                             .withMetricName(timerName)
                             .createOrGet(metricRegistry);
    }

    @Override
    public Meter getNamedMeter(String meterName) {
        return metricMetadata.forBuilder(MetricBuilder.METERS)
                             .withMetricName(meterName)
                             .createOrGet(metricRegistry);
    }

    @Override
    public Counter getNamedCounter(String counterName) {
        return metricMetadata.forBuilder(MetricBuilder.COUNTERS)
                             .withMetricName(counterName)
                             .createOrGet(metricRegistry);
    }

    @Override
    public Histogram getNamedHistogram(String histogramName) {
        return metricMetadata.forBuilder(histogramBuilder)
                             .withMetricName(histogramName)
                             .createOrGet(metricRegistry);
    }
}
