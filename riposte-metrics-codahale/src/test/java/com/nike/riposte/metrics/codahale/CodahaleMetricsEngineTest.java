package com.nike.riposte.metrics.codahale;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reporter;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Timer;

import org.junit.Before;
import org.junit.Test;

import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author pevans
 */
public class CodahaleMetricsEngineTest {

    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception {
    }

    @Test
    public void testStartup() {
        SimpleTestReporter str = new SimpleTestReporter();
        WeirdScheduledTestReporter wschtr = new WeirdScheduledTestReporter();
        ScheduledTestReporter schtr = new ScheduledTestReporter();
        BrokenReporter br = new BrokenReporter();
        CodahaleMetricsEngine engine =
            new CodahaleMetricsEngine().addReporter(wschtr).addReporter(schtr).reportJvmMetrics().start();
        assertTrue(wschtr.started());
        assertTrue(schtr.started());
        assertFalse(wschtr.stopped());
        assertFalse(schtr.stopped());

        engine.addReporter(str);
        assertTrue(str.started());

        assertFalse(engine.startReporter(br));
        assertFalse(engine.stopReporter(br));
        engine.stop();
        assertTrue(str.stopped());
        assertTrue(schtr.stopped());
        assertTrue(wschtr.stopped());
    }

    static class SimpleTestReporter implements TestReporter, ReporterFactory {

        boolean startCalled = false;
        boolean stopCalled = false;

        public void start() {
            startCalled = true;
        }

        public void stop() {
            stopCalled = true;
        }

        @Override
        public boolean started() {
            return startCalled;
        }

        @Override
        public boolean stopped() {
            return stopCalled;
        }

        @Override
        public Reporter getReporter(MetricRegistry registry) {
            return this;
        }

        @Override
        public boolean isScheduled() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    static class WeirdScheduledTestReporter implements TestReporter, ReporterFactory {

        boolean startCalled = false;
        boolean stopCalled = false;

        public void start(long interval, TimeUnit unit) {
            startCalled = true;
        }

        public void stop() {
            stopCalled = true;
        }

        @Override
        public boolean started() {
            return startCalled;
        }

        @Override
        public boolean stopped() {
            return stopCalled;
        }

        @Override
        public Reporter getReporter(MetricRegistry registry) {
            return this;
        }

        @Override
        public void close() {
        }
    }

    static class ScheduledTestReporter extends ScheduledReporter implements ReporterFactory, TestReporter {

        boolean startCalled = false;
        boolean stopCalled = false;

        protected ScheduledTestReporter() {
            super(new MetricRegistry(), "test reporter", null, TimeUnit.SECONDS, TimeUnit.SECONDS);
        }

        @Override
        public void start(long interval, TimeUnit unit) {
            startCalled = true;
        }

        @Override
        public void stop() {
            stopCalled = true;
        }

        @Override
        public void report(SortedMap<String, Gauge> gauges, SortedMap<String, Counter> counters,
                           SortedMap<String, Histogram> histograms, SortedMap<String, Meter> meters,
                           SortedMap<String, Timer> timers) {

        }

        @Override
        public Reporter getReporter(MetricRegistry registry) {
            return this;
        }

        @Override
        public boolean started() {
            return startCalled;
        }

        @Override
        public boolean stopped() {
            return stopCalled;
        }

    }

    interface TestReporter extends Reporter {

        public boolean started();

        public boolean stopped();
    }

    static class BrokenReporter implements ReporterFactory, Reporter {

        @Override
        public Reporter getReporter(MetricRegistry registry) {
            return this;
        }

        @Override
        public void close() {
        }
    }

}
