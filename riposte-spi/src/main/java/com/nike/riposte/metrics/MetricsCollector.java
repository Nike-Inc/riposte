package com.nike.riposte.metrics;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * This interface allows code to be instrumented with timers, meters, and counters which are managed by a Metric
 * collection tool such as Dropwizard metrics.  This Interface is simpler than it looks.  It seems complicated because
 * There are so many different forms of closures in Java at which must be explicityly supported. At the end of the day
 * these calls all boil down to something like the following: <code> timed((arg) -> {...code that operates on 'arg'},
 * timerName); </code> or <code> timed(someObject:someMethod,arg,timerName) </code> while those patterns all look the
 * same in code there are many compile time permutations due to the number of arguments and return values.
 *
 * @author pevans
 */
public interface MetricsCollector {

    /**
     * Execute some code and note how long it ran
     *
     * @param r
     *     A runnable or closure with no arguments and no returns
     * @param timerName
     *     The name of the timer to update with the measurements
     */
    void timed(@NotNull Runnable r, @NotNull String timerName);

    /**
     * Execute some code and note how long it ran
     *
     * @param c
     *     A Callable to run
     * @param timerName
     *     The name of the timer to update with the measurements
     *
     * @return an instance of V
     */
    <V> V timed(@NotNull Callable<V> c, @NotNull String timerName) throws Exception;

    /**
     * Execute some code and note how long it ran
     *
     * @param f
     *     a Function to run.  aka a closure with one argument and a return value
     * @param arg
     *     The argument to the function
     * @param timerName
     *     The name of the timer to update
     *
     * @return The results of the function
     */
    <T, R> R timed(@NotNull Function<T, R> f, T arg, @NotNull String timerName);

    /**
     * Execute some code and note how long it ran
     *
     * @param c
     *     a Consumer to run.  aka a closure with one argument and no return value
     * @param arg
     *     The argument to the function
     * @param timerName
     *     The name of the timer to update
     */
    <T> void timed(@NotNull Consumer<T> c, T arg, @NotNull String timerName);

    /**
     * Execute some code and note how long it ran
     *
     * @param bc
     *     A BiConsumer to run.  aka a closure with two arguments and no return value
     * @param arg1
     *     The first argument
     * @param arg2
     *     The second argument
     * @param timerName
     *     The name of the timer to update
     */
    <T, U> void timed(@NotNull BiConsumer<T, U> bc, T arg1, U arg2, @NotNull String timerName);

    /**
     * Execute some code and note how long it ran
     *
     * @param bf
     *     A BiFunction to run.  aka a closure with two arguments and a return value
     * @param arg1
     *     The first argument
     * @param arg2
     *     The second argument
     * @param timerName
     *     The name of the timer to update
     *
     * @return The resutls of processing
     */
    <T, U, R> R timed(@NotNull BiFunction<T, U, R> bf, T arg1, U arg2, @NotNull String timerName);

    /**
     * Execute some code and update the specified event rate metric.  This invocation represents a single event.
     *
     * @param r
     *     A Runnable to run or a closure with no arguments and no returns
     * @param meterName
     *     The meter to update
     */
    default void metered(@NotNull Runnable r, @NotNull String meterName) {
        metered(r, meterName, 1L);
    }

    /**
     * Execute some code and update the specified event rate metric.
     *
     * @param r
     *     A Runnable to run or a closure with no arguments and no returns
     * @param meterName
     *     The meter to update
     * @param events
     *     The number of events that this invocation represents
     */
    void metered(@NotNull Runnable r, @NotNull String meterName, long events);

    /**
     * Execute some code and update the specified event rate metric
     *
     * @param c
     *     A Callable to execute
     * @param meterName
     *     The meter to update
     * @param events
     *     The number of events that this invocation represents
     *
     * @return The result of processing
     */
    <V> V metered(@NotNull Callable<V> c, @NotNull String meterName, long events) throws Exception;

    /**
     * Execute some code and update the specified event rate metric.  This invocation represents a single event
     *
     * @param c
     *     a Callable to execute
     * @param meterName
     *     The meter to update
     *
     * @return The result of processing
     */
    default <V> V metered(@NotNull Callable<V> c, @NotNull String meterName) throws Exception {
        return metered(c, meterName, 1L);
    }

    /**
     * Execute some code and update the specified event rate metric
     *
     * @param f
     *     A Function to execute.  aka a closure with one argument and a returned value
     * @param arg
     *     The argument to the function
     * @param meterName
     *     The meter to update
     * @param events
     *     The number of events this invocation represents
     *
     * @return The result of processing
     */
    <T, R> R metered(@NotNull Function<T, R> f, T arg, @NotNull String meterName, long events);

    /**
     * Execute some code and update the specified event rate metric.  This invocation represents a single event
     *
     * @param f
     *     A Function to execute.  aka a closure with one argument and a returned value
     * @param arg
     *     The argument to the function
     * @param meterName
     *     The meter to update
     *
     * @return The result of processing
     */
    default <T, R> R metered(@NotNull Function<T, R> f, T arg, @NotNull String meterName) {
        return metered(f, arg, meterName, 1L);
    }

    /**
     * Execute some code and update the specified event rate metric.
     *
     * @param c
     *     A Consumer to execute.  aka a closure with one argument and no returned value
     * @param arg
     *     The argument to the function
     * @param meterName
     *     The meter to update
     * @param events
     *     the number of events this invocation represents
     */
    <T> void metered(@NotNull Consumer<T> c, T arg, @NotNull String meterName, long events);

    /**
     * Execute some code and update the specified event rate metric.  This invocation represents a single event
     *
     * @param c
     *     A Consumer to execute.  aka a closure with one argument and no returned value
     * @param arg
     *     The argument to the function
     * @param meterName
     *     The meter to update
     */
    default <T> void metered(@NotNull Consumer<T> c, T arg, @NotNull String meterName) {
        metered(c, arg, meterName, 1L);
    }

    /**
     * Execute some code and update the specified event rate metric.
     *
     * @param bc
     *     A BiConsumer to execute.  aka a closure with two arguments and no returned value
     * @param arg1
     *     The first argument
     * @param arg2
     *     the second argument
     * @param meterName
     *     The meter to update
     * @param events
     *     the number of events this invocation represents
     */
    <T, U> void metered(@NotNull BiConsumer<T, U> bc, T arg1, U arg2, @NotNull String meterName, long events);

    /**
     * Execute some code and update the specified event rate metric.  This invocation represents a single event
     *
     * @param bc
     *     A BiConsumer to execute.  aka a closure with two arguments and no returned value
     * @param arg1
     *     The first argument
     * @param arg2
     *     the second argument
     * @param meterName
     *     The meter to update
     */
    default <T, U> void metered(@NotNull BiConsumer<T, U> bc, T arg1, U arg2, @NotNull String meterName) {
        metered(bc, arg1, arg2, meterName, 1L);
    }

    /**
     * Execute some code and update the specified event rate metric.
     *
     * @param bf
     *     A BiFunction to execute.  aka a closure with two arguments and one returned value
     * @param arg1
     *     The first argument
     * @param arg2
     *     The second argument
     * @param meterName
     *     The meter to update
     * @param events
     *     the number of events this invocation represents
     */
    <T, U, R> R metered(@NotNull BiFunction<T, U, R> bf, T arg1, U arg2, @NotNull String meterName, long events);

    /**
     * Execute some code and update the specified event rate metric.  This invocation represents a single event
     *
     * @param bf
     *     A BiFunction to execute.  aka a closure with two arguments and one returned value
     * @param arg1
     *     The first argument
     * @param arg2
     *     The second argument
     * @param meterName
     *     The meter to update
     */
    default <T, U, R> R metered(@NotNull BiFunction<T, U, R> bf, T arg1, U arg2, @NotNull String meterName) {
        return metered(bf, arg1, arg2, meterName, 1L);
    }

    /**
     * Execute some code and update the specified counter.  This invocation increments the counter by 1
     *
     * @param r
     *     A Runnable or closure with no args and no return values
     * @param counterName
     *     The name of the counter to update
     */
    default void counted(@NotNull Runnable r, @NotNull String counterName) {
        counted(r, counterName, 1L);
    }

    /**
     * Execute some code and update the specified counter.
     *
     * @param r
     *     A Runnable or closure with no args and no return values
     * @param counterName
     *     The name of the counter to update
     * @param delta
     *     The amount by which to change the counter
     */
    void counted(@NotNull Runnable r, @NotNull String counterName, long delta);

    /**
     * Execute some code and upate the specified counter
     *
     * @param c
     *     A Callable to execute
     * @param counterName
     *     The name of the counter to update
     * @param delta
     *     The amount by which to change the counter
     *
     * @return The result of processing
     */
    <V> V counted(@NotNull Callable<V> c, @NotNull String counterName, long delta) throws Exception;

    /**
     * Execute some code and upate the specified counter.  This invocation increments the counter by 1
     *
     * @param c
     *     A Callable to execute
     * @param meterName
     *     The name of the meter to update
     *
     * @return The result of processing
     */
    default <V> V counted(@NotNull Callable<V> c, @NotNull String meterName) throws Exception {
        return counted(c, meterName, 1L);
    }

    /**
     * Execute some code and update the specified counter.
     *
     * @param f
     *     A Function to execute.  aka a closure with one argument and a returned value
     * @param arg
     *     The argument to the function
     * @param counterName
     *     The name of the counter to update
     * @param delta
     *     the amount by which to udpate the counter
     *
     * @return The result of processing
     */
    <T, R> R counted(@NotNull Function<T, R> f, T arg, @NotNull String counterName, long delta);

    /**
     * Execute some code and update the specified counter.  This invocation increments the counter by 1
     *
     * @param f
     *     A Function to execute.  aka a closure with one argument and a returned value
     * @param arg
     *     The argument to the function
     * @param counterName
     *     The name of the counter to update
     *
     * @return The result of processing
     */
    default <T, R> R counted(@NotNull Function<T, R> f, T arg, @NotNull String counterName) {
        return counted(f, arg, counterName, 1L);
    }

    /**
     * Execute some code and update the specified counter.
     *
     * @param c
     *     A Consumer to execute.  aka a closure with one argument and no returned value
     * @param arg
     *     The argument to the function
     * @param counterName
     *     The name of the counter to update
     * @param delta
     *     the amount by which to udpate the counter
     */
    <T> void counted(@NotNull Consumer<T> c, T arg, @NotNull String counterName, long delta);

    /**
     * Execute some code and update the specified counter.  This invocation increments the counter by 1
     *
     * @param c
     *     A Consumer to execute.  aka a closure with one argument and no returned value
     * @param arg
     *     The argument to the function
     * @param counterName
     *     The name of the counter to update
     */
    default <T> void counted(@NotNull Consumer<T> c, T arg, @NotNull String counterName) {
        counted(c, arg, counterName, 1L);
    }

    /**
     * Execute some code and update the specified counter.
     *
     * @param bc
     *     A BiConsumer to execute.  aka a closure with two arguments and no returned value
     * @param arg1
     *     The first argument
     * @param arg2
     *     The second argument
     * @param counterName
     *     The name of the counter to update
     * @param delta
     *     the amount by which to udpate the counter
     */
    <T, U> void counted(@NotNull BiConsumer<T, U> bc, T arg1, U arg2, @NotNull String counterName, long delta);

    /**
     * Execute some code and update the specified counter.  The invocation increments the counter by 1
     *
     * @param bc
     *     A BiConsumer to execute.  aka a closure with two arguments and no returned value
     * @param arg1
     *     The first argument
     * @param arg2
     *     The second argument
     * @param counterName
     *     The name of the counter to update
     */
    default <T, U> void counted(@NotNull BiConsumer<T, U> bc, T arg1, U arg2, @NotNull String counterName) {
        counted(bc, arg1, arg2, counterName, 1L);
    }

    /**
     * Execute some code and update the specified counter.
     *
     * @param bf
     *     A BiFunction to execute.  aka a closure with two arguments and one returned value
     * @param arg1
     *     The first argument
     * @param arg2
     *     The second argument
     * @param counterName
     *     The name of the counter to update
     * @param delta
     *     the amount by which to udpate the counter
     *
     * @return The result of processing
     */
    <T, U, R> R counted(@NotNull BiFunction<T, U, R> bf, T arg1, U arg2, @NotNull String counterName, long delta);

    /**
     * Execute some code and update the specified counter. This invocation increments the counter by 1
     *
     * @param bf
     *     A BiFunction to execute.  aka a closure with two arguments and one returned value
     * @param arg1
     *     The first argument
     * @param arg2
     *     The second argument
     * @param counterName
     *     The name of the counter to update
     *
     * @return The result of processing
     */
    default <T, U, R> R counted(@NotNull BiFunction<T, U, R> bf, T arg1, U arg2, @NotNull String counterName) {
        return counted(bf, arg1, arg2, counterName, 1L);
    }

}
