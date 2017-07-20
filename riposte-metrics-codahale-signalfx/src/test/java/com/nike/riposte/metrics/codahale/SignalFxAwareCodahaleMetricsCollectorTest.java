package com.nike.riposte.metrics.codahale;

import com.nike.riposte.metrics.codahale.contrib.SignalFxReporterFactory;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.RollingWindowHistogramBuilder;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.RollingWindowTimerBuilder;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.signalfx.codahale.metrics.MetricBuilder;
import com.signalfx.codahale.reporter.MetricMetadata;
import com.signalfx.codahale.reporter.MetricMetadata.BuilderTagger;
import com.signalfx.codahale.reporter.SignalFxReporter;

import org.junit.Before;
import org.junit.Test;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link SignalFxAwareCodahaleMetricsCollector}.
 *
 * @author Nic Munroe
 */
public class SignalFxAwareCodahaleMetricsCollectorTest {

    private MetricRegistry metricRegistryMock;
    private SignalFxReporterFactory sfxReporterFactoryMock;
    private SignalFxReporter sfxReporterMock;
    private MetricMetadata metricMetadataMock;

    private MetricBuilder<Timer> timerBuilderMock;
    private MetricBuilder<Histogram> histogramBuilderMock;

    private Timer timerMock;
    private Meter meterMock;
    private Histogram histogramMock;
    private Counter counterMock;

    private BuilderTagger<Timer> timerTaggerMock;
    private BuilderTagger<Meter> meterTaggerMock;
    private BuilderTagger<Histogram> histogramTaggerMock;
    private BuilderTagger<Counter> counterTaggerMock;

    private long reportingInterval = 42;
    private TimeUnit reportingTimeUnit = TimeUnit.HOURS;

    private SignalFxAwareCodahaleMetricsCollector sfxImpl;

    @Before
    public void beforeMethod() {
        metricRegistryMock = mock(MetricRegistry.class);
        sfxReporterFactoryMock = mock(SignalFxReporterFactory.class);
        sfxReporterMock = mock(SignalFxReporter.class);
        metricMetadataMock = mock(MetricMetadata.class);

        doReturn(sfxReporterMock).when(sfxReporterFactoryMock).getReporter(any(MetricRegistry.class));
        doReturn(metricMetadataMock).when(sfxReporterMock).getMetricMetadata();

        doReturn(reportingInterval).when(sfxReporterFactoryMock).getInterval();
        doReturn(reportingTimeUnit).when(sfxReporterFactoryMock).getTimeUnit();

        timerBuilderMock = mock(MetricBuilder.class);
        histogramBuilderMock = mock(MetricBuilder.class);

        timerMock = mock(Timer.class);
        meterMock = mock(Meter.class);
        histogramMock = mock(Histogram.class);
        counterMock = mock(Counter.class);

        timerTaggerMock = mock(BuilderTagger.class);
        meterTaggerMock = mock(BuilderTagger.class);
        histogramTaggerMock = mock(BuilderTagger.class);
        counterTaggerMock = mock(BuilderTagger.class);

        setupBuilderTaggerMock(timerTaggerMock, timerBuilderMock, timerMock);
        setupBuilderTaggerMock(meterTaggerMock, MetricBuilder.METERS, meterMock);
        setupBuilderTaggerMock(histogramTaggerMock, histogramBuilderMock, histogramMock);
        setupBuilderTaggerMock(counterTaggerMock, MetricBuilder.COUNTERS, counterMock);

        sfxImpl = new SignalFxAwareCodahaleMetricsCollector(
            metricRegistryMock, metricMetadataMock, timerBuilderMock, histogramBuilderMock
        );
    }

    private <M extends Metric> void setupBuilderTaggerMock(
        BuilderTagger<M> builderTaggerMock,
        MetricBuilder<M> metricBuilder,
        M metric
    ) {
        doReturn(builderTaggerMock).when(metricMetadataMock).forBuilder(metricBuilder);
        doReturn(builderTaggerMock).when(builderTaggerMock).withMetricName(anyString());
        doReturn(metric).when(builderTaggerMock).createOrGet(metricRegistryMock);
    }

    private void verifyRollingWindowTimerBuilder(MetricBuilder<Timer> timerBuilder,
                                                 long expectedReportingInterval,
                                                 TimeUnit expectedTimeUnit) {
        assertThat(timerBuilder).isInstanceOf(RollingWindowTimerBuilder.class);
        assertThat(Whitebox.getInternalState(timerBuilder, "amount")).isEqualTo(expectedReportingInterval);
        assertThat(Whitebox.getInternalState(timerBuilder, "timeUnit")).isEqualTo(expectedTimeUnit);
    }

    private void verifyRollingWindowHistogramBuilder(MetricBuilder<Histogram> histogramBuilder,
                                                 long expectedReportingInterval,
                                                 TimeUnit expectedTimeUnit) {
        assertThat(histogramBuilder).isInstanceOf(RollingWindowHistogramBuilder.class);
        assertThat(Whitebox.getInternalState(histogramBuilder, "amount")).isEqualTo(expectedReportingInterval);
        assertThat(Whitebox.getInternalState(histogramBuilder, "timeUnit")).isEqualTo(expectedTimeUnit);
    }

    @Test
    public void single_arg_constructor_sets_fields_as_expected() {
        // when
        SignalFxAwareCodahaleMetricsCollector cmc = new SignalFxAwareCodahaleMetricsCollector(sfxReporterFactoryMock);

        // then
        assertThat(cmc.metricRegistry)
            .isNotNull()
            .isNotSameAs(metricRegistryMock);
        verify(sfxReporterFactoryMock).getReporter(cmc.metricRegistry);
        assertThat(cmc.metricMetadata).isSameAs(metricMetadataMock);
        verifyRollingWindowTimerBuilder(cmc.timerBuilder, reportingInterval, reportingTimeUnit);
        verifyRollingWindowHistogramBuilder(cmc.histogramBuilder, reportingInterval, reportingTimeUnit);
    }

    @Test
    public void double_arg_constructor_sets_fields_as_expected() {
        // when
        SignalFxAwareCodahaleMetricsCollector cmc = new SignalFxAwareCodahaleMetricsCollector(
            metricRegistryMock, sfxReporterFactoryMock
        );

        // then
        assertThat(cmc.metricRegistry).isSameAs(metricRegistryMock);
        verify(sfxReporterFactoryMock).getReporter(metricRegistryMock);
        assertThat(cmc.metricMetadata).isSameAs(metricMetadataMock);
        verifyRollingWindowTimerBuilder(cmc.timerBuilder, reportingInterval, reportingTimeUnit);
        verifyRollingWindowHistogramBuilder(cmc.histogramBuilder, reportingInterval, reportingTimeUnit);
    }

    @Test
    public void kitchen_sink_constructor_sets_fields_as_expected() {
        // when
        SignalFxAwareCodahaleMetricsCollector cmc = new SignalFxAwareCodahaleMetricsCollector(
            metricRegistryMock, metricMetadataMock, timerBuilderMock, histogramBuilderMock
        );

        // then
        assertThat(cmc.metricRegistry).isSameAs(metricRegistryMock);
        assertThat(cmc.metricMetadata).isSameAs(metricMetadataMock);
        assertThat(cmc.timerBuilder).isSameAs(timerBuilderMock);
        assertThat(cmc.histogramBuilder).isSameAs(histogramBuilderMock);
    }

    @Test
    public void getNamedTimer_creates_timer_using_sfx_mechanisms() {
        // given
        String timerName = UUID.randomUUID().toString();

        // when
        Timer result = sfxImpl.getNamedTimer(timerName);

        // then
        verify(metricMetadataMock).forBuilder(timerBuilderMock);
        verify(timerTaggerMock).withMetricName(timerName);
        verify(timerTaggerMock).createOrGet(metricRegistryMock);
        verifyNoMoreInteractions(metricMetadataMock, timerTaggerMock);
        assertThat(result).isSameAs(timerMock);
    }

    @Test
    public void getNamedMeter_creates_timer_using_sfx_mechanisms() {
        // given
        String meterName = UUID.randomUUID().toString();

        // when
        Meter result = sfxImpl.getNamedMeter(meterName);

        // then
        verify(metricMetadataMock).forBuilder(MetricBuilder.METERS);
        verify(meterTaggerMock).withMetricName(meterName);
        verify(meterTaggerMock).createOrGet(metricRegistryMock);
        verifyNoMoreInteractions(metricMetadataMock, meterTaggerMock);
        assertThat(result).isSameAs(meterMock);
    }

    @Test
    public void getNamedHistogram_creates_histogram_using_sfx_mechanisms() {
        // given
        String histogramName = UUID.randomUUID().toString();

        // when
        Histogram result = sfxImpl.getNamedHistogram(histogramName);

        // then
        verify(metricMetadataMock).forBuilder(histogramBuilderMock);
        verify(histogramTaggerMock).withMetricName(histogramName);
        verify(histogramTaggerMock).createOrGet(metricRegistryMock);
        verifyNoMoreInteractions(metricMetadataMock, histogramTaggerMock);
        assertThat(result).isSameAs(histogramMock);
    }

    @Test
    public void getNamedCounter_creates_timer_using_sfx_mechanisms() {
        // given
        String counterName = UUID.randomUUID().toString();

        // when
        Counter result = sfxImpl.getNamedCounter(counterName);

        // then
        verify(metricMetadataMock).forBuilder(MetricBuilder.COUNTERS);
        verify(counterTaggerMock).withMetricName(counterName);
        verify(counterTaggerMock).createOrGet(metricRegistryMock);
        verifyNoMoreInteractions(metricMetadataMock, counterTaggerMock);
        assertThat(result).isSameAs(counterMock);
    }
}