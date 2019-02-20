package com.nike.riposte.server.http.filter;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.error.validation.RequestSecurityValidator;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.impl.ChunkedResponseInfo;
import com.nike.riposte.server.http.impl.FullResponseInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

import io.netty.channel.ChannelHandlerContext;

/**
 * Interface representing a request/response filter. These can be used similarly to servlet filters in that they give
 * you an opportunity to adjust (or even replace) the incoming request before a destination endpoint is determined and
 * called, and adjust (or replace) the outgoing response before it is sent to the caller. Typical use cases would be for
 * adding, removing, or modifying headers based on overall application rules so that each endpoint doesn't have to
 * reimplement the same logic.
 *
 * <p>The request filter side carries the useful possibility of "short circuiting" the request, where request processing
 * would jump straight from the short circuiting filter directly to sending a response (provided by the short circuiting
 * filter), completely bypassing any endpoint. Perfect for CORS support. If your filter may need to short circuit you
 * should use {@link ShortCircuitingRequestAndResponseFilter} interface that extends this interface instead of this
 * interface directly.
 *
 * <p>This base {@link RequestAndResponseFilter} is configured for a non-short-circuiting filter, namely {@link
 * #isShortCircuitRequestFilter()} returns false and the short circuiting methods are defaulted to throw a {@link
 * UnsupportedOperationException} since they should never be called when {@link #isShortCircuitRequestFilter()} if
 * false. As stated earlier, you should use {@link ShortCircuitingRequestAndResponseFilter} when building a short
 * circuiting filter for convenience since it reverses these decisions. </p> <p> See the method javadocs for details on
 * implementing a request and/or response filter. Hook them up to your application by having {@link
 * ServerConfig#requestAndResponseFilters()} return them.
 *
 * <p>Finally, there are some use cases where request filters should run before security validation, and other use cases
 * where they should run after. You can choose on a per-filter basis whether it runs before or after security
 * validation - see {@link #shouldExecuteBeforeSecurityValidation()} for details.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("UnusedParameters")
public interface RequestAndResponseFilter {

    /**
     * Called by the application when the first chunk of a HTTP request arrives. The {@code currentRequestInfo} will
     * not have any payload information, but it will have everything else (headers, URI/path, HTTP method, etc).
     *
     * <p><b>NOTE:</b> This method is only called if {@link #isShortCircuitRequestFilter()} returns false. If this is a
     * short circuiting filter then {@link #filterRequestFirstChunkWithOptionalShortCircuitResponse(RequestInfo,
     * ChannelHandlerContext)} will be called instead.
     *
     * @param currentRequestInfo
     *     The current request info. Since this method is called when the first chunk of the request arrives, this
     *     object will not have any payload information, but it will have everything else (headers, URI/path, HTTP
     *     method, etc).
     * @param ctx
     *     The {@link ChannelHandlerContext} associated with this request - unlikely to be used but there if you need
     *     it.
     * @param <T>
     *     The payload type of the request.
     *
     * @return The request object that should be used going forward (for other filters, the endpoint, etc). Usually you
     * should just return the same request object you received and make adjustments to headers directly, or if you need
     * to modify something that is normally immutable then you might return a delegate/wrapper object that returns all
     * the data from the original request except for the methods that you want to modify. But ultimately it's up to you
     * what you return so be careful. <b>Null can safely be returned - if null is returned then the original request
     * will continue to be used.</b>
     */
    <T> @Nullable RequestInfo<T> filterRequestFirstChunkNoPayload(
        @NotNull RequestInfo<T> currentRequestInfo,
        @NotNull ChannelHandlerContext ctx
    );

    /**
     * Called by the application after the last chunk of a HTTP request arrives. The {@code currentRequestInfo} will now
     * be fully populated including payload.
     *
     * <p><b>NOTE:</b> This method is only called if {@link #isShortCircuitRequestFilter()} returns false. If this is a
     * short circuiting filter then {@link #filterRequestLastChunkWithOptionalShortCircuitResponse(RequestInfo,
     * ChannelHandlerContext)} will be called instead. Furthermore since a 404 can be detected after the first chunk is
     * fully processed, this method will not be called when a 404 is thrown (the first chunk filter method will always
     * be called, so if you have logic that must run even for a 404 request then you need to put it in the first chunk
     * filter method instead).
     *
     * <p>Similarly, a security exception thrown by your application's {@link ServerConfig#requestSecurityValidator()}
     * may occur after the first chunk, so if you have filter logic that must run even for a failed-security request
     * then you must put it in the first chunk filter method, *and* {@link #shouldExecuteBeforeSecurityValidation()}
     * must be true so that it executes before the security validator runs.
     *
     * @param currentRequestInfo
     *     The current request info, now fully populated including payload.
     * @param ctx
     *     The {@link ChannelHandlerContext} associated with this request - unlikely to be used but there if you need
     *     it.
     * @param <T>
     *     The payload type of the request.
     *
     * @return The request object that should be used going forward (for other filters, the endpoint, etc). Usually you
     * should just return the same request object you received and make adjustments to headers directly, or if you need
     * to modify something that is normally immutable then you might return a delegate/wrapper object that returns all
     * the data from the original request except for the methods that you want to modify. But ultimately it's up to you
     * what you return so be careful. <b>Null can safely be returned - if null is returned then the original request
     * will continue to be used.</b>
     */
    <T> @Nullable RequestInfo<T> filterRequestLastChunkWithFullPayload(
        @NotNull RequestInfo<T> currentRequestInfo,
        @NotNull ChannelHandlerContext ctx
    );

    /**
     * Called by the application when the first chunk of the response is about to be sent to the client. Depending on
     * how the response was generated ("normal" endpoint vs proxy/router endpoint for example) this may be a chunked
     * response in which case no response payload would be available, or it might be a full response that has the
     * payload available. You can use {@link ResponseInfo#isChunkedResponse()} to determine if the payload is
     * available.
     *
     * @param currentResponseInfo
     *     The current response info, which may or may not have the payload attached (call {@link
     *     ResponseInfo#isChunkedResponse()} to see if the payload is available - chunked response means no payload).
     * @param requestInfo
     *     The {@link RequestInfo} associated with this request. Useful if your adjustments to response headers are
     *     dependent on the request that was processed (for example).
     * @param ctx
     *     The {@link ChannelHandlerContext} associated with this request - unlikely to be used but there if you need
     *     it.
     * @param <T>
     *     The payload type of the response.
     *
     * @return The response object that should be sent to the caller. Usually you should just return the same response
     * object you received and make adjustments to headers directly, or if you need to modify something that is normally
     * immutable then you might return a delegate/wrapper object that returns all the data from the original response
     * except for the methods that you want to modify. But ultimately it's up to you what you return so be careful.
     * <b>Null can safely be returned - if null is returned then the original response will continue to be used.</b>
     *
     * <p><b>IMPORTANT NOTE:</b> The implementation class of the return value *must* be the same as the original
     * response object. If the classes differ then an error will be logged and the original response will be used
     * instead. For example, {@link ChunkedResponseInfo} must map to {@link ChunkedResponseInfo}, and {@link
     * FullResponseInfo} must map to {@link FullResponseInfo}. This is another reason it's best to simply adjust the
     * original response when possible.
     */
    <T> @Nullable ResponseInfo<T> filterResponse(
        @NotNull ResponseInfo<T> currentResponseInfo,
        @NotNull RequestInfo<?> requestInfo,
        @NotNull ChannelHandlerContext ctx
    );

    /**
     * This method determines which request filtering methods are used. If this returns true indicating a short
     * circuiting filter, then {@link #filterRequestFirstChunkWithOptionalShortCircuitResponse(RequestInfo,
     * ChannelHandlerContext)} and {@link #filterRequestLastChunkWithOptionalShortCircuitResponse(RequestInfo,
     * ChannelHandlerContext)} will be used. If this returns false indicating it is *NOT* a short circuit filter, then
     * {@link #filterRequestFirstChunkNoPayload(RequestInfo, ChannelHandlerContext)} and {@link
     * #filterRequestLastChunkWithFullPayload(RequestInfo, ChannelHandlerContext)} will be used instead.
     *
     * @return true if this is a short circuiting filter, false otherwise.
     */
    default boolean isShortCircuitRequestFilter() {
        return false;
    }

    /**
     * Called by the application when the first chunk of a HTTP request arrives. The {@code currentRequestInfo} will not
     * have any payload information, but it will have everything else (headers, URI/path, HTTP method, etc).
     *
     * <p><b>NOTE:</b> This method is only called if {@link #isShortCircuitRequestFilter()} returns true. If this is not
     * a short circuiting filter then {@link #filterRequestFirstChunkNoPayload(RequestInfo, ChannelHandlerContext)} will
     * be called instead.
     *
     * @param currentRequestInfo
     *     The current request info. Since this method is called when the first chunk of the request arrives, this
     *     object will not have any payload information, but it will have everything else (headers, URI/path, HTTP
     *     method, etc).
     * @param ctx
     *     The {@link ChannelHandlerContext} associated with this request - unlikely to be used but there if you need
     *     it.
     * @param <T>
     *     The payload type of the request.
     *
     * @return A {@link Pair} containing the request object that should be used going forward (for other filters, the
     * endpoint, etc), and an optional response object. If the response object is specified then this request will
     * immediately short circuit (bypassing any endpoint execution), with the given response being sent back to the
     * caller.
     *
     * <p>For the request info object the same rules apply here that apply to the non-short-circuiting request filter
     * methods: usually you should just return the same request object you received and make adjustments to headers
     * directly, or if you need to modify something that is normally immutable then you might return a delegate/wrapper
     * object that returns all the data from the original request except for the methods that you want to modify. But
     * ultimately it's up to you what you return so be careful. <b>Null can safely be returned - if null is returned for
     * the pair or for the request object in the pair then the original request will continue to be used, and if null is
     * returned for the pair or for the response object in the pair then no short circuiting will be performed.</b>
     */
    default <T> @Nullable Pair<RequestInfo<T>, Optional<ResponseInfo<?>>> filterRequestFirstChunkWithOptionalShortCircuitResponse(
        @NotNull RequestInfo<T> currentRequestInfo,
        @NotNull ChannelHandlerContext ctx
    ) {
        throw new UnsupportedOperationException(
            "Not implemented - should only be called if isShortCircuitRequestFilter() is true");
    }

    /**
     * Called by the application after the last chunk of a HTTP request arrives. The {@code currentRequestInfo} will now
     * be fully populated including payload.
     *
     * <p><b>NOTE:</b> This method is only called if {@link #isShortCircuitRequestFilter()} returns true. If this is not
     * a short circuiting filter then {@link #filterRequestLastChunkWithFullPayload(RequestInfo, ChannelHandlerContext)}
     * will be called instead. Furthermore since a 404 can be detected after the first chunk is fully processed, this
     * method will not be called when a 404 is thrown (the first chunk filter method will always be called, so if you
     * have logic that must run even for a 404 request then you need to put it in the first chunk filter method
     * instead).
     *
     * <p>Similarly, a security exception thrown by your application's {@link ServerConfig#requestSecurityValidator()}
     * may occur after the first chunk, so if you have filter logic that must run even for a failed-security request
     * then you must put it in the first chunk filter method, *and* {@link #shouldExecuteBeforeSecurityValidation()}
     * must be true so that it executes before the security validator runs.
     * 
     * @param currentRequestInfo
     *     The current request info, now fully populated including payload.
     * @param ctx
     *     The {@link ChannelHandlerContext} associated with this request - unlikely to be used but there if you need
     *     it.
     * @param <T>
     *     The payload type of the request.
     *
     * @return A {@link Pair} containing the request object that should be used going forward (for other filters, the
     * endpoint, etc), and an optional response object. If the response object is specified then this request will
     * immediately short circuit (bypassing any endpoint execution), with the given response being sent back to the
     * caller.
     *
     * <p>For the request info object the same rules apply here that apply to the non-short-circuiting request filter
     * methods: usually you should just return the same request object you received and make adjustments to headers
     * directly, or if you need to modify something that is normally immutable then you might return a delegate/wrapper
     * object that returns all the data from the original request except for the methods that you want to modify. But
     * ultimately it's up to you what you return so be careful. <b>Null can safely be returned - if null is returned for
     * the pair or for the request object in the pair then the original request will continue to be used, and if null is
     * returned for the pair or for the response object in the pair then no short circuiting will be performed.</b>
     */
    default <T> @Nullable Pair<RequestInfo<T>, Optional<ResponseInfo<?>>> filterRequestLastChunkWithOptionalShortCircuitResponse(
        @NotNull RequestInfo<T> currentRequestInfo,
        @NotNull ChannelHandlerContext ctx
    ) {
        throw new UnsupportedOperationException(
            "Not implemented - should only be called if isShortCircuitRequestFilter() is true");
    }

    /**
     * This method determines whether the filter should be executed before or after any security validation provided by
     * the {@link RequestSecurityValidator} from the application's {@link ServerConfig#requestSecurityValidator()}. If
     * configured to execute before security validation then *all* requests will be run through this filter's
     * filter-the-first-chunk-of-the-request method ({@link
     * #filterRequestFirstChunkNoPayload(RequestInfo, ChannelHandlerContext)} or {@link
     * #filterRequestFirstChunkWithOptionalShortCircuitResponse(RequestInfo, ChannelHandlerContext)} depending on
     * whether this is a short-circuiting filter or not). If configured to execute after security validation then only
     * requests that pass the security validation will be run through this filter.
     *
     * <p>To put it another way, a security exception thrown by your application's {@link
     * ServerConfig#requestSecurityValidator()} may occur after the first chunk, so if you have filter logic that must
     * run even for a failed-security request then you must put it in the first chunk filter method, *and* this method
     * must return true so that it executes before the security validator runs.
     *
     * <p>NOTE: Another side effect of setting this to true is that it will execute before even endpoint routing has
     * been determined. This means that if this returns true then the first chunk filter method will execute before
     * a 404 in the case where the caller uses a path that does not map to an endpoint.
     *
     * @return true if this filter should execute before any {@link ServerConfig#requestSecurityValidator()} configured
     * for this application, false if it should execute after {@link ServerConfig#requestSecurityValidator()}.
     */
    default boolean shouldExecuteBeforeSecurityValidation() {
        return true;
    }

}
