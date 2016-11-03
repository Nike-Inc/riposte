package com.nike.riposte.metrics.codahale.contrib;

import com.nike.riposte.metrics.codahale.ReporterFactory;

import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reporter;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * @author pevans
 */
@SuppressWarnings("WeakerAccess")
public class DefaultGraphiteReporterFactory implements ReporterFactory {

    private final String prefix;
    private final String graphiteURL;
    private final Integer graphitePort;

    private Reporter reporter;

    public DefaultGraphiteReporterFactory(String prefix, String graphiteURL, Integer graphitePort) {
        this.prefix = prefix;
        this.graphiteURL = graphiteURL;
        this.graphitePort = graphitePort;
    }

    @Override
    public synchronized Reporter getReporter(MetricRegistry registry) {
        if (null == reporter) {
            Graphite graphite = new Graphite(new InetSocketAddress(graphiteURL, graphitePort));
            reporter = GraphiteReporter.forRegistry(registry)
                                       .prefixedWith(prefix)
                                       .convertRatesTo(TimeUnit.SECONDS)
                                       .convertDurationsTo(TimeUnit.MILLISECONDS)
                                       .filter(MetricFilter.ALL)
                                       .build(graphite);
        }
        return reporter;
    }


}
