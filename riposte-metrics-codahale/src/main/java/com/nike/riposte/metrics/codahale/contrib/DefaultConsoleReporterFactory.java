package com.nike.riposte.metrics.codahale.contrib;

import com.nike.riposte.metrics.codahale.ReporterFactory;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reporter;

import java.util.concurrent.TimeUnit;

/**
 * @author pevans
 */
@SuppressWarnings("WeakerAccess")
public class DefaultConsoleReporterFactory implements ReporterFactory {

    private Reporter reporter;

    @Override
    public synchronized Reporter getReporter(MetricRegistry registry) {
        if (null == reporter) {
            reporter = ConsoleReporter.forRegistry(registry)
                                      .convertRatesTo(TimeUnit.SECONDS)
                                      .convertDurationsTo(TimeUnit.MILLISECONDS)
                                      .build();
        }
        return reporter;
    }

    @Override
    public Long getInterval() {
        return 5L;
    }

    @Override
    public TimeUnit getTimeUnit() {
        return TimeUnit.SECONDS;
    }

}
