package com.nike.riposte.server.metrics;

/**
 * Server metric events.
 */
public enum ServerMetricsEvent {
    REQUEST_RECEIVED, RESPONSE_SENT,
    // TODO: This should be removed (see todos in ChannelPipelineFinalizerHandler)
    RESPONSE_WRITE_FAILED
}
