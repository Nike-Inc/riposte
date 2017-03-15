package com.nike.riposte.metrics.codahale.contrib;

import com.nike.internal.util.Pair;

import com.codahale.metrics.MetricRegistry;
import com.signalfx.codahale.reporter.DimensionInclusion;
import com.signalfx.codahale.reporter.SignalFxReporter;
import com.signalfx.codahale.reporter.SignalFxReporter.Builder;
import com.signalfx.metrics.auth.AuthToken;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.nike.riposte.metrics.codahale.contrib.SignalFxReporterFactory.DEFAULT_REPORTING_FREQUENCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.internal.util.reflection.Whitebox.getInternalState;

/**
 * Tests the functionality of {@link SignalFxReporterFactory}.
 *
 * @author Nic Munroe
 */
public class SignalFxReporterFactoryTest {

    private String apiKey;
    private SignalFxReporterFactory factory;
    private MetricRegistry metricRegistryMock;
    private Function<Builder, Builder> configuratorMock;
    private Pair<Long, TimeUnit> reporterFrequency;
    private Builder configuratorResult;

    private String customDimKey;
    private String customDimValue;

    @Before
    public void beforeMethod() {
        apiKey = UUID.randomUUID().toString();
        customDimKey = UUID.randomUUID().toString();
        customDimValue = UUID.randomUUID().toString();

        configuratorMock = mock(Function.class);

        configuratorResult = new Builder(metricRegistryMock, apiKey).addDimension(customDimKey, customDimValue);
        doReturn(configuratorResult).when(configuratorMock).apply(any(Builder.class));

        reporterFrequency = Pair.of(42L, TimeUnit.HOURS);

        factory = new SignalFxReporterFactory(apiKey, configuratorMock, reporterFrequency);

        metricRegistryMock = mock(MetricRegistry.class);
    }

    @Test
    public void single_arg_constructor_sets_fields_as_expected() {
        // given
        String apiKey = UUID.randomUUID().toString();

        // when
        SignalFxReporterFactory instance = new SignalFxReporterFactory(apiKey);

        // then
        assertThat(instance.signalFxApiKey).isEqualTo(apiKey);
        assertThat(instance.reporterConfigurator).isSameAs(Function.identity());
        assertThat(instance.signalFxReporter).isNull();
        assertThat(instance.reportingFrequencyInterval).isEqualTo(DEFAULT_REPORTING_FREQUENCY.getLeft());
        assertThat(instance.reportingFrequencyTimeUnit).isEqualTo(DEFAULT_REPORTING_FREQUENCY.getRight());
        assertThat(instance.getInterval()).isEqualTo(instance.reportingFrequencyInterval);
        assertThat(instance.getTimeUnit()).isEqualTo(instance.reportingFrequencyTimeUnit);
    }

    @Test
    public void kitchen_sink_constructor_sets_fields_as_expected() {
        // given
        String apiKey = UUID.randomUUID().toString();
        Function<Builder, Builder> configurator = mock(Function.class);
        Pair<Long, TimeUnit> reportingFreq = Pair.of(4242L, TimeUnit.MILLISECONDS);

        // when
        SignalFxReporterFactory instance = new SignalFxReporterFactory(apiKey, configurator, reportingFreq);

        // then
        assertThat(instance.signalFxApiKey).isEqualTo(apiKey);
        assertThat(instance.reporterConfigurator).isSameAs(configurator);
        assertThat(instance.reportingFrequencyInterval).isEqualTo(reportingFreq.getLeft());
        assertThat(instance.reportingFrequencyTimeUnit).isEqualTo(reportingFreq.getRight());
        assertThat(instance.signalFxReporter).isNull();
        assertThat(instance.getInterval()).isEqualTo(instance.reportingFrequencyInterval);
        assertThat(instance.getTimeUnit()).isEqualTo(instance.reportingFrequencyTimeUnit);
    }

    @Test
    public void verify_default_method_values() {
        // given
        String apiKey = UUID.randomUUID().toString();
        SignalFxReporterFactory instance = new SignalFxReporterFactory(apiKey);

        // then
        assertThat(instance.getInterval()).isEqualTo(DEFAULT_REPORTING_FREQUENCY.getLeft());
        assertThat(instance.getTimeUnit()).isEqualTo(DEFAULT_REPORTING_FREQUENCY.getRight());
        assertThat(instance.isScheduled()).isTrue();
    }

    @Test
    public void getReporter_passes_properly_configured_builder_to_reporterConfigurator_and_builds_the_result_of_reporterConfigurator() {
        // when
        SignalFxReporter result = factory.getReporter(metricRegistryMock);

        // then
        // Make sure the configurator function received a properly configured builder.
        ArgumentCaptor<Builder> origBuilderArgCaptor = ArgumentCaptor.forClass(Builder.class);
        verify(configuratorMock).apply(origBuilderArgCaptor.capture());
        Builder origBuilder = origBuilderArgCaptor.getValue();
        assertThat(getInternalState(origBuilder, "registry")).isSameAs(metricRegistryMock);
        assertThat(((AuthToken) getInternalState(origBuilder, "authToken")).getAuthToken()).isEqualTo(apiKey);

        // Make sure the thing returned by the configurator was used to build the final reporter.
        assertThat(
            ((Map<String, DimensionInclusion>)getInternalState(result, "defaultDimensions"))
                .get(customDimKey).getValue()
        ).isEqualTo(customDimValue);
    }

    @Test
    public void getReporter_caches_result_and_reuses_it_for_subsequent_calls() {
        // given
        SignalFxReporter initialResult = factory.getReporter(metricRegistryMock);
        verify(configuratorMock).apply(any(Builder.class));

        // when
        SignalFxReporter subsequentResult = factory.getReporter(metricRegistryMock);

        // then
        verifyNoMoreInteractions(configuratorMock);
        assertThat(subsequentResult).isSameAs(initialResult);
    }
}