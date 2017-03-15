package com.nike.riposte.metrics.codahale.contrib;

import com.nike.internal.util.Pair;
import com.nike.riposte.metrics.codahale.ReporterFactory;

import com.codahale.metrics.MetricRegistry;
import com.signalfx.codahale.reporter.SignalFxReporter;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A {@link ReporterFactory} for {@link SignalFxReporter}.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class SignalFxReporterFactory implements ReporterFactory {

    protected SignalFxReporter signalFxReporter;
    protected final String signalFxApiKey;
    protected final Function<SignalFxReporter.Builder, SignalFxReporter.Builder> reporterConfigurator;
    protected final long reportingFrequencyInterval;
    protected final TimeUnit reportingFrequencyTimeUnit;

    /**
     * The default reporting frequency that will be used for {@link #getInterval()} and {@link #getTimeUnit()} if you
     * don't specify a custom one.
     */
    public static final Pair<Long, TimeUnit> DEFAULT_REPORTING_FREQUENCY = Pair.of(10L, TimeUnit.SECONDS);

    /**
     * Creates a new instance with the given SignalFx API key (auth token) and no custom reporter configuration.
     * If you want to adjust the settings on the {@link SignalFxReporter.Builder} before the SignalFx reporter is
     * created, or if you want to adjust the reporting frequency, then you should use the {@link
     * SignalFxReporterFactory#SignalFxReporterFactory(String, Function, Pair)} constructor instead.
     *
     * @param signalFxApiKey The SignalFx API key (auth token) to use when reporting data to SignalFx.
     */
    public SignalFxReporterFactory(String signalFxApiKey) {
        this(signalFxApiKey, null, null);
    }

    /**
     * Creates a new instance with the given SignalFX API key (auth token), the given reporter configurator function,
     * and the given reporting frequency. The SignalFx {@link SignalFxReporter.Builder} will be passed through the given
     * function before building the reporter, allowing you to customize anything you want about the reporter. For
     * example:
     *
     * <pre>
     *      new SignalFxReporterFactory(
     *          "mySignalFxApiKey",
     *          (reporterConfig) -> reporterConfig.addDimension("foo", "bar")
     *                                            .addUniqueDimension("host_foo", "host_bar")
     *                                            .setDetailsToAdd(SignalFxReporter.MetricDetails.ALL),
     *          Pair.of(10L, TimeUnit.SECONDS)
     *      );
     * </pre>
     *
     * <p>If you pass in null for the reporterConfigurator argument then {@link Function#identity()} will be used for
     * a fully default SignalFx reporter.
     *
     * <p>The {@code reportingFrequency} passed in will be used to set what is returned by the {@link #getInterval()}
     * and {@link #getTimeUnit()} methods, which controls the reporting frequency of the SignalFx reporter. If you pass
     * in null for reportingFrequency then {@link #DEFAULT_REPORTING_FREQUENCY} will be used (10 seconds).
     *
     * @param signalFxApiKey The SignalFx API key (auth token) to use when reporting data to SignalFx.
     * @param reporterConfigurator A function that performs any custom reporter configuration you want done on the
     * resulting {@link SignalFxReporter}. If you pass in null for the reporterConfigurator argument then {@link
     * Function#identity()} will be used for a fully default SignalFx reporter.
     * @param reportingFrequency The reporting frequency for the SignalFx reporter. If you pass in null then
     * {@link #DEFAULT_REPORTING_FREQUENCY} will be used (10 seconds).
     */
    public SignalFxReporterFactory(String signalFxApiKey,
                                   Function<SignalFxReporter.Builder, SignalFxReporter.Builder> reporterConfigurator,
                                   Pair<Long, TimeUnit> reportingFrequency) {
        if (reporterConfigurator == null)
            reporterConfigurator = Function.identity();

        if (reportingFrequency == null)
            reportingFrequency = DEFAULT_REPORTING_FREQUENCY;
        
        this.signalFxApiKey = signalFxApiKey;
        this.reporterConfigurator = reporterConfigurator;
        this.reportingFrequencyInterval = reportingFrequency.getLeft();
        this.reportingFrequencyTimeUnit = reportingFrequency.getRight();
    }

    @Override
    public synchronized SignalFxReporter getReporter(MetricRegistry registry) {
        if (signalFxReporter == null) {
            signalFxReporter = reporterConfigurator.apply(
                new SignalFxReporter.Builder(registry,signalFxApiKey)
            ).build();
        }

        return signalFxReporter;
    }

    @Override
    public Long getInterval() {
        return reportingFrequencyInterval;
    }

    @Override
    public TimeUnit getTimeUnit() {
        return reportingFrequencyTimeUnit;
    }

    @Override
    public boolean isScheduled() {
        // SignalFxReporter extends ScheduledReporter
        return true;
    }
}
