package com.nike.riposte.metrics.codahale.contrib;

import com.nike.riposte.metrics.codahale.CodahaleMetricsEngine;
import com.nike.riposte.metrics.codahale.ReporterFactory;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reporter;
import com.codahale.metrics.Slf4jReporter;

import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * @author pevans
 */
@SuppressWarnings("WeakerAccess")
public class DefaultSLF4jReporterFactory implements ReporterFactory {

    private final String prefix;

    private Reporter reporter;

    public DefaultSLF4jReporterFactory() {
        this(CodahaleMetricsEngine.class.getSimpleName());
    }

    public DefaultSLF4jReporterFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public synchronized Reporter getReporter(MetricRegistry registry) {
        if (null == reporter) {
            reporter = Slf4jReporter.forRegistry(registry)
                                    .outputTo(LoggerFactory.getLogger(prefix))
                                    .convertRatesTo(TimeUnit.SECONDS)
                                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                                    .build();
        }
        return reporter;
    }

}
