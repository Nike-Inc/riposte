package com.nike.riposte.server.http.impl;

import com.nike.riposte.server.http.RequestInfo;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpContent;

/**
 * Interface that implementations of {@link RequestInfo} should also implement - this interface covers some
 * under-the-hood stuff that implementations of {@link RequestInfo} need to support for Riposte internals to work
 * correctly, but app developers shouldn't need to worry about (which is why we hide them here in a separate interface).
 *
 * @author Nic Munroe
 */
public interface RiposteInternalRequestInfo {

    /**
     * Indicates to this {@link RequestInfo} implementation that all content chunks will be handled externally, e.g.
     * {@code ProxyRouterEndpoint}s where the content chunks are immediately released after being sent downstream
     * to avoid pulling the full request into memory. In other words this should disable {@link
     * RequestInfo#releaseContentChunks()} so that when that method is called it does *not* call {@link
     * ByteBuf#release()} on any content chunks. This avoids releasing chunks too many times, thus causing reference
     * counting bugs.
     *
     * <p>IMPORTANT NOTE: Implementations should continue to {@link ByteBuf#retain()} chunks as they are added via
     * {@link RequestInfo#addContentChunk(HttpContent)}. This method only means they will be *released* externally,
     * but {@link RequestInfo} is still expected to retain the chunks so that they aren't prematurely destroyed.
     *
     * <p>ALSO NOTE: Since content chunks may be released externally early (even before the last content chunk arrives
     * from the original caller) {@link RequestInfo} must never allow {@link
     * RequestInfo#isCompleteRequestWithAllChunks()} to be set to true and must never allow any of the content-related
     * methods to return "real" values (e.g. all the {@code RequestInfo#get*Content()} methods should return null),
     * *UNLESS* the full request has already arrived and the content already pulled into heap memory at which point no
     * damage would be done by allowing those methods to continue to work.
     */
    void contentChunksWillBeReleasedExternally();

}
