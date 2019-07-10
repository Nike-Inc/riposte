package com.nike.riposte.server.config.distributedtracing;

import com.nike.riposte.server.http.RequestInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * An abstract extension of {@link SpanNamingAndTaggingStrategy} that controls how Riposte handles span names and
 * auto-tagging/annotations for the {@code com.nike.riposte.server.http.ProxyRouterEndpoint} request spans that cover 
 * the outbound client HTTP call to the proxy target. Concrete implementations fill in the logic for their tracing
 * impls.
 *
 * <p>This class adds connection start and connection finish annotation options that will be used when the endpoint hit
 * is a {@code com.nike.riposte.server.http.ProxyRouterEndpoint} (from the riposte-core library). Otherwise this class
 * doesn't do anything beyond the base {@link SpanNamingAndTaggingStrategy}.
 *
 * <p>NOTE: You may want to adjust the span name based on something outside simply the Netty {@link HttpRequest}, which
 * is all you get when {@link #getInitialSpanName(HttpRequest)} is called. There is a hook to allow you to use other
 * criteria (such as the overall {@link RequestInfo} which may have useful attributes, for example):
 * {@link #getInitialSpanNameOverride(HttpRequest, RequestInfo, String, String)}. Note that method already has some
 * logic to give you a decent span name, and if you want to force a specific span name from your endpoint code you can
 * simply call the static {@link #setSpanNameOverrideForOutboundProxyRouterEndpointCall(String, RequestInfo)} method
 * instead, and it will be used for the proxy call's span name without you having to override the {@link
 * #doGetInitialSpanNameOverride(HttpRequest, RequestInfo, String, String)} method. But if you need more comprehensive
 * logic rather than a one-off value per endpoint, you can override that method to do whatever you want.
 *
 * <p>See {@code
 * com.nike.riposte.server.config.distributedtracing.DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy} in
 * the {@code riposte-core} library for a concrete extension of this class that is setup for Riposte's current
 * Wingtips-only environment (see the description of the {@code <S>} param below for an explanation), and takes
 * advantage of the pre-built Wingtips tagging and naming strategies.
 *
 * @param <S> The type of Span this {@link ProxyRouterSpanNamingAndTaggingStrategy}
 * will handle. Riposte is currently tied to Wingtips, so this type must be {@code com.nike.wingtips.Span} for now
 * (if you try to do something else, Riposte will throw an exception on startup). It is genericized into a type param
 * to avoid tying the {@code riposte-spi} library with the Wingtips dependency, and in anticipation of a future
 * version of Riposte that can support any underlying distributed tracing system.
 *
 * @author Nic Munroe
 */
public abstract class ProxyRouterSpanNamingAndTaggingStrategy<S>
    extends SpanNamingAndTaggingStrategy<HttpRequest, HttpResponse, S> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * The {@link RequestInfo#getRequestAttributes()} attribute used by the default implementation of
     * {@link #getInitialSpanNameOverride(HttpRequest, RequestInfo, String, String)} to see if you've set a specific
     * proxy call span name to use instead of the default override logic. You can call {@link
     * #setSpanNameOverrideForOutboundProxyRouterEndpointCall(String, RequestInfo)} as a shortcut for setting this.
     * Otherwise you'd do something like this in your endpoint code to set it:
     * <pre>
     *     requestInfo.addRequestAttribute(ProxyRouterSpanNamingAndTaggingStrategy.SPAN_NAME_FOR_OUTBOUND_PROXY_CALL_REQ_ATTR_KEY, someDesiredSpanName);
     * </pre>
     */
    public static final String SPAN_NAME_FOR_OUTBOUND_PROXY_CALL_REQ_ATTR_KEY = "SpanNameForOutboundProxyCall";

    /**
     * Call this to force a specific desired span name for the proxy outbound call's span.
     *
     * @param spanName The span name you want applied to the proxy outbound call's span.
     * @param request The {@link RequestInfo} your endpoint received.
     */
    public static void setSpanNameOverrideForOutboundProxyRouterEndpointCall(String spanName, RequestInfo<?> request) {
        request.addRequestAttribute(SPAN_NAME_FOR_OUTBOUND_PROXY_CALL_REQ_ATTR_KEY, spanName);
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the proxy outbound client request span
     * when it asks for a connection from the pool, or false if you want to omit that annotation.
     * {@link #connStartAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    public boolean shouldAddConnStartAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte starts grabbing a
     * connection from the pool to make the proxy outbound client request. This should never return null or blank
     * string - if you want to disable this annotation then override {@link #shouldAddConnStartAnnotation()} to return
     * false.
     * <p>Defaults to "conn.start".
     */
    public @NotNull String connStartAnnotationName() {
        return "conn.start";
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the proxy outbound client request span
     * when it asks for a connection from the pool, or false if you want to omit that annotation.
     * {@link #connFinishAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    public boolean shouldAddConnFinishAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte finishes grabbing a
     * connection from the pool to make the proxy outbound client request. This should never return null or blank
     * string - if you want to disable this annotation then override {@link #shouldAddConnFinishAnnotation()} to return
     * false.
     * <p>Defaults to "conn.finish".
     */
    public @NotNull String connFinishAnnotationName() {
        return "conn.finish";
    }

    /**
     * This method is a hook to allow overriding of the initial span name generated for the outbound call of a
     * {@code ProxyRouterEndpoint}, using criteria not available to the {@link #getInitialSpanName(HttpRequest)} method
     * (which only has access to the Netty {@link HttpRequest}). By default, this method does the following, in order:
     *
     * <ol>
     *     <li>
     *         Checks the given {@code overallRequest} to see if it contains a non-null {@link
     *         #SPAN_NAME_FOR_OUTBOUND_PROXY_CALL_REQ_ATTR_KEY} request attribute value. If so, that is returned as
     *         the desired initial span name override. You can use {@link
     *         #setSpanNameOverrideForOutboundProxyRouterEndpointCall(String, RequestInfo)} as a convenience method
     *         for setting this attribute in your endpoint code.
     *     </li>
     *     <li>
     *         Checks the given {@code initialSpanName} to see if it's a basic default name that only consists of
     *         the HTTP method (with or without the {@code "proxy-"} prefix. If it's just the basic default name,
     *         then this method will return the given {@code overallRequestSpanName}, prefixed with {@code "proxy-"}.
     *         i.e. If {@code overallRequestSpanName} was {@code "GET /foo/bar/:baz"}, then this would return
     *         {@code "proxy-GET /foo/bar/:baz"}.
     *     </li>
     *     <li>
     *         If none of the other cases match, then this method will return null to indicate that the normal initial
     *         span name should be used.
     *     </li>
     * </ol>
     *
     * Override {@link #doGetInitialSpanNameOverride(HttpRequest, RequestInfo, String, String)} if you have other
     * logic you'd rather use to determine the initial span name.
     *
     * <p>NOTE: This method is final and delegates to {@link
     * #doGetInitialSpanNameOverride(HttpRequest, RequestInfo, String, String)}.
     * That delegate method call is surrounded with a try/catch so that this method will never throw an exception.
     * If an exception occurs then the error will be logged and null will be returned. Since this method is final, if
     * you want to override the behavior of this method then you should override {@link
     * #doGetInitialSpanNameOverride(HttpRequest, RequestInfo, String, String)}.
     *
     * @param downstreamRequest The Netty {@link HttpRequest} for the outbound proxy call.
     * @param overallRequest The Riposte {@link RequestInfo} for the overall request.
     * @param initialSpanName The initial span name for the outbound proxy call generated by {@link
     * #getInitialSpanName(HttpRequest)}.
     * @param overallRequestSpanName The span name of the overall request span surrounding the given {@code
     * overallRequest}.
     * @return The desired span name override that should be used for the outbound proxy call's span, or null if the
     * normal initial span name should be used.
     */
    public final @Nullable String getInitialSpanNameOverride(
        @NotNull HttpRequest downstreamRequest,
        @NotNull RequestInfo<?> overallRequest,
        @Nullable String initialSpanName,
        @Nullable String overallRequestSpanName
    ) {
        //noinspection ConstantConditions
        if (downstreamRequest == null || overallRequest == null) {
            return null;
        }

        try {
            return doGetInitialSpanNameOverride(
                downstreamRequest, overallRequest, initialSpanName, overallRequestSpanName
            );
        }
        catch (Throwable t) {
            // Impl methods should never throw an exception. If you're seeing this error pop up, the impl needs to
            //      be fixed.
            logger.error(
                "An unexpected error occurred while getting the initial span name override. The error will be "
                + "swallowed to avoid doing any damage and null will be returned, but your span name may not be what "
                + "you expect. This error should be fixed.",
                t
            );
            return null;
        }
    }

    /**
     * This method is a hook to allow overriding of the initial span name generated for the outbound call of a
     * {@code ProxyRouterEndpoint}, using criteria not available to the {@link #getInitialSpanName(HttpRequest)} method
     * (which only has access to the Netty {@link HttpRequest}).
     *
     * <p>NOTE: This method does the actual work for the public-facing {@link
     * #getInitialSpanNameOverride(HttpRequest, RequestInfo, String, String)}. See that method's javadocs for a full
     * description of the default behavior of this method. Override this method if you have other logic you'd rather
     * use to determine the initial span name.
     *
     * @param downstreamRequest The Netty {@link HttpRequest} for the outbound proxy call.
     * @param overallRequest The Riposte {@link RequestInfo} for the overall request.
     * @param initialSpanName The initial span name for the outbound proxy call generated by {@link
     * #getInitialSpanName(HttpRequest)}.
     * @param overallRequestSpanName The span name of the overall request span surrounding the given {@code
     * overallRequest}.
     * @return The desired span name override that should be used for the outbound proxy call's span, or null if the
     * normal initial span name should be used.
     */
    protected @Nullable String doGetInitialSpanNameOverride(
        @NotNull HttpRequest downstreamRequest,
        @NotNull RequestInfo<?> overallRequest,
        @Nullable String initialSpanName,
        @Nullable String overallRequestSpanName
    ) {
        Object requestInfoAttrOverrideValue =
            overallRequest.getRequestAttributes().get(SPAN_NAME_FOR_OUTBOUND_PROXY_CALL_REQ_ATTR_KEY);
        if (requestInfoAttrOverrideValue != null) {
            return requestInfoAttrOverrideValue.toString();
        }

        String httpMethodStr = downstreamRequest.method().name();
        if (initialSpanName != null
            && (initialSpanName.equals("proxy-" + httpMethodStr) || initialSpanName.equals(httpMethodStr))
        ) {
            // The initialSpanName is a basic default one, with just the HTTP method. If the overallRequestSpanName
            //      exists then we can use its name as part of the override for the proxy call's span name.
            if (overallRequestSpanName != null) {
                return "proxy-" + overallRequestSpanName;
            }
        }

        // No span name override is allowed for this case, so return null.
        return null;
    }
}
