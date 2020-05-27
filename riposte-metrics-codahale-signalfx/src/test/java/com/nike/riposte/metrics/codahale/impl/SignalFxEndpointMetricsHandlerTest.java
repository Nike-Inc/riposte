package com.nike.riposte.metrics.codahale.impl;

import com.nike.internal.util.Pair;
import com.nike.riposte.metrics.codahale.contrib.SignalFxReporterFactory;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.ChainedMetricDimensionConfigurator;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.DefaultMetricDimensionConfigurator;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.MetricDimensionConfigurator;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.RollingWindowHistogramBuilder;
import com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.RollingWindowTimerBuilder;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reservoir;
import com.codahale.metrics.SlidingTimeWindowArrayReservoir;
import com.codahale.metrics.Timer;
import com.signalfx.codahale.metrics.MetricBuilder;
import com.signalfx.codahale.reporter.MetricMetadata;
import com.signalfx.codahale.reporter.MetricMetadata.BuilderTagger;
import com.signalfx.codahale.reporter.SignalFxReporter;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.netty.handler.codec.http.HttpMethod;

import static com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR;
import static com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.DefaultMetricDimensionConfigurator.DEFAULT_ENDPOINT_CLASS_DIMENSION_KEY;
import static com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.DefaultMetricDimensionConfigurator.DEFAULT_METHOD_DIMENSION_KEY;
import static com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.DefaultMetricDimensionConfigurator.DEFAULT_RESPONSE_CODE_DIMENSION_KEY;
import static com.nike.riposte.metrics.codahale.impl.SignalFxEndpointMetricsHandler.DefaultMetricDimensionConfigurator.DEFAULT_URI_DIMENSION_KEY;
import static com.nike.riposte.testutils.Whitebox.getInternalState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests the functionality of {@link SignalFxEndpointMetricsHandler}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class SignalFxEndpointMetricsHandlerTest {

    private MetricMetadata metricMetadataMock;
    private MetricRegistry metricRegistryMock;
    private MetricBuilder<Timer> requestTimerBuilderMock;
    private MetricDimensionConfigurator<Timer> dimensionConfiguratorMock;

    private BuilderTagger<Timer> timerBuilderTaggerMock;
    private Timer timerMock;

    private SignalFxEndpointMetricsHandler handler;
    private RequestInfo<?> requestInfoMock;
    private ResponseInfo<?> responseInfoMock;
    private HttpProcessingState httpStateMock;
    private Instant requestStartTime;
    private Endpoint<?> endpointMock;
    private HttpMethod httpMethod;
    private String matchingPathTemplate;

    @Before
    public void beforeMethod() {
        metricMetadataMock = mock(MetricMetadata.class);
        metricRegistryMock = mock(MetricRegistry.class);
        requestTimerBuilderMock = mock(MetricBuilder.class);
        dimensionConfiguratorMock = mock(MetricDimensionConfigurator.class);

        handler = new SignalFxEndpointMetricsHandler(
            metricMetadataMock, metricRegistryMock, requestTimerBuilderMock, dimensionConfiguratorMock
        );

        requestInfoMock = mock(RequestInfo.class);
        responseInfoMock = mock(ResponseInfo.class);
        httpStateMock = mock(HttpProcessingState.class);
        endpointMock = mock(Endpoint.class);

        requestStartTime = Instant.now().minus(42, ChronoUnit.MILLIS);
        httpMethod = HttpMethod.PATCH;
        matchingPathTemplate = "/" + UUID.randomUUID().toString();
        doReturn(requestStartTime).when(httpStateMock).getRequestStartTime();
        doReturn(endpointMock).when(httpStateMock).getEndpointForExecution();
        doReturn(httpMethod).when(requestInfoMock).getMethod();
        doReturn(matchingPathTemplate).when(httpStateMock).getMatchingPathTemplate();

        timerBuilderTaggerMock = mock(BuilderTagger.class);
        doReturn(timerBuilderTaggerMock).when(metricMetadataMock).forBuilder(requestTimerBuilderMock);

        doReturn(timerBuilderTaggerMock).when(dimensionConfiguratorMock).setupMetricWithDimensions(
            eq(timerBuilderTaggerMock), eq(requestInfoMock), eq(responseInfoMock), eq(httpStateMock), anyInt(),
            anyInt(), anyLong(), any(), anyString(), anyString(), anyString()
        );

        timerMock = mock(Timer.class);
        doReturn(timerMock).when(timerBuilderTaggerMock).createOrGet(metricRegistryMock);
    }

    private Pair<Pair<SignalFxReporter, MetricMetadata>, Pair<Long, TimeUnit>> wireUpReporterFactoryMockForConstructor(
        SignalFxReporterFactory factoryMock, MetricRegistry expectedMetricRegistry
    ) {
        SignalFxReporter reporterMock = mock(SignalFxReporter.class);
        doReturn(reporterMock).when(factoryMock).getReporter(expectedMetricRegistry);

        MetricMetadata metricMetadataMock = wireUpReporterForConstructor(reporterMock);

        long reportingInterval = 42;
        TimeUnit reportingTimeUnit = TimeUnit.DAYS;

        doReturn(reportingInterval).when(factoryMock).getInterval();
        doReturn(reportingTimeUnit).when(factoryMock).getTimeUnit();

        return Pair.of(Pair.of(reporterMock, metricMetadataMock), Pair.of(reportingInterval, reportingTimeUnit));
    }

    private MetricMetadata wireUpReporterForConstructor(SignalFxReporter reporterMock) {
        MetricMetadata metricMetadataMock = mock(MetricMetadata.class);
        doReturn(metricMetadataMock).when(reporterMock).getMetricMetadata();

        return metricMetadataMock;
    }

    @Test
    public void two_arg_constructor_sets_fields_as_expected() {
        // given
        SignalFxReporterFactory reporterFactoryMock = mock(SignalFxReporterFactory.class);
        Pair<Pair<SignalFxReporter, MetricMetadata>, Pair<Long, TimeUnit>> wiredUpMocksAndData =
            wireUpReporterFactoryMockForConstructor(reporterFactoryMock, metricRegistryMock);

        MetricMetadata expectedMetricMetadata = wiredUpMocksAndData.getLeft().getRight();
        long expectedReportingInterval = wiredUpMocksAndData.getRight().getLeft();
        TimeUnit expectedReportingTimeUnit = wiredUpMocksAndData.getRight().getRight();

        // when
        SignalFxEndpointMetricsHandler instance =
            new SignalFxEndpointMetricsHandler(reporterFactoryMock, metricRegistryMock);

        // then
        assertThat(instance.metricMetadata).isSameAs(expectedMetricMetadata);
        assertThat(instance.metricRegistry).isSameAs(metricRegistryMock);
        assertThat(instance.requestTimerBuilder).isInstanceOf(RollingWindowTimerBuilder.class);
        RollingWindowTimerBuilder rwtb = (RollingWindowTimerBuilder) instance.requestTimerBuilder;
        assertThat(rwtb.amount).isEqualTo(expectedReportingInterval);
        assertThat(rwtb.timeUnit).isEqualTo(expectedReportingTimeUnit);
        assertThat(instance.requestTimerDimensionConfigurator)
            .isSameAs(DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR);
    }

    @Test
    public void two_arg_constructor_fails_with_IllegalArgumentException_if_factory_is_null() {
        // when
        Throwable ex = catchThrowable(() -> new SignalFxEndpointMetricsHandler(null, metricRegistryMock));

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                      .hasMessage("SignalFxReporterFactory cannot be null");
    }

    @Test
    public void two_arg_constructor_fails_with_IllegalArgumentException_if_metricRegistry_is_null() {
        // given
        SignalFxReporterFactory reporterFactoryMock = mock(SignalFxReporterFactory.class);
        wireUpReporterFactoryMockForConstructor(reporterFactoryMock, metricRegistryMock);

        // when
        Throwable ex = catchThrowable(() -> new SignalFxEndpointMetricsHandler(reporterFactoryMock, null));

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                      .hasMessage("MetricRegistry cannot be null");
    }

    @DataProvider(value = {
        "true   |   false",
        "false  |   true"
    }, splitBy = "\\|")
    @Test
    public void two_arg_constructor_fails_with_IllegalArgumentException_if_reporting_frequency_args_are_null(
        boolean amountIsNull, boolean timeUnitIsNull
    ) {
        // given
        SignalFxReporterFactory reporterFactoryMock = mock(SignalFxReporterFactory.class);
        wireUpReporterFactoryMockForConstructor(reporterFactoryMock, metricRegistryMock);

        if (amountIsNull)
            doReturn(null).when(reporterFactoryMock).getInterval();

        if (timeUnitIsNull)
            doReturn(null).when(reporterFactoryMock).getTimeUnit();

        // when
        Throwable ex = catchThrowable(() -> new SignalFxEndpointMetricsHandler(reporterFactoryMock, metricRegistryMock));

        // then
        if (amountIsNull) {
            assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                          .hasMessage("reportingFrequency amount cannot be null");
        }

        if (timeUnitIsNull) {
            assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                          .hasMessage("reportingFrequency TimeUnit cannot be null");
        }
    }

    @Test
    public void three_arg_constructor_sets_fields_as_expected() {
        // given
        SignalFxReporter reporterMock = mock(SignalFxReporter.class);
        MetricMetadata expectedMetricMetadata = wireUpReporterForConstructor(reporterMock);

        Pair<Long, TimeUnit> reportingFrequency = Pair.of(42L, TimeUnit.DAYS);

        // when
        SignalFxEndpointMetricsHandler instance =
            new SignalFxEndpointMetricsHandler(reporterMock, reportingFrequency, metricRegistryMock);

        // then
        assertThat(instance.metricMetadata).isSameAs(expectedMetricMetadata);
        assertThat(instance.metricRegistry).isSameAs(metricRegistryMock);
        assertThat(instance.requestTimerBuilder).isInstanceOf(RollingWindowTimerBuilder.class);
        RollingWindowTimerBuilder rwtb = (RollingWindowTimerBuilder) instance.requestTimerBuilder;
        assertThat(rwtb.amount).isEqualTo(reportingFrequency.getLeft());
        assertThat(rwtb.timeUnit).isEqualTo(reportingFrequency.getRight());
        assertThat(instance.requestTimerDimensionConfigurator)
            .isSameAs(DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR);
    }

    @Test
    public void three_arg_constructor_fails_with_IllegalArgumentException_if_reporter_is_null() {
        // when
        Throwable ex = catchThrowable(
            () -> new SignalFxEndpointMetricsHandler(null, Pair.of(42L, TimeUnit.DAYS), metricRegistryMock)
        );

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                      .hasMessage("SignalFxReporter cannot be null");
    }

    @Test
    public void three_arg_constructor_fails_with_IllegalArgumentException_if_reportingFrequency_is_null() {
        // given
        SignalFxReporter reporterMock = mock(SignalFxReporter.class);
        wireUpReporterForConstructor(reporterMock);

        // when
        Throwable ex = catchThrowable(
            () -> new SignalFxEndpointMetricsHandler(reporterMock, null, metricRegistryMock)
        );

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                      .hasMessage("reportingFrequency cannot be null");
    }

    @DataProvider(value = {
        "true   |   false",
        "false  |   true"
    }, splitBy = "\\|")
    @Test
    public void three_arg_constructor_fails_with_IllegalArgumentException_if_reporting_frequency_args_are_null(
        boolean amountIsNull, boolean timeUnitIsNull
    ) {
        // given
        SignalFxReporter reporterMock = mock(SignalFxReporter.class);
        wireUpReporterForConstructor(reporterMock);

        Long amount = (amountIsNull) ? null : 42L;
        TimeUnit timeUnit = (timeUnitIsNull) ? null : TimeUnit.DAYS;

        // when
        Throwable ex = catchThrowable(
            () -> new SignalFxEndpointMetricsHandler(reporterMock, Pair.of(amount, timeUnit), metricRegistryMock)
        );

        // then
        if (amountIsNull) {
            assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                          .hasMessage("reportingFrequency amount cannot be null");
        }

        if (timeUnitIsNull) {
            assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                          .hasMessage("reportingFrequency TimeUnit cannot be null");
        }
    }

    @Test
    public void three_arg_constructor_fails_with_IllegalArgumentException_if_metricRegistry_is_null() {
        // given
        SignalFxReporter reporterMock = mock(SignalFxReporter.class);
        wireUpReporterForConstructor(reporterMock);

        // when
        Throwable ex = catchThrowable(
            () -> new SignalFxEndpointMetricsHandler(reporterMock, Pair.of(42L, TimeUnit.DAYS), null)
        );

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                      .hasMessage("metricRegistry cannot be null");
    }

    @Test
    public void kitchen_sink_constructor_sets_fields_as_expected() {
        // when
        SignalFxEndpointMetricsHandler instance = new SignalFxEndpointMetricsHandler(
            metricMetadataMock, metricRegistryMock, requestTimerBuilderMock, dimensionConfiguratorMock
        );

        // then
        assertThat(instance.metricMetadata).isSameAs(metricMetadataMock);
        assertThat(instance.metricRegistry).isSameAs(metricRegistryMock);
        assertThat(instance.requestTimerBuilder).isSameAs(requestTimerBuilderMock);
        assertThat(instance.requestTimerDimensionConfigurator).isSameAs(dimensionConfiguratorMock);
    }

    @Test
    public void kitchen_sink_constructor_defaults_requestTimerDimensionConfigurator_if_passed_null() {
        // when
        SignalFxEndpointMetricsHandler instance = new SignalFxEndpointMetricsHandler(
            metricMetadataMock, metricRegistryMock, requestTimerBuilderMock, null
        );

        // then
        assertThat(instance.requestTimerDimensionConfigurator)
            .isSameAs(DEFAULT_REQUEST_LATENCY_TIMER_DIMENSION_CONFIGURATOR);
    }

    @DataProvider(value = {
        "true   |   false   |   false   |   signalFxReporterMetricMetadata cannot be null",
        "false  |   true    |   false   |   metricRegistry cannot be null",
        "false  |   false   |   true    |   requestTimerBuilder cannot be null"
    }, splitBy = "\\|")
    @Test
    public void kitchen_sink_constructor_fails_with_IllegalArgumentException_if_certain_args_are_null(
        boolean metadataIsNull, boolean registryIsNull, boolean timerBuilderIsNull, String expectedMessage
    ) {
        // given
        MetricMetadata metricMetadata = (metadataIsNull) ? null : metricMetadataMock;
        MetricRegistry registry = (registryIsNull) ? null : metricRegistryMock;
        MetricBuilder<Timer> timerBuilder = (timerBuilderIsNull) ? null : requestTimerBuilderMock;

        // when
        Throwable ex = catchThrowable(
            () -> new SignalFxEndpointMetricsHandler(metricMetadata, registry, timerBuilder, null)
        );

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                      .hasMessage(expectedMessage);
    }

    @Test
    public void setupEndpointsMetrics_does_nothing() {
        // given
        ServerConfig serverConfigMock = mock(ServerConfig.class);

        // when
        handler.setupEndpointsMetrics(serverConfigMock, metricRegistryMock);
        
        // then
        verifyNoInteractions(
            serverConfigMock, metricMetadataMock, metricRegistryMock, requestTimerBuilderMock, dimensionConfiguratorMock
        );
    }

    @Test
    public void handleRequest_works_as_expected() {
        // given
        int statusCode = 242;
        int statusCodeXXValue = 2;
        long elapsedTimeMillis = 42;

        // when
        handler.handleRequest(
            requestInfoMock, responseInfoMock, httpStateMock, statusCode, statusCodeXXValue, elapsedTimeMillis
        );

        // then
        verify(metricMetadataMock).forBuilder(requestTimerBuilderMock);
        verify(dimensionConfiguratorMock).setupMetricWithDimensions(
            timerBuilderTaggerMock, requestInfoMock, responseInfoMock, httpStateMock, statusCode, statusCodeXXValue,
            elapsedTimeMillis, endpointMock, endpointMock.getClass().getName(), httpMethod.name(), matchingPathTemplate
        );
        verify(timerBuilderTaggerMock).createOrGet(metricRegistryMock);
        verify(timerMock).update(elapsedTimeMillis, TimeUnit.MILLISECONDS);
    }

    @DataProvider(value = {
        "true   |   false   |   false",
        "false  |   true    |   false",
        "false  |   false   |   true",
        "true   |   true    |   true",
    }, splitBy = "\\|")
    @Test
    public void handleRequest_gracefully_handles_some_null_args(
        boolean endpointIsNull, boolean methodIsNull, boolean matchingPathTemplateIsNull
    ) {
        // given
        int statusCode = 242;
        int statusCodeXXValue = 2;
        long elapsedTimeMillis = 42;

        Endpoint endpoint = (endpointIsNull) ? null : endpointMock;
        doReturn(endpoint).when(httpStateMock).getEndpointForExecution();
        String expectedEndpointClass = (endpointIsNull) ? "NONE" : endpoint.getClass().getName();

        HttpMethod method = (methodIsNull) ? null : httpMethod;
        doReturn(method).when(requestInfoMock).getMethod();
        String expectedMethodName = (methodIsNull) ? "NONE" : method.name();

        String pathTemplate = (matchingPathTemplateIsNull) ? null : matchingPathTemplate;
        doReturn(pathTemplate).when(httpStateMock).getMatchingPathTemplate();
        String expectedPathTemplate = (matchingPathTemplateIsNull) ? "NONE" : pathTemplate;

        // when
        handler.handleRequest(
            requestInfoMock, responseInfoMock, httpStateMock, statusCode, statusCodeXXValue, elapsedTimeMillis
        );

        // then
        verify(metricMetadataMock).forBuilder(requestTimerBuilderMock);
        verify(dimensionConfiguratorMock).setupMetricWithDimensions(
            timerBuilderTaggerMock, requestInfoMock, responseInfoMock, httpStateMock, statusCode, statusCodeXXValue,
            elapsedTimeMillis, endpoint, expectedEndpointClass, expectedMethodName, expectedPathTemplate
        );
        verify(timerBuilderTaggerMock).createOrGet(metricRegistryMock);
        verify(timerMock).update(elapsedTimeMillis, TimeUnit.MILLISECONDS);
    }

    @Test
    public void RollingWindowTimerBuilder_constructor_sets_fields_as_expected() {
        // given
        long amount = 42;
        TimeUnit timeUnit = TimeUnit.DAYS;

        // when
        RollingWindowTimerBuilder rwtb = new RollingWindowTimerBuilder(amount, timeUnit);

        // then
        assertThat(rwtb.amount).isEqualTo(amount);
        assertThat(rwtb.timeUnit).isEqualTo(timeUnit);
    }

    @DataProvider(value = {
        "42     |   DAYS",
        "123    |   SECONDS",
        "999    |   MILLISECONDS",
        "3      |   HOURS"
    }, splitBy = "\\|")
    @Test
    public void RollingWindowTimerBuilder_newMetric_creates_new_timer_with_SlidingTimeWindowArrayReservoir_with_expected_values(
        long amount, TimeUnit timeUnit
    ) {
        // given
        RollingWindowTimerBuilder rwtb = new RollingWindowTimerBuilder(amount, timeUnit);

        // when
        Timer timer = rwtb.newMetric();

        // then
        Histogram histogram = (Histogram) getInternalState(timer, "histogram");
        Reservoir reservoir = (Reservoir) getInternalState(histogram, "reservoir");
        assertThat(reservoir).isInstanceOf(SlidingTimeWindowArrayReservoir.class);
        // The expected value here comes from logic in the SlidingTimeWindowArrayReservoir constructor.
        assertThat(getInternalState(reservoir, "window")).isEqualTo(timeUnit.toNanos(amount) * 256);
    }

    @Test
    public void RollingWindowTimerBuilder_newMetric_creates_a_new_timer_with_each_call() {
        // given
        RollingWindowTimerBuilder rwtb = new RollingWindowTimerBuilder(42, TimeUnit.DAYS);

        // when
        Timer firstCallTimer = rwtb.newMetric();
        Timer secondCallTimer = rwtb.newMetric();

        // then
        assertThat(firstCallTimer).isNotSameAs(secondCallTimer);
    }

    @DataProvider(value = {
        "true   |   true",
        "false  |   false"
    }, splitBy = "\\|")
    @Test
    public void RollingWindowTimerBuilder_isInstance_works_as_expected(boolean useTimer, boolean expectedResult) {
        // given
        Metric metric = (useTimer) ? mock(Timer.class) : mock(Gauge.class);
        RollingWindowTimerBuilder rwtb = new RollingWindowTimerBuilder(42, TimeUnit.DAYS);

        // when
        boolean result = rwtb.isInstance(metric);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }
    
    @Test
    public void RollingWindowHistogramBuilder_constructor_sets_fields_as_expected() {
        // given
        long amount = 42;
        TimeUnit timeUnit = TimeUnit.DAYS;

        // when
        RollingWindowHistogramBuilder rwhb = new RollingWindowHistogramBuilder(amount, timeUnit);

        // then
        assertThat(rwhb.amount).isEqualTo(amount);
        assertThat(rwhb.timeUnit).isEqualTo(timeUnit);
    }

    @DataProvider(value = {
        "42     |   DAYS",
        "123    |   SECONDS",
        "999    |   MILLISECONDS",
        "3      |   HOURS"
    }, splitBy = "\\|")
    @Test
    public void RollingWindowHistogramBuilder_newMetric_creates_new_histogram_with_SlidingTimeWindowArrayReservoir_with_expected_values(
        long amount, TimeUnit timeUnit
    ) {
        // given
        RollingWindowHistogramBuilder rwhb = new RollingWindowHistogramBuilder(amount, timeUnit);

        // when
        Histogram histogram = rwhb.newMetric();

        // then
        Reservoir reservoir = (Reservoir) getInternalState(histogram, "reservoir");
        assertThat(reservoir).isInstanceOf(SlidingTimeWindowArrayReservoir.class);
        // The expected value here comes from logic in the SlidingTimeWindowArrayReservoir constructor.
        assertThat(getInternalState(reservoir, "window")).isEqualTo(timeUnit.toNanos(amount) * 256);
    }

    @Test
    public void RollingWindowHistogramBuilder_newMetric_creates_a_new_histogram_with_each_call() {
        // given
        RollingWindowHistogramBuilder rwhb = new RollingWindowHistogramBuilder(42, TimeUnit.DAYS);

        // when
        Histogram firstCallHistogram = rwhb.newMetric();
        Histogram secondCallHistogram = rwhb.newMetric();

        // then
        assertThat(firstCallHistogram).isNotSameAs(secondCallHistogram);
    }

    @DataProvider(value = {
        "true   |   true",
        "false  |   false"
    }, splitBy = "\\|")
    @Test
    public void RollingWindowHistogramBuilder_isInstance_works_as_expected(boolean useHistogram, boolean expectedResult) {
        // given
        Metric metric = (useHistogram) ? mock(Histogram.class) : mock(Gauge.class);
        RollingWindowHistogramBuilder rwhb = new RollingWindowHistogramBuilder(42, TimeUnit.DAYS);

        // when
        boolean result = rwhb.isInstance(metric);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void MetricDimensionConfigurator_chainedWith_returns_ChainedMetricDimensionConfigurator_with_correct_args() {
        // given
        MetricDimensionConfigurator orig = (
            rawBuilder, requestInfo, responseInfo, httpState, responseHttpStatusCode, responseHttpStatusCodeXXValue,
            elapsedTimeMillis, endpoint, endpointClass, method, matchingPathTemplate
        ) -> null;
        MetricDimensionConfigurator chainMe = mock(MetricDimensionConfigurator.class);

        // when
        MetricDimensionConfigurator result = orig.chainedWith(chainMe);

        // then
        assertThat(result).isInstanceOf(ChainedMetricDimensionConfigurator.class);
        ChainedMetricDimensionConfigurator cmdc = (ChainedMetricDimensionConfigurator)result;
        assertThat(cmdc.firstConfigurator).isSameAs(orig);
        assertThat(cmdc.secondConfigurator).isSameAs(chainMe);
    }

    @Test
    public void DefaultMetricDimensionConfigurator_one_arg_constructor_sets_fields_as_expected() {
        // give
        String metricName = UUID.randomUUID().toString();

        // when
        DefaultMetricDimensionConfigurator instance = new DefaultMetricDimensionConfigurator(metricName);

        // then
        assertThat(instance.metricName).isEqualTo(metricName);
        assertThat(instance.responseCodeDimensionKey).isEqualTo(DEFAULT_RESPONSE_CODE_DIMENSION_KEY);
        assertThat(instance.uriDimensionKey).isEqualTo(DEFAULT_URI_DIMENSION_KEY);
        assertThat(instance.methodDimensionKey).isEqualTo(DEFAULT_METHOD_DIMENSION_KEY);
        assertThat(instance.endpointClassKey).isEqualTo(DEFAULT_ENDPOINT_CLASS_DIMENSION_KEY);
    }

    @Test
    public void DefaultMetricDimensionConfigurator_kitchen_sink_constructor_sets_fields_as_expected() {
        // give
        String metricName = UUID.randomUUID().toString();
        String responseCodeDimKey = UUID.randomUUID().toString();
        String uriDimKey = UUID.randomUUID().toString();
        String methodDimKey = UUID.randomUUID().toString();
        String endpointDimKey = UUID.randomUUID().toString();

        // when
        DefaultMetricDimensionConfigurator instance = new DefaultMetricDimensionConfigurator(
            metricName, responseCodeDimKey, uriDimKey, methodDimKey, endpointDimKey
        );

        // then
        assertThat(instance.metricName).isEqualTo(metricName);
        assertThat(instance.responseCodeDimensionKey).isEqualTo(responseCodeDimKey);
        assertThat(instance.uriDimensionKey).isEqualTo(uriDimKey);
        assertThat(instance.methodDimensionKey).isEqualTo(methodDimKey);
        assertThat(instance.endpointClassKey).isEqualTo(endpointDimKey);
    }

    @Test
    public void DefaultMetricDimensionConfigurator_setupMetricWithDimensions_works_as_expected() {
        // given
        BuilderTagger builderMock = mock(BuilderTagger.class);
        doReturn(builderMock).when(builderMock).withMetricName(anyString());
        doReturn(builderMock).when(builderMock).withDimension(anyString(), anyString());

        String metricName = UUID.randomUUID().toString();
        String responseCodeDimKey = UUID.randomUUID().toString();
        String uriDimKey = UUID.randomUUID().toString();
        String methodDimKey = UUID.randomUUID().toString();
        String endpointDimKey = UUID.randomUUID().toString();

        DefaultMetricDimensionConfigurator instance = new DefaultMetricDimensionConfigurator(
            metricName, responseCodeDimKey, uriDimKey, methodDimKey, endpointDimKey
        );

        int responseStatusCode = 242;
        int responseStatusCodeXXValue = 2;
        long elapsedTimeMillis = 42;
        String endpointClass = UUID.randomUUID().toString();
        String method = UUID.randomUUID().toString();

        // when
        BuilderTagger result = instance.setupMetricWithDimensions(
            builderMock, requestInfoMock, responseInfoMock, httpStateMock, responseStatusCode,
            responseStatusCodeXXValue, elapsedTimeMillis, endpointMock, endpointClass, method, matchingPathTemplate
        );

        // then
        assertThat(result).isSameAs(builderMock);
        verify(builderMock).withMetricName(metricName);
        verify(builderMock).withDimension(responseCodeDimKey, String.valueOf(responseStatusCode));
        verify(builderMock).withDimension(uriDimKey, matchingPathTemplate);
        verify(builderMock).withDimension(methodDimKey, method);
        verify(builderMock).withDimension(endpointDimKey, endpointClass);
        verifyNoMoreInteractions(builderMock);
    }

    @Test
    public void ChainedMetricDimensionConfigurator_constructor_sets_fields_as_expected() {
        // given
        MetricDimensionConfigurator first = mock(MetricDimensionConfigurator.class);
        MetricDimensionConfigurator second = mock(MetricDimensionConfigurator.class);

        // when
        ChainedMetricDimensionConfigurator instance = new ChainedMetricDimensionConfigurator(first, second);

        // then
        assertThat(instance.firstConfigurator).isSameAs(first);
        assertThat(instance.secondConfigurator).isSameAs(second);
    }

    @Test
    public void ChainedMetricDimensionConfigurator_setupMetricWithDimensions_chains_calls_as_expected() {
        // given
        MetricDimensionConfigurator first = mock(MetricDimensionConfigurator.class);
        MetricDimensionConfigurator second = mock(MetricDimensionConfigurator.class);
        ChainedMetricDimensionConfigurator instance = new ChainedMetricDimensionConfigurator(first, second);

        int responseStatusCode = 242;
        int responseStatusCodeXXValue = 2;
        long elapsedTimeMillis = 42;
        String endpointClass = UUID.randomUUID().toString();
        String method = UUID.randomUUID().toString();

        BuilderTagger origBuilderMock = mock(BuilderTagger.class);
        BuilderTagger firstResultBuilderMock = mock(BuilderTagger.class);
        BuilderTagger secondResultBuilderMock = mock(BuilderTagger.class);

        doReturn(firstResultBuilderMock).when(first).setupMetricWithDimensions(
            any(BuilderTagger.class), any(RequestInfo.class), any(ResponseInfo.class), any(HttpProcessingState.class),
            anyInt(), anyInt(), anyLong(), any(Endpoint.class), anyString(), anyString(), anyString()
        );
        doReturn(secondResultBuilderMock).when(second).setupMetricWithDimensions(
            any(BuilderTagger.class), any(RequestInfo.class), any(ResponseInfo.class), any(HttpProcessingState.class),
            anyInt(), anyInt(), anyLong(), any(Endpoint.class), anyString(), anyString(), anyString()
        );

        // when
        BuilderTagger finalResult = instance.setupMetricWithDimensions(
            origBuilderMock, requestInfoMock, responseInfoMock, httpStateMock, responseStatusCode,
            responseStatusCodeXXValue, elapsedTimeMillis, endpointMock, endpointClass, method, matchingPathTemplate
        );

        // then
        verify(first).setupMetricWithDimensions(
            origBuilderMock, requestInfoMock, responseInfoMock, httpStateMock, responseStatusCode,
            responseStatusCodeXXValue, elapsedTimeMillis, endpointMock, endpointClass, method, matchingPathTemplate
        );
        verify(second).setupMetricWithDimensions(
            firstResultBuilderMock, requestInfoMock, responseInfoMock, httpStateMock, responseStatusCode,
            responseStatusCodeXXValue, elapsedTimeMillis, endpointMock, endpointClass, method, matchingPathTemplate
        );
        assertThat(finalResult).isSameAs(secondResultBuilderMock);
    }
}