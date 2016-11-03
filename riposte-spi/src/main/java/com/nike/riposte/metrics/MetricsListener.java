package com.nike.riposte.metrics;

import com.nike.riposte.server.metrics.ServerMetricsEvent;

/**
 * Interface that allows to listen for all types of events for the purpose of collecting metrics.
 */
public interface MetricsListener {

    void onEvent(ServerMetricsEvent event, Object value);
}
