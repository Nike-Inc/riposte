package com.nike.riposte.metrics.codahale;

import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import com.codahale.metrics.MetricRegistry;

/**
 * Interface describing an object that knows how to deal with endpoint-related metrics. Depending on what metrics
 * reporter(s) you're using and where those metrics end up, you may want to handle endpoint metrics differently - for
 * example, some systems like Graphite might want separate specially-named metrics for everything, while others
 * like SignalFx might want a more compact set of metrics with various dimensions carved out via metadata from their
 * custom reporters. This interface lets you choose how endpoint metrics are handled. Note that a
 * {@link com.nike.riposte.metrics.codahale.impl.EndpointMetricsHandlerDefaultImpl} will be used by default if you
 * don't specify a custom one when setting up your {@link CodahaleMetricsListener}.
 *
 * @author Nic Munroe
 */
public interface EndpointMetricsHandler {

    /**
     * Eagerly setup or initialize endpoint-related metrics. You can inspect what endpoints are available via
     * {@link ServerConfig#appEndpoints()}, and use the provided {@link MetricRegistry} to register/setup metrics.
     * Note that some implementations of this interface may do nothing here.
     *
     * @param config The {@link ServerConfig} that has the endpoints for the application.
     * @param metricRegistry The {@link MetricRegistry} to use for creating/registering metrics.
     */
    void setupEndpointsMetrics(ServerConfig config, MetricRegistry metricRegistry);

    /**
     * Handle a request that has been processed. The implementation of this method is charged with finding and properly
     * updating any endpoint-related metrics. Note that at the point this method is called, the response has been
     * fully sent to the caller.
     *
     * @param requestInfo The request that was processed.
     * @param responseInfo The response that was processed.
     * @param httpState The {@link HttpProcessingState} associated with the request/response. This contains some useful
     * info - in particular {@link HttpProcessingState#getRequestStartTime()} (so you can calculate request latency).
     * @param responseHttpStatusCode The HTTP response status code that was sent to the caller.
     * @param responseHttpStatusCodeXXValue Which category the response status code falls into,
     * i.e. 1xx, 2xx, 3xx, 4xx, 5xx, etc. This is just a convenience for {@code (int)(responseHttpStatusCode / 100)}.
     * @param requestElapsedTimeMillis How long the request took in milliseconds.
     */
    void handleRequest(RequestInfo<?> requestInfo,
                       ResponseInfo<?> responseInfo,
                       HttpProcessingState httpState,
                       int responseHttpStatusCode,
                       int responseHttpStatusCodeXXValue,
                       long requestElapsedTimeMillis);
}
