package com.nike.riposte.metrics;

import com.nike.riposte.server.metrics.ServerMetricsEvent;

/**
 * Interface for handling {@link ServerMetricsEvent}s. Concrete implementations should track and report on metrics
 * around general server statistics and health (e.g. counters for inflight, processed, and failed requests, histograms
 * for request and response sizes, etc), as well as per-endpoint metrics.
 *
 * <p>Inspired by metrics handling in <a href="https://github.com/ReactiveX/RxNetty">RxNetty</a> and
 * <a href="https://github.com/dropwizard/metrics/blob/master/metrics-jetty9/src/main/java/io/dropwizard/metrics/jetty9/InstrumentedHandler.java">
 *  Dropwizard metrics Jetty instrumentation
 * </a>
 */
public interface MetricsListener {

    /**
     * Handle the given event.
     *
     * @param event The event to handle.
     * @param value This should be a {@code HttpProcessingState} object, but may be null depending what happened during
     * the request.
     */
    void onEvent(ServerMetricsEvent event, Object value);
}
