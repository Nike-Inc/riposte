package com.nike.riposte.server.config.distributedtracing;

import org.jetbrains.annotations.NotNull;

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
}
