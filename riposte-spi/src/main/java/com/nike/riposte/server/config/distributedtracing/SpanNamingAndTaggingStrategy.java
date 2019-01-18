package com.nike.riposte.server.config.distributedtracing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An abstract class that controls how Riposte handles span names and auto-tagging/annotations for the distributed
 * tracing spans that it creates (i.e. overall-request spans, proxy/router child spans, etc). Concrete implementations
 * fill in the logic for their tracing impls.
 *
 * <p>Callers interface with the following public methods, which are final and surrounded with try/catch to avoid
 * having implementation exceptions bubble out (errors are logged, but do not propagate up to callers):
 * <ul>
 *     <li>{@link #getInitialSpanName(Object)}</li>
 *     <li>{@link #changeSpanName(Object, String)}</li>
 *     <li>{@link #handleRequestTagging(Object, Object)}</li>
 *     <li>{@link #handleResponseTaggingAndFinalSpanName(Object, Object, Object, Throwable)}</li>
 * </ul>
 *
 * <p>Besides the try/catch, those caller-facing methods don't do anything themselves other than delegate to protected
 * (overrideable) methods that are intended for concrete implementations to flesh out.
 *
 * <p>This class also contains methods for controlling whether some annotations are automatically added and what their
 * names are, e.g. {@link #shouldAddWireReceiveStartAnnotation()} and {@link #wireReceiveStartAnnotationName()} for
 * the "wire received start" annotation. These methods are concrete and provide reasonable defaults, but are
 * overrideable if you have custom needs.
 *
 * @param <REQ> The type of request this {@link SpanNamingAndTaggingStrategy} will handle.
 * @param <RES> The type of response this {@link SpanNamingAndTaggingStrategy} will handle.
 * @param <S> The type of Span this {@link SpanNamingAndTaggingStrategy}
 * will handle. Riposte is currently tied to Wingtips, so this type must be {@code com.nike.wingtips.Span} for now
 * (if you try to do something else, Riposte will throw an exception on startup). It is genericized into a type param
 * to avoid tying the {@code riposte-spi} library with the Wingtips dependency, and in anticipation of a future
 * version of Riposte that can support any underlying distributed tracing system.
 *
 * @author Nic Munroe
 */
public abstract class SpanNamingAndTaggingStrategy<REQ, RES, S> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * Determines and returns the initial span name that should be used for the span around the given request. Note
     * that depending on what this {@link SpanNamingAndTaggingStrategy} is being used for, the low-cardinality
     * "path template" (i.e. {@code /foo/bar/:id} rather than {@code /foo/bar/1234}) may not be populated yet
     * (e.g. because we don't know what endpoint the request is for at the time the span is created). So if you're
     * following Zipkin conventions, the returned name will probably just be the request's HTTP method. You'll have
     * an opportunity to set a more useful span name in {@link
     * #handleResponseTaggingAndFinalSpanName(Object, Object, Object, Throwable)}, which will be called after
     * the request is handled, and therefore more info should be available for the best span name possible.
     *
     * <p>This method is final and delegates to {@link #doGetInitialSpanName(Object)}.
     * That delegate method call is surrounded with a try/catch so that this method will never throw an exception.
     * If an exception occurs then the error will be logged and null will be returned. Since this method is final, if
     * you want to override the behavior of this method then you should override {@link #doGetInitialSpanName(Object)}.
     *
     * @param request The incoming request - should never be null.
     * @return The initial span name that should be used for the given request, or null if this strategy can't come
     * up with a name. Riposte will use a fallback name if this returns null.
     */
    public final @Nullable String getInitialSpanName(@NotNull REQ request) {
        //noinspection ConstantConditions
        if (request == null) {
            return null;
        }

        try {
            return doGetInitialSpanName(request);
        }
        catch (Throwable t) {
            // Impl methods should never throw an exception. If you're seeing this error pop up, the impl needs to
            //      be fixed.
            logger.error(
                "An unexpected error occurred while getting the initial span name. The error will be swallowed to "
                + "avoid doing any damage and null will be returned, but your span name may not be what you expect. "
                + "This error should be fixed.",
                t
            );
            return null;
        }
    }

    /**
     * Determines and returns the initial span name that should be used for the span around the given request. Note
     * that depending on what this {@link SpanNamingAndTaggingStrategy} is being used for, the low-cardinality
     * "path template" (i.e. {@code /foo/bar/:id} rather than {@code /foo/bar/1234}) may not be populated yet
     * (e.g. because we don't know what endpoint the request is for at the time the span is created). So if you're
     * following Zipkin conventions, the returned name will probably just be the request's HTTP method. You'll have
     * an opportunity to set a more useful span name in {@link
     * #handleResponseTaggingAndFinalSpanName(Object, Object, Object, Throwable)}, which will be called after
     * the request is handled, and therefore more info should be available for the best span name possible.
     *
     * <p>NOTE: This method does the actual work for the public-facing {@link #getInitialSpanName(Object)}.
     *
     * @param request The incoming request - should never be null.
     * @return The initial span name that should be used for the given request, or null if this strategy can't come
     * up with a name. Riposte will use a fallback name if this returns null.
     */
    protected abstract @Nullable String doGetInitialSpanName(@NotNull REQ request);

    /**
     * Changes the name on the given span to the given new name. This is needed because you want the span name to be
     * as informative as possible while keeping the cardinality of the name low for visualization and analytics systems
     * that expect low-cardinality span names, but you often don't have the info you need for a good span name at
     * the beginning of the request. Since some of the info you need for a good low-cardinality span name isn't
     * available until after an endpoint has been hit and/or response generated (like URI path template and HTTP
     * response code), a serviceable name is chosen to begin with (see {@link #getInitialSpanName(Object)}),
     * and this method is available for {@link #handleResponseTaggingAndFinalSpanName(Object, Object, Object,
     * Throwable)} to use to set the final span name once the necessary info is available.
     *
     * <p>This method is final and delegates to {@link #doChangeSpanName(Object, String)}.
     * That delegate method call is surrounded with a try/catch so that this method will never throw an exception.
     * If an exception occurs then the error will be logged but not propagated. Since this method is final, if
     * you want to override the behavior of this method then you should override {@link
     * #doChangeSpanName(Object, String)}.
     *
     * @param span The span that needs its name changed - should never be null.
     * @param newName The new name to set on the span. Should never be null or blank - implementations are encouraged
     * to do nothing if passed a null or blank span name.
     */
    public final void changeSpanName(@NotNull S span, @NotNull String newName) {
        //noinspection ConstantConditions
        if (span == null || newName == null) {
            return;
        }

        try {
            doChangeSpanName(span, newName);
        }
        catch (Throwable t) {
            // Impl methods should never throw an exception. If you're seeing this error pop up, the impl needs to
            //      be fixed.
            logger.error(
                "An unexpected error occurred while changing the span name. The error will be swallowed to "
                + "avoid doing any damage, but your span name may not be what you expect. This error should be fixed.",
                t
            );
        }
    }

    /**
     * Changes the name on the given span to the given new name. This is needed because you want the span name to be
     * as informative as possible while keeping the cardinality of the name low for visualization and analytics systems
     * that expect low-cardinality span names, but you often don't have the info you need for a good span name at
     * the beginning of the request. Since some of the info you need for a good low-cardinality span name isn't
     * available until after an endpoint has been hit and/or response generated (like URI path template and HTTP
     * response code), a serviceable name is chosen to begin with (see {@link #getInitialSpanName(Object)}),
     * and this method is available for {@link #handleResponseTaggingAndFinalSpanName(Object, Object, Object,
     * Throwable)} to use to set the final span name once the necessary info is available.
     *
     * <p>NOTE: This method does the actual work for the public-facing {@link #changeSpanName(Object, String)}.
     *
     * @param span The span that needs its name changed - should never be null.
     * @param newName The new name to set on the span. Should never be null or blank - implementations are encouraged
     * to do nothing if passed a null or blank span name.
     */
    protected abstract void doChangeSpanName(@NotNull S span, @NotNull String newName);

    /**
     * Auto-tags the given span with relevant data from the given request. If you're following Zipkin conventions this
     * may include full HTTP URL, HTTP path, HTTP method, etc. Note that the low-cardinality "path template" (i.e.
     * {@code /foo/bar/:id} rather than {@code /foo/bar/1234}) may not be populated yet when this method is called.
     * If so, then you won't be able to include path template as a tag - you'll have a second chance to do that in
     * {@link #handleResponseTaggingAndFinalSpanName(Object, Object, Object, Throwable)}.
     *
     * <p>This method is final and delegates to {@link #doHandleRequestTagging(Object, Object)}.
     * That delegate method call is surrounded with a try/catch so that this method will never throw an exception.
     * If an exception occurs then the error will be logged but not propagated. Since this method is final, if
     * you want to override the behavior of this method then you should override {@link
     * #doHandleRequestTagging(Object, Object)}.
     *
     * @param span The span to tag - should never be null.
     * @param request The incoming request - should never be null.
     */
    public final void handleRequestTagging(@NotNull S span, @NotNull REQ request) {
        //noinspection ConstantConditions
        if (span == null || request == null) {
            return;
        }

        try {
            doHandleRequestTagging(span, request);
        }
        catch (Throwable t) {
            // Impl methods should never throw an exception. If you're seeing this error pop up, the impl needs to
            //      be fixed.
            logger.error(
                "An unexpected error occurred while handling request tagging. The error will be swallowed to avoid "
                + "doing any damage, but your span may be missing some expected tags. This error should be fixed.",
                t
            );
        }
    }

    /**
     * Auto-tags the given span with relevant data from the given request. If you're following Zipkin conventions this
     * may include full HTTP URL, HTTP path, HTTP method, etc. Note that the low-cardinality "path template" (i.e.
     * {@code /foo/bar/:id} rather than {@code /foo/bar/1234}) may not be populated yet when this method is called.
     * If so, then you won't be able to include path template as a tag - you'll have a second chance to do that in
     * {@link #handleResponseTaggingAndFinalSpanName(Object, Object, Object, Throwable)}.
     *
     * <p>NOTE: This method does the actual work for the public-facing {@link #handleRequestTagging(Object, Object)}.
     *
     * @param span The span to tag - should never be null.
     * @param request The incoming request - should never be null.
     */
    protected abstract void doHandleRequestTagging(@NotNull S span, @NotNull REQ request);

    /**
     * Auto-tags the given span name with relevant data from the given response (e.g. HTTP response status code) and
     * request. The request is included because at this point it may be populated with some data that wasn't available
     * when {@link #handleRequestTagging(Object, Object)} was called (like the low-cardinality "path template"), so
     * this is your opportunity to add those tags. For similar reasons, this method is your chance to set the final
     * span name now that you have things like response status code and path template.
     *
     * <p>If you're following Zipkin conventions this final span name usually looks like a combination of request
     * HTTP method and the low-cardinality path template, unless the HTTP response code is 404 or 3xx in which case
     * you need to choose a static low-cardinality name. See the Wingtips
     * <a href="https://github.com/Nike-Inc/wingtips/blob/751b687320623b1b258cdcd9594df49180375385/wingtips-core/src/main/java/com/nike/wingtips/http/HttpRequestTracingUtils.java#L265-L325">
     * HttpRequestTracingUtils.generateSafeSpanName(String, String, Integer)</a> method javadocs for an explanation
     * of the subtleties of good low-cardinality span names.
     *
     * <p>This method is final and delegates to {@link #doHandleResponseTaggingAndFinalSpanName(Object, Object, Object,
     * Throwable)}. That delegate method call is surrounded with a try/catch so that this method will never throw an exception.
     * If an exception occurs then the error will be logged but not propagated. Since this method is final, if
     * you want to override the behavior of this method then you should override {@link
     * #doHandleResponseTaggingAndFinalSpanName(Object, Object, Object, Throwable)}.
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
    public final void handleResponseTaggingAndFinalSpanName(
        @NotNull S span,
        @Nullable REQ request,
        @Nullable RES response,
        @Nullable Throwable error
    ) {
        //noinspection ConstantConditions
        if (span == null) {
            return;
        }

        try {
            doHandleResponseTaggingAndFinalSpanName(span, request, response, error);
        }
        catch (Throwable t) {
            // Impl methods should never throw an exception. If you're seeing this error pop up, the impl needs to
            //      be fixed.
            logger.error(
                "An unexpected error occurred while handling response tagging and final span name. The error will be "
                + "swallowed to avoid doing any damage, but your span may be missing some expected tags and/or the "
                + "span name might not be what you expect. This error should be fixed.",
                t
            );
        }
    }

    /**
     * Auto-tags the given span name with relevant data from the given response (e.g. HTTP response status code) and
     * request. The request is included because at this point it may be populated with some data that wasn't available
     * when {@link #handleRequestTagging(Object, Object)} was called (like the low-cardinality "path template"), so
     * this is your opportunity to add those tags. For similar reasons, this method is your chance to set the final
     * span name now that you have things like response status code and path template.
     *
     * <p>If you're following Zipkin conventions this final span name usually looks like a combination of request
     * HTTP method and the low-cardinality path template, unless the HTTP response code is 404 or 3xx in which case
     * you need to choose a static low-cardinality name. See the Wingtips
     * <a href="https://github.com/Nike-Inc/wingtips/blob/751b687320623b1b258cdcd9594df49180375385/wingtips-core/src/main/java/com/nike/wingtips/http/HttpRequestTracingUtils.java#L265-L325">
     * HttpRequestTracingUtils.generateSafeSpanName(String, String, Integer)</a> method javadocs for an explanation
     * of the subtleties of good low-cardinality span names.
     *
     * <p>NOTE: This method does the actual work for the public-facing {@link
     * #handleResponseTaggingAndFinalSpanName(Object, Object, Object, Throwable)}.
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
    protected abstract void doHandleResponseTaggingAndFinalSpanName(
        @NotNull S span,
        @Nullable REQ request,
        @Nullable RES response,
        @Nullable Throwable error
    );

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the span when it
     * receives the first bytes of the request (for server spans) or response (for client spans), or false if you want
     * to omit that annotation. {@link #wireReceiveStartAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    public boolean shouldAddWireReceiveStartAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte saw the first bytes of
     * the request (for server spans) or response (for client spans). This should never return null or blank
     * string - if you want to disable this annotation then override {@link #shouldAddWireReceiveStartAnnotation()} to
     * return false.
     * <p>Defaults to "wr.start".
     */
    public @NotNull String wireReceiveStartAnnotationName() {
        return "wr.start";
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the span when it
     * receives the last bytes of the request (for server spans) or response (for client spans), or false if you want
     * to omit that annotation. {@link #wireReceiveFinishAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    public boolean shouldAddWireReceiveFinishAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte saw the last bytes of
     * the request (for server spans) or response (for client spans). This should never return null or blank
     * string - if you want to disable this annotation then override {@link #shouldAddWireReceiveFinishAnnotation()} to
     * return false.
     * <p>Defaults to "wr.finish".
     */
    public @NotNull String wireReceiveFinishAnnotationName() {
        return "wr.finish";
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the span when it
     * sends the first bytes of the response (for server spans) or request (for client spans), or false if you want to
     * omit that annotation. {@link #wireSendStartAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    public boolean shouldAddWireSendStartAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte sent the first bytes of
     * the response (for server spans) or request (for client spans). This should never return null or blank
     * string - if you want to disable this annotation then override {@link #shouldAddWireSendStartAnnotation()} to
     * return false.
     * <p>Defaults to "ws.start".
     */
    public @NotNull String wireSendStartAnnotationName() {
        return "ws.start";
    }

    /**
     * @return true if Riposte should add an automatic timestamped annotation to the span when it
     * sends the last bytes of the response (for server spans) or request (for client spans), or false if you want to
     * omit that annotation. {@link #wireSendFinishAnnotationName()} is used as the annotation name.
     * <p>Defaults to true.
     */
    public boolean shouldAddWireSendFinishAnnotation() {
        return true;
    }

    /**
     * @return The name that should be used for the annotation that represents when Riposte sent the last bytes of
     * the response (for server spans) or request (for client spans). This should never return null or blank
     * string - if you want to disable this annotation then override {@link #shouldAddWireSendFinishAnnotation()} to
     * return false.
     * <p>Defaults to "ws.finish".
     */
    public @NotNull String wireSendFinishAnnotationName() {
        return "ws.finish";
    }

    /**
     * @param response The response object - may be null, depending on the use case (i.e. server span vs. client span).
     * @param error The exception that occurred - should never be null.
     * @return true if Riposte should add an automatic timestamped annotation to the span when it handles the
     * exception, or false if you want to omit that annotation. {@link #errorAnnotationName(Object, Throwable)} is
     * used as the annotation name.
     * <p>Defaults to true.
     */
    @SuppressWarnings("unused")
    public boolean shouldAddErrorAnnotationForCaughtException(
        @Nullable RES response,
        @NotNull Throwable error
    ) {
        return true;
    }

    /**
     * @param response The response object - may be null, depending on the use case (i.e. server span vs. client span).
     * @param error The exception that occurred - should never be null.
     * @return The name that should be used for the annotation that represents when Riposte handled the given
     * exception. This should never return null or blank string - if you want to disable this annotation then
     * override {@link #shouldAddErrorAnnotationForCaughtException(Object, Throwable)} to return false.
     * <p>Defaults to "error".
     */
    @SuppressWarnings("unused")
    public @NotNull String errorAnnotationName(
        @Nullable RES response,
        @NotNull Throwable error
    ) {
        // TODO: Should we adjust 4xx errors to return "caller-error" or something other than "error"?
        //      Probably not, but worth thinking about, and probably worth discussion with Zipkin folks for standard
        //      practice details.
        return "error";
    }
}
