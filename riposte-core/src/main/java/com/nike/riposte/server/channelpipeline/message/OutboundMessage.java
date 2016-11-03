package com.nike.riposte.server.channelpipeline.message;

/**
 * Marker interface to be used as the message object with the various {@code
 * io.netty.channel.ChannelHandlerContext#fire...(Object msg)} methods once the correct endpoint handler has processed a
 * request and is ready to start sending response info back to the user.
 * <p/>
 * NOTE: This is intended to be used by endpoint handlers only when calling the various various {@code
 * io.netty.channel.ChannelHandlerContext#fire...(Object msg)} methods.
 *
 * @author Nic Munroe
 */
public interface OutboundMessage {

}
