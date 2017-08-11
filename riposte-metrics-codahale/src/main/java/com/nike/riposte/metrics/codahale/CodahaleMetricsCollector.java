package com.nike.riposte.metrics.codahale;

import com.nike.riposte.metrics.MetricsCollector;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import com.codahale.metrics.Timer.Context;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * This is a straightforward implementation of the {@link MetricsCollector} interface. The only additional API exposed
 * by this class is {@link #getMetricRegistry()} and some helper methods like {@link #getNamedTimer(String)} and
 * {@link #registerNamedMetric(String, Metric)} which allows consumers to gain access to the core {@link
 * MetricRegistry} and do things like register custom metrics.
 *
 * @author pevans
 */
@SuppressWarnings("WeakerAccess")
public class CodahaleMetricsCollector implements MetricsCollector {

    protected final MetricRegistry metricRegistry;

    public CodahaleMetricsCollector() {
        this(new MetricRegistry());
    }

    public CodahaleMetricsCollector(MetricRegistry registry) {
        this.metricRegistry = registry;
    }

    public MetricRegistry getMetricRegistry() {
        return metricRegistry;
    }

    public CodahaleMetricsCollector registerAll(String prefix, MetricSet metricSet) {
        for (Map.Entry<String, Metric> entry : metricSet.getMetrics().entrySet()) {
            if (entry.getValue() instanceof MetricSet) {
                registerAll(prefix + "." + entry.getKey(), (MetricSet) entry.getValue());
            }
            else {
                registerNamedMetric(prefix + "." + entry.getKey(), entry.getValue());
            }
        }
        return this;
    }

    public Timer getNamedTimer(String timerName) {
        return metricRegistry.timer(timerName);
    }

    public Meter getNamedMeter(String meterName) {
        return metricRegistry.meter(meterName);
    }

    public Counter getNamedCounter(String counterName) {
        return metricRegistry.counter(counterName);
    }

    public Histogram getNamedHistogram(String histogramName) {
        return metricRegistry.histogram(histogramName);
    }

    public <M extends Metric> M registerNamedMetric(String metricName, M metric) {
        return metricRegistry.register(metricName, metric);
    }

    @Override
    public <T, R> R timed(Function<T, R> f, T arg, String timerName) {
        final Context context = getNamedTimer(timerName).time();
        try {
            return f.apply(arg);
        }
        finally {
            context.stop();
        }

    }

    @Override
    public void timed(Runnable r, String timerName) {
        final Context context = getNamedTimer(timerName).time();
        try {
            r.run();
        }
        finally {
            context.stop();
        }
    }

    @Override
    public <V> V timed(Callable<V> c, String timerName) throws Exception {
        final Context context = getNamedTimer(timerName).time();
        try {
            return c.call();
        }
        finally {
            context.stop();
        }
    }

    @Override
    public <T> void timed(Consumer<T> c, T arg, String timerName) {
        final Context context = getNamedTimer(timerName).time();
        try {
            c.accept(arg);
        }
        finally {
            context.stop();
        }
    }

    @Override
    public <T, U> void timed(BiConsumer<T, U> bc, T arg1, U arg2, String timerName) {
        final Context context = getNamedTimer(timerName).time();
        try {
            bc.accept(arg1, arg2);
        }
        finally {
            context.stop();
        }
    }

    @Override
    public <T, U, R> R timed(BiFunction<T, U, R> bf, T arg1, U arg2, String timerName) {
        final Context context = getNamedTimer(timerName).time();
        try {
            return bf.apply(arg1, arg2);
        }
        finally {
            context.stop();
        }

    }

    @Override
    public void metered(Runnable r, String meterName, long events) {
        final Meter meter = getNamedMeter(meterName);
        try {
            r.run();
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public <V> V metered(Callable<V> c, String meterName, long events) throws Exception {
        final Meter meter = getNamedMeter(meterName);
        try {
            return c.call();
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public <T, R> R metered(Function<T, R> f, T arg, String meterName, long events) {
        final Meter meter = getNamedMeter(meterName);
        try {
            return f.apply(arg);
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public <T> void metered(Consumer<T> c, T arg, String meterName, long events) {
        final Meter meter = getNamedMeter(meterName);
        try {
            c.accept(arg);
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public <T, U> void metered(BiConsumer<T, U> bc, T arg1, U arg2, String meterName, long events) {
        final Meter meter = getNamedMeter(meterName);
        try {
            bc.accept(arg1, arg2);
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public <T, U, R> R metered(BiFunction<T, U, R> bf, T arg1, U arg2, String meterName, long events) {
        final Meter meter = getNamedMeter(meterName);
        try {
            return bf.apply(arg1, arg2);
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public void counted(Runnable r, String counterName, long delta) {
        final Counter counter = getNamedCounter(counterName);
        try {
            r.run();
        }
        finally {
            processCounter(counter, delta);
        }
    }

    @Override
    public <V> V counted(Callable<V> c, String counterName, long delta) throws Exception {
        final Counter counter = getNamedCounter(counterName);
        try {
            return c.call();
        }
        finally {
            processCounter(counter, delta);
        }
    }

    @Override
    public <T, R> R counted(Function<T, R> f, T arg, String counterName, long delta) {
        final Counter counter = getNamedCounter(counterName);
        try {
            return f.apply(arg);
        }
        finally {
            processCounter(counter, delta);
        }
    }

    @Override
    public <T> void counted(Consumer<T> c, T arg, String counterName, long delta) {
        final Counter counter = getNamedCounter(counterName);
        try {
            c.accept(arg);
        }
        finally {
            processCounter(counter, delta);
        }

    }

    @Override
    public <T, U> void counted(BiConsumer<T, U> bc, T arg1, U arg2, String counterName, long delta) {
        final Counter counter = getNamedCounter(counterName);
        try {
            bc.accept(arg1, arg2);
        }
        finally {
            processCounter(counter, delta);
        }
    }

    @Override
    public <T, U, R> R counted(BiFunction<T, U, R> bf, T arg1, U arg2, String counterName, long delta) {
        final Counter counter = getNamedCounter(counterName);
        try {
            return bf.apply(arg1, arg2);
        }
        finally {
            processCounter(counter, delta);
        }
    }

    protected static void processCounter(Counter counter, long delta) {
        if (delta > 0) {
            counter.inc(delta);
        }
        else if (delta < 0) {
            counter.dec(delta * -1);
        }
    }
}
