package com.nike.riposte.server.config.distributedtracing;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An interface that controls how Riposte handles span names and auto-tagging/annotations for the overall request
 * spans that cover incoming requests into Riposte.
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
public interface ServerSpanNamingAndTaggingStrategy<S> {

    // TODO: Should we turn this into an abstract class with final methods for the public interface that surround the
    //       actual work with try/catch, and abstract protected methods for impls to actually do the work?

    /**
     * Determines and returns the initial span name that should be used for incoming Riposte requests. Note that
     * when this method is called, {@link RequestInfo#getPathTemplate()} is probably not populated (because we
     * don't know what endpoint the request is for yet). So if you're following Zipkin conventions, the returned
     * name will probably just be {@link RequestInfo#getMethod()}. You'll have an opportunity to set a more
     * useful span name in {@link #handleResponseTaggingAndFinalSpanName(Object, RequestInfo, ResponseInfo, Throwable)},
     * which will be called after {@link RequestInfo#getPathTemplate()} is populated.
     *
     * @param request The incoming request - should never be null.
     * @return The initial span name that should be used for incoming Riposte requests, or null if this strategy can't
     * come up with a name. Riposte will use a fallback name if this returns null.
     */
    @Nullable String getInitialSpanName(@NotNull RequestInfo<?> request);

    /**
     * Changes the name on the given span to the given new name. This is needed because you want the span name to be
     * as informative as possible while keeping the cardinality of the name low for visualization and analytics systems
     * that expect low-cardinality span names, but you often don't have the info you need for a good span name at
     * the beginning of the request. Since some of the info you need for a good low-cardinality span name isn't
     * available until after an endpoint has been hit and/or response generated (like URI path template and HTTP
     * response code), a serviceable name is chosen to begin with (see {@link #getInitialSpanName(RequestInfo)}),
     * and this method is available for {@link #handleResponseTaggingAndFinalSpanName(Object, RequestInfo,
     * ResponseInfo, Throwable)} to use to set the final span name once the necessary info is available.
     *
     * @param span The span that needs its name changed - should never be null.
     * @param newName The new name to set on the span. Should never be null or blank - implementations are encouraged
     * to do nothing if passed a null or blank span name.
     */
    void changeSpanName(@NotNull S span, @NotNull String newName);

    /**
     * Auto-tags the given span with relevant data from the given request. If you're following Zipkin conventions this
     * may include full HTTP URL, HTTP path, HTTP method, etc. Note that {@link RequestInfo#getPathTemplate()} will
     * not be populated yet when this method is called, so you won't be able to include path template as a tag - you'll
     * need to do that in {@link #handleResponseTaggingAndFinalSpanName(Object, RequestInfo, ResponseInfo, Throwable)}
     * instead.
     *
     * @param span The span to tag - should never be null.
     * @param request The incoming request - should never be null.
     */
    void handleRequestTagging(@NotNull S span, @NotNull RequestInfo<?> request);

    /**
     * Auto-tags the given span name with relevant data from the given response (e.g. HTTP response status code) and
     * request. The request is included because at this point it will be populated with some data
     * (e.g. {@link RequestInfo#getPathTemplate()}) that wasn't available when {@link
     * #handleRequestTagging(Object, RequestInfo)} was called, so this is your opportunity to add those tags.
     * For similar reasons, this method is your chance to set the final span name now that you have things like
     * response status code and path template.
     *
     * <p>If you're following Zipkin conventions this final span name usually looks like a combination of {@link
     * RequestInfo#getMethod()} and {@link RequestInfo#getPathTemplate()}, unless the HTTP response code is 404 or 3xx
     * in which case you need to choose a static low-cardinality name. See the Wingtips
     * <a href="https://github.com/Nike-Inc/wingtips/blob/751b687320623b1b258cdcd9594df49180375385/wingtips-core/src/main/java/com/nike/wingtips/http/HttpRequestTracingUtils.java#L265-L325">
     * HttpRequestTracingUtils.generateSafeSpanName(String, String, Integer)</a> method javadocs for an explanation
     * of the subtleties of good low-cardinality span names.
     *
     * @param span The span to tag - should never be null.
     * @param request The request that was processed - may be null (depending on how the request flowed through the
     * system and what errors occurred)!
     * @param response The response that will be returned to the user - may be null (depending on how the request
     * flowed through the system and what errors occurred)!
     * @param error The exception associated with the given response, or null if no error was triggered while
     * processing the request. May be either an exception thrown by the endpoint processing the request, or by the
     * Riposte framework itself if the request never made it to an endpoint (e.g. invalid request path that resulted
     * in a 404, or a dropped connection, etc).
     */
    void handleResponseTaggingAndFinalSpanName(
        @NotNull S span,
        @Nullable RequestInfo<?> request,
        @Nullable ResponseInfo<?> response,
        @Nullable Throwable error
    );

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the overall request span when it
     * receives the first bytes of the request, or false if you want to omit that annotation.
     * {@link #wireReceiveStartAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    default boolean shouldAddWireReceiveStartAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte saw the first bytes of
     * the request. This should never return null or blank string - if you want to disable this annotation then
     * override {@link #shouldAddWireReceiveStartAnnotation()} to return false.
     * <p>Defaults to "wr.start".
     */
    default @NotNull String wireReceiveStartAnnotationName() {
        return "wr.start";
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the overall request span when it
     * receives the last bytes of the request, or false if you want to omit that annotation.
     * {@link #wireReceiveFinishAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    default boolean shouldAddWireReceiveFinishAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte saw the last bytes of
     * the request. This should never return null or blank string - if you want to disable this annotation then
     * override {@link #shouldAddWireReceiveFinishAnnotation()} to return false.
     * <p>Defaults to "wr.finish".
     */
    default @NotNull String wireReceiveFinishAnnotationName() {
        return "wr.finish";
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the overall request span when it
     * sends the first bytes of the response, or false if you want to omit that annotation.
     * {@link #wireSendStartAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    default boolean shouldAddWireSendStartAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte sent the first bytes of
     * the response. This should never return null or blank string - if you want to disable this annotation then
     * override {@link #shouldAddWireSendStartAnnotation()} to return false.
     * <p>Defaults to "ws.start".
     */
    default @NotNull String wireSendStartAnnotationName() {
        return "ws.start";
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the overall request span when it
     * sends the last bytes of the response, or false if you want to omit that annotation.
     * {@link #wireSendFinishAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    default boolean shouldAddWireSendFinishAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte sent the last bytes of
     * the response. This should never return null or blank string - if you want to disable this annotation then
     * override {@link #shouldAddWireSendFinishAnnotation()} to return false.
     * <p>Defaults to "ws.finish".
     */
    default @NotNull String wireSendFinishAnnotationName() {
        return "ws.finish";
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the overall request span when it
     * starts executing a Riposte {@code com.nike.riposte.server.http.StandardEndpoint}, or false if you want to
     * omit that annotation. {@link #endpointStartAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    default boolean shouldAddEndpointStartAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte starts executing a
     * {@code com.nike.riposte.server.http.StandardEndpoint}. This should never return null or blank string - if you
     * want to disable this annotation then override {@link #shouldAddEndpointStartAnnotation()} to return false.
     * <p>Defaults to "endpoint.start".
     */
    default @NotNull String endpointStartAnnotationName() {
        return "endpoint.start";
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the overall request span when it
     * finishes executing a Riposte {@code com.nike.riposte.server.http.StandardEndpoint}, or false if you want to
     * omit that annotation. {@link #endpointFinishAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    default boolean shouldAddEndpointFinishAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte finishes executing a
     * {@code com.nike.riposte.server.http.StandardEndpoint}. This should never return null or blank string - if you
     * want to disable this annotation then override {@link #shouldAddEndpointFinishAnnotation()} to return false.
     * <p>Defaults to "endpoint.finish".
     */
    default @NotNull String endpointFinishAnnotationName() {
        return "endpoint.finish";
    }

    /**
     * @param responseInfo The {@link ResponseInfo} that will be sent to the caller - should never be null.
     * @param error The exception that occurred that caused the given response to be sent - should never be null.
     * @return true if Riposte should add an automatic timestamped annotation to the overall request span when it
     * generates a response for an exception, or false if you want to omit that annotation.
     * {@link #errorAnnotationName(ResponseInfo, Throwable)} is used as the annotation name.
     * <p>Defaults to true.
     */
    @SuppressWarnings("unused")
    default boolean shouldAddErrorAnnotationForCaughtException(
        @NotNull ResponseInfo<?> responseInfo,
        @NotNull Throwable error
    ) {
        return true;
    }

    /**
     * @param responseInfo The {@link ResponseInfo} that will be sent to the caller - should never be null.
     * @param error The exception that occurred that caused the given response to be sent - should never be null.
     * @return The name that should be used for the annotation that represents when Riposte generated the given
     * response as a result of the given exception. This should never return null or blank string - if you want to
     * disable this annotation then override {@link
     * #shouldAddErrorAnnotationForCaughtException(ResponseInfo, Throwable)}  to return false.
     * <p>Defaults to "error".
     */
    @SuppressWarnings("unused")
    default @NotNull String errorAnnotationName(
        @NotNull ResponseInfo<?> responseInfo,
        @NotNull Throwable error
    ) {
        // TODO: Should we adjust 4xx errors to return "caller-error" or something other than "error"?
        //      Probably not, but worth thinking about, and probably worth discussion with Zipkin folks for standard
        //      practice details.
        return "error";
    }
}
