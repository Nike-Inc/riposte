package com.nike.riposte.metrics.codahale;

import com.nike.internal.util.Pair;
import com.nike.riposte.metrics.codahale.contrib.SignalFxReporterFactory;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.RollingWindowHistogramBuilder;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.RollingWindowTimerBuilder;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.signalfx.codahale.metrics.MetricBuilder;
import com.signalfx.codahale.reporter.MetricMetadata;
import com.signalfx.codahale.reporter.MetricMetadata.BuilderTagger;
import com.signalfx.codahale.reporter.MetricMetadata.Tagger;
import com.signalfx.codahale.reporter.SignalFxReporter;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link SignalFxAwareCodahaleMetricsCollector}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class SignalFxAwareCodahaleMetricsCollectorTest {

    private MetricRegistry metricRegistryMock;
    private SignalFxReporterFactory sfxReporterFactoryMock;
    private SignalFxReporter sfxReporterMock;
    private MetricMetadata metricMetadataMock;

    private MetricBuilder<Timer> timerBuilderMock;
    private MetricBuilder<Histogram> histogramBuilderMock;
    private MetricBuilder<Metric> genericMetricBuilderMock;

    private Timer timerMock;
    private Meter meterMock;
    private Histogram histogramMock;
    private Counter counterMock;
    private Gauge<String> gaugeMock;
    private Metric genericMetricMock;

    private BuilderTagger<Timer> timerTaggerMock;
    private BuilderTagger<Meter> meterTaggerMock;
    private BuilderTagger<Histogram> histogramTaggerMock;
    private BuilderTagger<Counter> counterTaggerMock;
    private Tagger<Gauge<String>> gaugeTaggerMock;
    private BuilderTagger<Metric> genericMetricTaggerMock;

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
        genericMetricBuilderMock = mock(MetricBuilder.class);

        timerMock = mock(Timer.class);
        meterMock = mock(Meter.class);
        histogramMock = mock(Histogram.class);
        counterMock = mock(Counter.class);
        gaugeMock = mock(Gauge.class);
        genericMetricMock = mock(Metric.class);

        timerTaggerMock = mock(BuilderTagger.class);
        meterTaggerMock = mock(BuilderTagger.class);
        histogramTaggerMock = mock(BuilderTagger.class);
        counterTaggerMock = mock(BuilderTagger.class);
        gaugeTaggerMock = mock(Tagger.class);
        genericMetricTaggerMock = mock(BuilderTagger.class);

        setupBuilderTaggerMock(timerTaggerMock, timerBuilderMock, timerMock);
        setupBuilderTaggerMock(meterTaggerMock, MetricBuilder.METERS, meterMock);
        setupBuilderTaggerMock(histogramTaggerMock, histogramBuilderMock, histogramMock);
        setupBuilderTaggerMock(counterTaggerMock, MetricBuilder.COUNTERS, counterMock);
        setupTaggerMock(gaugeTaggerMock);
        setupBuilderTaggerMock(genericMetricTaggerMock, genericMetricBuilderMock, genericMetricMock);

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
        doReturn(builderTaggerMock).when(builderTaggerMock).withDimension(anyString(), anyString());
        doReturn(metric).when(builderTaggerMock).createOrGet(metricRegistryMock);
    }

    private <M extends Metric> void setupTaggerMock(
        Tagger<M> taggerMock
    ) {
        List<M> metricHolder = new ArrayList<>();
        doAnswer(invocation -> {
            metricHolder.add((M) invocation.getArguments()[0]);
            return taggerMock;
        }).when(metricMetadataMock).forMetric(any(Metric.class));
        doReturn(taggerMock).when(taggerMock).withMetricName(anyString());
        doReturn(taggerMock).when(taggerMock).withDimension(anyString(), anyString());
        doAnswer(invocation -> metricHolder.get(0)).when(taggerMock).register(metricRegistryMock);
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

    private <M extends Metric> void verifyMetricCreation(
        MetricBuilder<M> metricBuilder, BuilderTagger<M> builderTaggerMock, String metricName,
        M expectedMetricResult, M actualMetricResult
    ) {
        verifyMetricCreation(
            metricBuilder, builderTaggerMock, metricName, null, expectedMetricResult, actualMetricResult
        );
    }

    private <M extends Metric> void verifyMetricCreation(
        MetricBuilder<M> metricBuilder, BuilderTagger<M> builderTaggerMock, String metricName,
        List<Pair<String, String>> dimensions, M expectedMetricResult, M actualMetricResult
    ) {
        int numDimensions = (dimensions == null) ? 0 : dimensions.size();

        verify(metricMetadataMock).forBuilder(metricBuilder);
        verify(builderTaggerMock).withMetricName(metricName);
        if (numDimensions == 0) {
            verify(builderTaggerMock, never()).withDimension(anyString(), anyString());
        }
        else {
            for (Pair<String, String> dimension : dimensions) {
                verify(builderTaggerMock).withDimension(dimension.getKey(), dimension.getValue());
            }
        }
        verify(builderTaggerMock).createOrGet(metricRegistryMock);
        verifyNoMoreInteractions(metricMetadataMock, builderTaggerMock);
        assertThat(actualMetricResult).isSameAs(expectedMetricResult);
    }

    private <M extends Metric, V> void verifyMetricRegistration(
        Tagger<Gauge<V>> taggerMock, String gaugeName, Gauge<V> expectedGauge, Gauge<V> actualGauge
    ) {
        verifyMetricRegistration(taggerMock, gaugeName, null, expectedGauge, actualGauge);
    }

    private <M extends Metric, V> void verifyMetricRegistration(
        Tagger<Gauge<V>> taggerMock, String gaugeName, List<Pair<String, String>> dimensions,
        Gauge<V> expectedGauge, Gauge<V> actualGauge
    ) {
        int numDimensions = (dimensions == null) ? 0 : dimensions.size();

        ArgumentCaptor<Gauge> gaugeArgumentCaptor = ArgumentCaptor.forClass(Gauge.class);
        verify(metricMetadataMock).forMetric(gaugeArgumentCaptor.capture());
        verify(taggerMock).withMetricName(gaugeName);
        if (numDimensions == 0) {
            verify(taggerMock, never()).withDimension(anyString(), anyString());
        }
        else {
            for (Pair<String, String> dimension : dimensions) {
                verify(taggerMock).withDimension(dimension.getKey(), dimension.getValue());
            }
        }
        verify(taggerMock).register(metricRegistryMock);
        verifyNoMoreInteractions(metricMetadataMock, taggerMock);

        Gauge gaugeRegistered = gaugeArgumentCaptor.getValue();
        assertThat(gaugeRegistered).isNotNull();
        assertThat(gaugeRegistered).isSameAs(actualGauge);
        assertThat(actualGauge).isSameAs(expectedGauge);
    }

    private Pair<String, String>[] generateVarargDimensions(Integer numDims) {
        if (numDims == null)
            return null;

        return generateIterableDimensions(numDims).toArray(new Pair[]{});
    }

    private List<Pair<String, String>> generateIterableDimensions(Integer numDims) {
        if (numDims == null)
            return null;

        List<Pair<String, String>> result = new ArrayList<>();
        for (int i = 0; i < numDims; i++) {
            result.add(Pair.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        }

        return result;
    }

    @Test
    public void getNamedTimer_creates_timer_using_sfx_mechanisms() {
        // given
        String timerName = UUID.randomUUID().toString();

        // when
        Timer result = sfxImpl.getNamedTimer(timerName);

        // then
        verifyMetricCreation(timerBuilderMock, timerTaggerMock, timerName, timerMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void getNamedTimer_with_varargs_dimensions_creates_dimensioned_timer_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String timerName = UUID.randomUUID().toString();
        Pair<String, String>[] varargDims = generateVarargDimensions(numDimensions);
        List<Pair<String, String>> dimsAsList = (varargDims == null) ? null : Arrays.asList(varargDims);

        // when
        Timer result = sfxImpl.getNamedTimer(timerName, varargDims);

        // then
        verifyMetricCreation(timerBuilderMock, timerTaggerMock, timerName, dimsAsList, timerMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void getNamedTimer_with_iterable_dimensions_creates_dimensioned_timer_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String timerName = UUID.randomUUID().toString();
        List<Pair<String, String>> iterableDims = generateIterableDimensions(numDimensions);

        // when
        Timer result = sfxImpl.getNamedTimer(timerName, iterableDims);

        // then
        verifyMetricCreation(timerBuilderMock, timerTaggerMock, timerName, iterableDims, timerMock, result);
    }

    @Test
    public void getNamedMeter_creates_timer_using_sfx_mechanisms() {
        // given
        String meterName = UUID.randomUUID().toString();

        // when
        Meter result = sfxImpl.getNamedMeter(meterName);

        // then
        verifyMetricCreation(MetricBuilder.METERS, meterTaggerMock, meterName, meterMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void getNamedMeter_with_varargs_dimensions_creates_dimensioned_meter_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String meterName = UUID.randomUUID().toString();
        Pair<String, String>[] varargDims = generateVarargDimensions(numDimensions);
        List<Pair<String, String>> dimsAsList = (varargDims == null) ? null : Arrays.asList(varargDims);

        // when
        Meter result = sfxImpl.getNamedMeter(meterName, varargDims);

        // then
        verifyMetricCreation(MetricBuilder.METERS, meterTaggerMock, meterName, dimsAsList, meterMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void getNamedMeter_with_iterable_dimensions_creates_dimensioned_meter_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String meterName = UUID.randomUUID().toString();
        List<Pair<String, String>> iterableDims = generateIterableDimensions(numDimensions);

        // when
        Meter result = sfxImpl.getNamedMeter(meterName, iterableDims);

        // then
        verifyMetricCreation(MetricBuilder.METERS, meterTaggerMock, meterName, iterableDims, meterMock, result);
    }

    @Test
    public void getNamedHistogram_creates_histogram_using_sfx_mechanisms() {
        // given
        String histogramName = UUID.randomUUID().toString();

        // when
        Histogram result = sfxImpl.getNamedHistogram(histogramName);

        // then
        verifyMetricCreation(histogramBuilderMock, histogramTaggerMock, histogramName, histogramMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void getNamedHistogram_with_varargs_dimensions_creates_dimensioned_histogram_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String histogramName = UUID.randomUUID().toString();
        Pair<String, String>[] varargDims = generateVarargDimensions(numDimensions);
        List<Pair<String, String>> dimsAsList = (varargDims == null) ? null : Arrays.asList(varargDims);

        // when
        Histogram result = sfxImpl.getNamedHistogram(histogramName, varargDims);

        // then
        verifyMetricCreation(histogramBuilderMock, histogramTaggerMock, histogramName, dimsAsList, histogramMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void getNamedHistogram_with_iterable_dimensions_creates_dimensioned_histogram_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String histogramName = UUID.randomUUID().toString();
        List<Pair<String, String>> iterableDims = generateIterableDimensions(numDimensions);

        // when
        Histogram result = sfxImpl.getNamedHistogram(histogramName, iterableDims);

        // then
        verifyMetricCreation(histogramBuilderMock, histogramTaggerMock, histogramName, iterableDims, histogramMock, result);
    }

    @Test
    public void getNamedCounter_creates_timer_using_sfx_mechanisms() {
        // given
        String counterName = UUID.randomUUID().toString();

        // when
        Counter result = sfxImpl.getNamedCounter(counterName);

        // then
        verifyMetricCreation(MetricBuilder.COUNTERS, counterTaggerMock, counterName, counterMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void getNamedCounter_with_varargs_dimensions_creates_dimensioned_counter_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String counterName = UUID.randomUUID().toString();
        Pair<String, String>[] varargDims = generateVarargDimensions(numDimensions);
        List<Pair<String, String>> dimsAsList = (varargDims == null) ? null : Arrays.asList(varargDims);

        // when
        Counter result = sfxImpl.getNamedCounter(counterName, varargDims);

        // then
        verifyMetricCreation(MetricBuilder.COUNTERS, counterTaggerMock, counterName, dimsAsList, counterMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void getNamedCounter_with_iterable_dimensions_creates_dimensioned_counter_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String counterName = UUID.randomUUID().toString();
        List<Pair<String, String>> iterableDims = generateIterableDimensions(numDimensions);

        // when
        Counter result = sfxImpl.getNamedCounter(counterName, iterableDims);

        // then
        verifyMetricCreation(MetricBuilder.COUNTERS, counterTaggerMock, counterName, iterableDims, counterMock, result);
    }

    @Test
    public void getNamedMetric_creates_metric_using_sfx_mechanisms() {
        // given
        String metricName = UUID.randomUUID().toString();

        // when
        Metric result = sfxImpl.getNamedMetric(metricName, genericMetricBuilderMock);

        // then
        verifyMetricCreation(genericMetricBuilderMock, genericMetricTaggerMock, metricName, genericMetricMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void getNamedMetric_with_varargs_dimensions_creates_dimensioned_metric_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String metricName = UUID.randomUUID().toString();
        Pair<String, String>[] varargDims = generateVarargDimensions(numDimensions);
        List<Pair<String, String>> dimsAsList = (varargDims == null) ? null : Arrays.asList(varargDims);

        // when
        Metric result = sfxImpl.getNamedMetric(metricName, genericMetricBuilderMock, varargDims);

        // then
        verifyMetricCreation(genericMetricBuilderMock, genericMetricTaggerMock, metricName, dimsAsList, genericMetricMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void getNamedMetric_with_iterable_dimensions_creates_dimensioned_metric_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String metricName = UUID.randomUUID().toString();
        List<Pair<String, String>> iterableDims = generateIterableDimensions(numDimensions);

        // when
        Metric result = sfxImpl.getNamedMetric(metricName, genericMetricBuilderMock, iterableDims);

        // then
        verifyMetricCreation(genericMetricBuilderMock, genericMetricTaggerMock, metricName, iterableDims, genericMetricMock, result);
    }

    @Test
    public void registerNamedMetric_registers_metric_using_sfx_mechanisms() {
        // given
        String gaugeName = UUID.randomUUID().toString();

        // when
        Gauge<String> result = sfxImpl.registerNamedMetric(gaugeName, gaugeMock);

        // then
        verifyMetricRegistration(gaugeTaggerMock, gaugeName, gaugeMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void registerNamedMetric_with_varargs_dimensions_creates_dimensioned_gauge_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String gaugeName = UUID.randomUUID().toString();
        Pair<String, String>[] varargDims = generateVarargDimensions(numDimensions);
        List<Pair<String, String>> dimsAsList = (varargDims == null) ? null : Arrays.asList(varargDims);

        // when
        Gauge<String> result = sfxImpl.registerNamedMetric(gaugeName, gaugeMock, varargDims);

        // then
        verifyMetricRegistration(gaugeTaggerMock, gaugeName, dimsAsList, gaugeMock, result);
    }

    @DataProvider(value = {
        "null",
        "0",
        "1",
        "2"
    }, splitBy = "\\|")
    @Test
    public void registerNamedMetric_with_iterable_dimensions_creates_dimensioned_gauge_using_sfx_mechanisms(
        Integer numDimensions
    ) {
        // given
        String gaugeName = UUID.randomUUID().toString();
        List<Pair<String, String>> iterableDims = generateIterableDimensions(numDimensions);

        // when
        Gauge<String> result = sfxImpl.registerNamedMetric(gaugeName, gaugeMock, iterableDims);

        // then
        verifyMetricRegistration(gaugeTaggerMock, gaugeName, iterableDims, gaugeMock, result);
    }
}