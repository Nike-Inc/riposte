package com.nike.riposte.metrics.codahale.contrib;

import com.nike.riposte.metrics.codahale.ReporterFactory;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reporter;
import com.codahale.metrics.jmx.JmxReporter;

/**
 * @author pevans
 */
@SuppressWarnings("WeakerAccess")
public class DefaultJMXReporterFactory implements ReporterFactory {

    private Reporter reporter;

    @Override
    public synchronized Reporter getReporter(MetricRegistry registry) {
        if (null == reporter) {
            reporter = JmxReporter.forRegistry(registry).build();
        }
        return reporter;
    }

    @Override
    public boolean isScheduled() {
        return false;
    }

}
