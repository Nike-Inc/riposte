package com.nike.riposte.server.channelpipeline.message;

/**
 * Marker interface that indicates this is the last message that will be going through the pipeline related to the
 * current request, so any cleanup should be performed (e.g. distributed trace ending, access logging, request metrics,
 * etc).
 * <p/>
 * NOTE: This is intended to be used by endpoint handlers only when calling the various various {@code
 * io.netty.channel.ChannelHandlerContext#fire...(Object msg)} methods.
 *
 * @author Nic Munroe
 */
public interface LastOutboundMessage extends OutboundMessage {
}
