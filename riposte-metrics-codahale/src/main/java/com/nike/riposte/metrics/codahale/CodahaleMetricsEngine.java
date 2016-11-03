package com.nike.riposte.metrics.codahale;

import com.codahale.metrics.Reporter;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.jvm.BufferPoolMetricSet;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

/**
 * Implementers of Riposte servers will want to create an instance of this class and supply it with the desired metrics
 * reporters. This class also contains the {@link CodahaleMetricsCollector} that will be used by classes supplying
 * metric data to the reporters.
 *
 * @author pevans
 */
@SuppressWarnings("WeakerAccess")
public class CodahaleMetricsEngine {

    private static final Logger logger = LoggerFactory.getLogger(CodahaleMetricsEngine.class);

    protected final CodahaleMetricsCollector metricsCollector;
    protected final Collection<ReporterFactory> reporters = Collections.synchronizedSet(new HashSet<>());

    protected boolean jvmMetricsAdded = false;
    protected boolean started = false;

    /**
     * Provides a CodahaleMetricsEngine with the default CodahaleMetricsCollector
     */
    public CodahaleMetricsEngine() {
        this(new CodahaleMetricsCollector());
    }

    /**
     * Provides a CodahaleMetricsEngine with a supplied CodahaleMetricsCollector.  A consumer might use this if for
     * whatever reason extended it or created one with a 'special' MetricsRegistry
     */
    public CodahaleMetricsEngine(CodahaleMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Provides a CodahaleMetricsEngine with a supplied CodahaleMetricsCollector and some ReporterFactories.
     * Alternatively, ReporterFactories can be supplied after construction.
     */
    public CodahaleMetricsEngine(CodahaleMetricsCollector metricsCollector, Collection<ReporterFactory> reporters) {
        this(metricsCollector);
        this.reporters.addAll(reporters);
    }

    /**
     * Provides a CodahaleMetricsEngine with the default CodahaleMetricsCollector and some ReporterFactories
     */
    public CodahaleMetricsEngine(Collection<ReporterFactory> reporters) {
        this(new CodahaleMetricsCollector(), reporters);
    }

    /**
     * Add a Reporter to the engine.  If the engine has been started already, the reporter will be started as it is
     * added.
     *
     * @param reporter
     *     the ReporterFactory to add
     */
    public CodahaleMetricsEngine addReporter(ReporterFactory reporter) {
        reporters.add(reporter);
        if (started) {
            startReporter(reporter);
        }
        return this;
    }

    /**
     * Starts the Reporters
     */
    public CodahaleMetricsEngine start() {
        reporters.forEach(this::startReporter);
        started = true;
        return this;
    }

    /**
     * Stops the Reporters
     */
    public CodahaleMetricsEngine stop() {
        reporters.forEach(this::stopReporter);
        started = false;
        return this;
    }

    /**
     * Adds JVM MetricSets to this engine.  By default JVM metrics are not placed in the Registry
     */
    public CodahaleMetricsEngine reportJvmMetrics() {
        // add JVM metrics
        if (!jvmMetricsAdded) {
            metricsCollector.registerAll("JVM-gc", new GarbageCollectorMetricSet());
            metricsCollector
                .registerAll("JVM-buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()));
            metricsCollector.registerAll("JVM-memory", new MemoryUsageGaugeSet());
            metricsCollector.registerAll("JVM-threads", new ThreadStatesGaugeSet());
            jvmMetricsAdded = true;
        }
        return this;
    }

    /**
     * Get the CodahaleMetricsCollector that is being used by this engine instance
     */
    public CodahaleMetricsCollector getMetricsCollector() {
        return metricsCollector;
    }


    final boolean startReporter(ReporterFactory reporterFactory) {
        boolean success = false;
        try {
            reporterFactory.startReporter();
            return true;
        }
        catch (UnsupportedOperationException e) {
            logger.debug("ReporterFactory {} does not expose custom startup behavior.",
                         reporterFactory.getClass().getName());
        }
        Reporter reporter = reporterFactory.getReporter(metricsCollector.getMetricRegistry());
        if (reporter instanceof ScheduledReporter) {
            ((ScheduledReporter) reporter).start(reporterFactory.getInterval(), reporterFactory.getTimeUnit());
            return true;
        }
        //try to figure out what to do via reflection since there is no standard way to start a Reporter
        try {
            if (!reporterFactory.isScheduled() || reporterFactory.getInterval() == null) {
                //look for a start() method
                Method m = reporter.getClass().getMethod("start", (Class<?>[]) null);
                if (null != m) {
                    m.invoke(reporter, (Object[]) null);
                    success = true;
                }
                else {
                    logger.warn("Unable to locate a start() method on Reporter: {}", reporter.getClass());
                }
            }
            else {
                //look for a start(long,TimeUnit) method
                Method m = reporter.getClass().getMethod("start", long.class, TimeUnit.class);
                if (null != m) {
                    m.invoke(reporter, reporterFactory.getInterval(), reporterFactory.getTimeUnit());
                    success = true;
                }
                else {
                    logger.warn("Unable to locate a start(long,TimeUnit) method on Reporter: {}", reporter.getClass());
                }
            }
        }
        catch (Throwable t) {
            logger.warn("Unable to start reporter of type {}", reporter.getClass(), t);
        }
        return success;
    }

    final boolean stopReporter(ReporterFactory reporterFactory) {
        boolean success = false;
        try {
            reporterFactory.stopReporter();
            return true;
        }
        catch (UnsupportedOperationException e) {
            logger.debug("ReporterFactory {} does not expose custom startup behavior.",
                         reporterFactory.getClass().getName());
        }
        try {
            Method m = reporterFactory.getClass().getMethod("stop", (Class<?>[]) null);
            m.invoke(reporterFactory, (Object[]) null);
            success = true;
        }
        catch (Throwable t) {
            logger.warn("Unable to stop reporter of type {}", reporterFactory.getClass(), t);
        }
        return success;
    }


}
