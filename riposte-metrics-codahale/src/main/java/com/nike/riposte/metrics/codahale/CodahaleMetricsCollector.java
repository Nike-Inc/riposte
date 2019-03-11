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

import org.jetbrains.annotations.NotNull;

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

    protected final @NotNull MetricRegistry metricRegistry;

    public CodahaleMetricsCollector() {
        this(new MetricRegistry());
    }

    public CodahaleMetricsCollector(@NotNull MetricRegistry registry) {
        //noinspection ConstantConditions
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null.");
        }
        this.metricRegistry = registry;
    }

    public @NotNull MetricRegistry getMetricRegistry() {
        return metricRegistry;
    }

    @SuppressWarnings("UnusedReturnValue")
    public @NotNull CodahaleMetricsCollector registerAll(String prefix, @NotNull MetricSet metricSet) {
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

    public @NotNull Timer getNamedTimer(@NotNull String timerName) {
        return metricRegistry.timer(timerName);
    }

    public @NotNull Meter getNamedMeter(@NotNull String meterName) {
        return metricRegistry.meter(meterName);
    }

    public @NotNull Counter getNamedCounter(@NotNull String counterName) {
        return metricRegistry.counter(counterName);
    }

    public @NotNull Histogram getNamedHistogram(@NotNull String histogramName) {
        return metricRegistry.histogram(histogramName);
    }

    public <M extends Metric> @NotNull M registerNamedMetric(@NotNull String metricName, @NotNull M metric) {
        return metricRegistry.register(metricName, metric);
    }

    @Override
    public <T, R> R timed(@NotNull Function<T, R> f, T arg, @NotNull String timerName) {
        final Context context = getNamedTimer(timerName).time();
        try {
            return f.apply(arg);
        }
        finally {
            context.stop();
        }

    }

    @Override
    public void timed(@NotNull Runnable r, @NotNull String timerName) {
        final Context context = getNamedTimer(timerName).time();
        try {
            r.run();
        }
        finally {
            context.stop();
        }
    }

    @Override
    public <V> V timed(@NotNull Callable<V> c, @NotNull String timerName) throws Exception {
        final Context context = getNamedTimer(timerName).time();
        try {
            return c.call();
        }
        finally {
            context.stop();
        }
    }

    @Override
    public <T> void timed(@NotNull Consumer<T> c, T arg, @NotNull String timerName) {
        final Context context = getNamedTimer(timerName).time();
        try {
            c.accept(arg);
        }
        finally {
            context.stop();
        }
    }

    @Override
    public <T, U> void timed(@NotNull BiConsumer<T, U> bc, T arg1, U arg2, @NotNull String timerName) {
        final Context context = getNamedTimer(timerName).time();
        try {
            bc.accept(arg1, arg2);
        }
        finally {
            context.stop();
        }
    }

    @Override
    public <T, U, R> R timed(@NotNull BiFunction<T, U, R> bf, T arg1, U arg2, @NotNull String timerName) {
        final Context context = getNamedTimer(timerName).time();
        try {
            return bf.apply(arg1, arg2);
        }
        finally {
            context.stop();
        }

    }

    @Override
    public void metered(@NotNull Runnable r, @NotNull String meterName, long events) {
        final Meter meter = getNamedMeter(meterName);
        try {
            r.run();
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public <V> V metered(@NotNull Callable<V> c, @NotNull String meterName, long events) throws Exception {
        final Meter meter = getNamedMeter(meterName);
        try {
            return c.call();
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public <T, R> R metered(@NotNull Function<T, R> f, T arg, @NotNull String meterName, long events) {
        final Meter meter = getNamedMeter(meterName);
        try {
            return f.apply(arg);
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public <T> void metered(@NotNull Consumer<T> c, T arg, @NotNull String meterName, long events) {
        final Meter meter = getNamedMeter(meterName);
        try {
            c.accept(arg);
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public <T, U> void metered(@NotNull BiConsumer<T, U> bc, T arg1, U arg2, @NotNull String meterName, long events) {
        final Meter meter = getNamedMeter(meterName);
        try {
            bc.accept(arg1, arg2);
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public <T, U, R> R metered(@NotNull BiFunction<T, U, R> bf, T arg1, U arg2, @NotNull String meterName, long events) {
        final Meter meter = getNamedMeter(meterName);
        try {
            return bf.apply(arg1, arg2);
        }
        finally {
            meter.mark(events);
        }
    }

    @Override
    public void counted(@NotNull Runnable r, @NotNull String counterName, long delta) {
        final Counter counter = getNamedCounter(counterName);
        try {
            r.run();
        }
        finally {
            processCounter(counter, delta);
        }
    }

    @Override
    public <V> V counted(@NotNull Callable<V> c, @NotNull String counterName, long delta) throws Exception {
        final Counter counter = getNamedCounter(counterName);
        try {
            return c.call();
        }
        finally {
            processCounter(counter, delta);
        }
    }

    @Override
    public <T, R> R counted(@NotNull Function<T, R> f, T arg, @NotNull String counterName, long delta) {
        final Counter counter = getNamedCounter(counterName);
        try {
            return f.apply(arg);
        }
        finally {
            processCounter(counter, delta);
        }
    }

    @Override
    public <T> void counted(@NotNull Consumer<T> c, T arg, @NotNull String counterName, long delta) {
        final Counter counter = getNamedCounter(counterName);
        try {
            c.accept(arg);
        }
        finally {
            processCounter(counter, delta);
        }

    }

    @Override
    public <T, U> void counted(@NotNull BiConsumer<T, U> bc, T arg1, U arg2, @NotNull String counterName, long delta) {
        final Counter counter = getNamedCounter(counterName);
        try {
            bc.accept(arg1, arg2);
        }
        finally {
            processCounter(counter, delta);
        }
    }

    @Override
    public <T, U, R> R counted(@NotNull BiFunction<T, U, R> bf, T arg1, U arg2, @NotNull String counterName, long delta) {
        final Counter counter = getNamedCounter(counterName);
        try {
            return bf.apply(arg1, arg2);
        }
        finally {
            processCounter(counter, delta);
        }
    }

    protected static void processCounter(@NotNull Counter counter, long delta) {
        if (delta > 0) {
            counter.inc(delta);
        }
        else if (delta < 0) {
            counter.dec(delta * -1);
        }
    }
}
