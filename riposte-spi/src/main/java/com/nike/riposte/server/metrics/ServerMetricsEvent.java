package com.nike.riposte.server.metrics;

/**
 * Server metric events inspired by HTTP RxNetty like https://github.com/ReactiveX/RxNetty/tree/0.x/rxnetty-servo DEEP
 * Http https://github.com/dropwizard/metrics/blob/master/metrics-jetty9/src/main/java/com/codahale/metrics/jetty9/InstrumentedHandler.java
 * also counters and timers/Histograms for the registered endpoints.
 */
public enum ServerMetricsEvent {
    REQUEST_RECEIVED, RESPONSE_SENT,
    // TODO: This should be removed
    RESPONSE_WRITE_FAILED
}
