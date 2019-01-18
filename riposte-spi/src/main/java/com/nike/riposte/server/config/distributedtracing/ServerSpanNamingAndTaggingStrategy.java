package com.nike.riposte.server.config.distributedtracing;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import org.jetbrains.annotations.NotNull;

/**
 * An abstract extension of {@link SpanNamingAndTaggingStrategy} that controls how Riposte handles span names and
 * auto-tagging/annotations for the server overall request spans that cover incoming requests into Riposte. Concrete
 * implementations fill in the logic for their tracing impls.
 *
 * <p>This class adds endpoint start and endpoint finish annotation options that will be used when the endpoint hit
 * is a {@link com.nike.riposte.server.http.NonblockingEndpoint} (e.g. {@code
 * com.nike.riposte.server.http.StandardEndpoint} from the riposte-core library). Otherwise this class doesn't do
 * anything beyond the base {@link SpanNamingAndTaggingStrategy}.
 *
 * <p>See {@code com.nike.riposte.server.config.distributedtracing.DefaultRiposteServerSpanNamingAndTaggingStrategy} in
 * the {@code riposte-core} library for a concrete extension of this class that is setup for Riposte's current
 * Wingtips-only environment (see the description of the {@code <S>} param below for an explanation), and takes
 * advantage of the pre-built Wingtips tagging and naming strategies.
 *
 * @param <S> The type of Span this {@link ServerSpanNamingAndTaggingStrategy}
 * will handle. Riposte is currently tied to Wingtips, so this type must be {@code com.nike.wingtips.Span} for now
 * (if you try to do something else, Riposte will throw an exception on startup). It is genericized into a type param
 * to avoid tying the {@code riposte-spi} library with the Wingtips dependency, and in anticipation of a future
 * version of Riposte that can support any underlying distributed tracing system.
 *
 * @author Nic Munroe
 */
public abstract class ServerSpanNamingAndTaggingStrategy<S>
    extends SpanNamingAndTaggingStrategy<RequestInfo<?>, ResponseInfo<?>, S> {

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the overall request span when it
     * starts executing a Riposte {@code com.nike.riposte.server.http.StandardEndpoint}, or false if you want to
     * omit that annotation. {@link #endpointStartAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    public boolean shouldAddEndpointStartAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte starts executing a
     * {@code com.nike.riposte.server.http.StandardEndpoint}. This should never return null or blank string - if you
     * want to disable this annotation then override {@link #shouldAddEndpointStartAnnotation()} to return false.
     * <p>Defaults to "endpoint.start".
     */
    public @NotNull String endpointStartAnnotationName() {
        return "endpoint.start";
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the overall request span when it
     * finishes executing a Riposte {@code com.nike.riposte.server.http.StandardEndpoint}, or false if you want to
     * omit that annotation. {@link #endpointFinishAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    public boolean shouldAddEndpointFinishAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte finishes executing a
     * {@code com.nike.riposte.server.http.StandardEndpoint}. This should never return null or blank string - if you
     * want to disable this annotation then override {@link #shouldAddEndpointFinishAnnotation()} to return false.
     * <p>Defaults to "endpoint.finish".
     */
    public @NotNull String endpointFinishAnnotationName() {
        return "endpoint.finish";
    }
}
