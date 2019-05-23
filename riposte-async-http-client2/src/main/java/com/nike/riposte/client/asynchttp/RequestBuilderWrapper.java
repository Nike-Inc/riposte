package com.nike.riposte.client.asynchttp;

import com.nike.fastbreak.CircuitBreaker;

import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;

import java.util.Optional;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

/**
 * Wrapper around the actual {@link #requestBuilder} that also keeps track of the URL and HTTP method used to create the
 * builder (since there are no getters to inspect the builder). Generate one of these by calling one of the
 * request-starter methods in {@link AsyncHttpClientHelper}, e.g. {@link
 * AsyncHttpClientHelper#getRequestBuilder(String, HttpMethod)}. From there you'll want to set any headers, add request
 * body, or adjust anything else about the request by interacting with {@link #requestBuilder} before passing it into
 * one of the execute methods (e.g. {@link
 * AsyncHttpClientHelper#executeAsyncHttpRequest(RequestBuilderWrapper, AsyncResponseHandler, ChannelHandlerContext)}).
 *
 * @author Nic Munroe
 */
@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType"})
public class RequestBuilderWrapper {

    String url;
    String httpMethod;
    public final BoundRequestBuilder requestBuilder;
    /**
     * An Optional containing a custom circuit breaker if a custom one should be used, or empty if the request sender
     * should use a default circuit breaker. If you don't want *any* circuit breaker to be used, set {@link
     * #disableCircuitBreaker} to true. The default circuit breaker will be based on the host value of the {@link #url}
     * (i.e. all calls to the same host will use the same circuit breaker). If you need something more (or less) fine
     * grained than that then you'll need to provide a custom circuit breaker.
     */
    Optional<CircuitBreaker<Response>> customCircuitBreaker;
    /**
     * Set this to true if you don't want *any* circuit breaker to be used - if this is false then {@link
     * #customCircuitBreaker} will be used to determine which circuit breaker to use (custom vs. default).
     */
    boolean disableCircuitBreaker;

    private ChannelHandlerContext ctx;

    /**
     * Intentionally package-scoped. Instances of this class are generated and returned by calling one of the
     * request-starter methods in {@link AsyncHttpClientHelper}, e.g. {@link
     * AsyncHttpClientHelper#getRequestBuilder(String, HttpMethod)}.
     */
    RequestBuilderWrapper(
            String url, String httpMethod, BoundRequestBuilder requestBuilder,
            Optional<CircuitBreaker<Response>> customCircuitBreaker, boolean disableCircuitBreaker
    ) {
        this.url = url;
        this.httpMethod = httpMethod;
        this.requestBuilder = requestBuilder;
        this.customCircuitBreaker = customCircuitBreaker;
        this.disableCircuitBreaker = disableCircuitBreaker;
    }

    ChannelHandlerContext getCtx() {
        return ctx;
    }

    void setCtx(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    public void setCustomCircuitBreaker(Optional<CircuitBreaker<Response>> customCircuitBreaker) {
        this.customCircuitBreaker = customCircuitBreaker;
    }

    public Optional<CircuitBreaker<Response>> getCustomCircuitBreaker() {
        return customCircuitBreaker;
    }

    public void setDisableCircuitBreaker(boolean disableCircuitBreaker) {
        this.disableCircuitBreaker = disableCircuitBreaker;
    }

    public boolean isDisableCircuitBreaker() {
        return disableCircuitBreaker;
    }

    /**
     * <p>Use this method to update the url stored inside this {@link RequestBuilderWrapper}
     * and the wrapped {@link BoundRequestBuilder}
     *
     * <p>Setting the url only on the wrapped {@link BoundRequestBuilder} will impact logging
     * and circuit breakers potentially. Use this method to keep the two in sync.
     */
    public void setUrl(String url) {
        this.url = url;
        requestBuilder.setUrl(url);
    }

    public String getUrl() {
        return url;
    }

    /**
     * <p>Use this method to update the httpMethod stored inside this {@link RequestBuilderWrapper}
     * and the wrapped {@link BoundRequestBuilder}
     *
     * <p>Setting the httpMethod only on the wrapped {@link BoundRequestBuilder} will impact logging
     * and circuit breakers potentially. Use this method to keep the two in sync.
     */
    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
        requestBuilder.setMethod(httpMethod);
    }

    public String getHttpMethod() {
        return httpMethod;
    }

}
