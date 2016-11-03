package com.nike.riposte.server.channelpipeline.message;

/**
 * Marker interface for chunked messages - this does not represent a full response, but rather a single part of a
 * multi-chunk response. It might be the first or last chunk, or something in the middle.
 * <p/>
 * NOTE: This is intended to be used by endpoint handlers only when calling the various various {@code
 * io.netty.channel.ChannelHandlerContext#fire...(Object msg)} methods.
 *
 * @author Nic Munroe
 */
public interface ChunkedOutboundMessage extends OutboundMessage {
}
