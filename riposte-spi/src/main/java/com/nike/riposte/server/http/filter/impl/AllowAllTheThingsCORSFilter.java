package com.nike.riposte.server.http.filter.impl;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.filter.ShortCircuitingRequestAndResponseFilter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

/**
 * Simple filter that implements the wildcard "allow everybody to do everything" CORS-enabled spec. *Every* response
 * will be tagged with a {@code Access-Control-Allow-Origin: *} response header which indicates any origin is allowed to
 * access the resource. This supports the CORS preflight {@code OPTIONS} request as well - when the server sees a
 * request with an {@code OPTIONS} HTTP method it will short circuit (bypassing all endpoints) and return a blank HTTP
 * status code 200 response (which will also be tagged with the {@code Access-Control-Allow-Origin: *} response header).
 * This wide-open CORS policy has potential security implications so make sure you understand the ramifications before
 * enabling it.
 *
 * <p>See here for more details about CORS:
 * <ul>
 *     <li><a href="https://www.w3.org/wiki/CORS_Enabled">https://www.w3.org/wiki/CORS_Enabled</a></li>
 *     <li>
 *         <a href="https://code.google.com/archive/p/html5security/wikis/CrossOriginRequestSecurity.wiki">
 *             https://code.google.com/archive/p/html5security/wikis/CrossOriginRequestSecurity.wiki
 *         </a>
 *     </li>
 *     <li>
 *         <a href="https://en.wikipedia.org/wiki/Cross-origin_resource_sharing">
 *             https://en.wikipedia.org/wiki/Cross-origin_resource_sharing
 *         </a>
 *     </li>
 * </ul>
 *
 * @author Nic Munroe
 */
public class AllowAllTheThingsCORSFilter implements ShortCircuitingRequestAndResponseFilter {

    @Override
    public <T> @Nullable ResponseInfo<T> filterResponse(
        @NotNull ResponseInfo<T> currentResponseInfo,
        @NotNull RequestInfo<?> requestInfo,
        @NotNull ChannelHandlerContext ctx
    ) {
        // *All* responses get tagged with the "do whatever you want" CORS ACAO header.
        currentResponseInfo.getHeaders().set("Access-Control-Allow-Origin", "*");
        return currentResponseInfo;
    }

    @Override
    public <T> @Nullable Pair<RequestInfo<T>, Optional<ResponseInfo<?>>> filterRequestFirstChunkWithOptionalShortCircuitResponse(
        @NotNull RequestInfo<T> currentRequestInfo,
        @NotNull ChannelHandlerContext ctx
    ) {
        if (HttpMethod.OPTIONS.equals(currentRequestInfo.getMethod())) {
            // CORS preflight OPTIONS request. Return a blank 200 response, and the filterResponse() method will
            //      add the "do whatever you want" response header.
            return Pair.of(currentRequestInfo, Optional.of(ResponseInfo.newBuilder().withHttpStatusCode(200).build()));
        }

        // Not a CORS preflight OPTIONS request. Continue on normally.
        return null;
    }

    @Override
    public <T> @Nullable Pair<RequestInfo<T>, Optional<ResponseInfo<?>>> filterRequestLastChunkWithOptionalShortCircuitResponse(
        @NotNull RequestInfo<T> currentRequestInfo,
        @NotNull ChannelHandlerContext ctx
    ) {
        // If it was a CORS preflight OPTIONS request it would have been handled by
        //      filterRequestFirstChunkWithOptionalShortCircuitResponse() and we would never reach here.
        //      Therefore this is a normal call. Nothing for us to do in this method.
        return null;
    }
}
