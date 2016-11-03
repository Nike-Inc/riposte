package com.nike.riposte.server.channelpipeline.message;

import io.netty.handler.codec.http.LastHttpContent;

/**
 * Implementation of {@link LastOutboundMessage} intended to be used with multi-chunk/streamed responses - indicates
 * this is the final chunk to send to the user and no further response chunks will be coming for the current request.
 * <p/>
 * NOTE: This is intended to be used by endpoint handlers only when calling the various various {@code
 * io.netty.channel.ChannelHandlerContext#fire...(Object msg)} methods.
 *
 * @author Nic Munroe
 */
public class LastOutboundMessageSendLastContentChunk extends OutboundMessageSendContentChunk
    implements LastOutboundMessage {

    public LastOutboundMessageSendLastContentChunk(LastHttpContent contentChunk) {
        super(contentChunk);
    }
}
