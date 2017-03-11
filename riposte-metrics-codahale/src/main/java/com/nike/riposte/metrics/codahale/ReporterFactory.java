package com.nike.riposte.metrics.codahale;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reporter;

import java.util.concurrent.TimeUnit;

/**
 * An Interface for classes that can create configured Reporters.
 *
 * @author pevans
 */
public interface ReporterFactory {

    /**
     * Get the singleton instance of the Reporter wrapped by this ReporterFactory
     *
     * @param registry
     *     The MetricRegistry that the Reporter will be reporting on
     */
    Reporter getReporter(MetricRegistry registry);

    /**
     * True if this factory provides scheduled reporters, normally these reporters will implement ScheduledReporter
     * but the CodahaleMetricsEngine can deal with reporters which dont implement ScheduledReporter but do have a
     * start(long interval, TimeUnit unit) method.
     *
     * @return true if Reporters created by this Factory are scheduled otherwise false
     */
    default boolean isScheduled() {
        return true;
    }

    /**
     * The interval at which Reporters produced by this Factory should report
     */
    default Long getInterval() {
        if (isScheduled()) {
            return 1L;
        }
        else {
            return null;
        }
    }

    /**
     * The units associated with the interval
     */
    default TimeUnit getTimeUnit() {
        if (isScheduled()) {
            return TimeUnit.MINUTES;
        }
        else {
            return null;
        }
    }

    /**
     * Override this method to control the Reporter startup.  If not implemented the CodahaleMetricsEngine will start
     * the Reporter with standard means
     */
    default void startReporter() {
        throw new UnsupportedOperationException("This ReporterFactory does not expose custom start behavior");
    }

    /**
     * Override this method to control the Reporter shutdown.  If not implemented the CodahaleMetricsEngine will stop
     * the Reporter with standard means
     */
    default void stopReporter() {
        throw new UnsupportedOperationException("This ReporterFactory does not expose custom stop behavior");
    }
}
