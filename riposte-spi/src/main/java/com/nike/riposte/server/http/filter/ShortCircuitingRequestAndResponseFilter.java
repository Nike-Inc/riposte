package com.nike.riposte.server.http.filter;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import java.util.Optional;

import io.netty.channel.ChannelHandlerContext;

/**
 * An extension of {@link RequestAndResponseFilter} that pre-configures the methods to work as a short-circuiting filter. Specifically
 * {@link #isShortCircuitRequestFilter()} will return true, and the non-short-circuiting methods are configured to throw a
 * {@link UnsupportedOperationException} since they should never be called when {@link #isShortCircuitRequestFilter()} is true. You just need
 * to implement the short circuiting request filter methods and the response filter method. See the javadocs for {@link RequestAndResponseFilter}
 * and its methods for more general information on request/response filtering.
 *
 * @author Nic Munroe
 */
public interface ShortCircuitingRequestAndResponseFilter extends RequestAndResponseFilter {

    @Override
    default <T> RequestInfo<T> filterRequestFirstChunkNoPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
        throw new UnsupportedOperationException("This method should never be called for ShortCircuitingRequestAndResponseFilter classes "
                                                + "(where isShortCircuitRequestFilter() returns true)");
    }

    @Override
    default <T> RequestInfo<T> filterRequestLastChunkWithFullPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
        throw new UnsupportedOperationException("This method should never be called for ShortCircuitingRequestAndResponseFilter classes "
                                                + "(where isShortCircuitRequestFilter() returns true)");
    }

    @Override
    default boolean isShortCircuitRequestFilter() {
        return true;
    }

    @Override
    <T> Pair<RequestInfo<T>, Optional<ResponseInfo<?>>> filterRequestFirstChunkWithOptionalShortCircuitResponse(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx);

    @Override
    <T> Pair<RequestInfo<T>, Optional<ResponseInfo<?>>> filterRequestLastChunkWithOptionalShortCircuitResponse(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx);
}
