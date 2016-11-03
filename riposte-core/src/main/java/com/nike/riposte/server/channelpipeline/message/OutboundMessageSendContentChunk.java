package com.nike.riposte.server.channelpipeline.message;

import io.netty.handler.codec.http.HttpContent;

/**
 * Implementation of {@link OutboundMessage} that indicates to the response sender that it should write the given {@link
 * #contentChunk} to the user. This is intended to be used with multi-chunk/streaming responses only. If you are an
 * endpoint handler and you have a full response message to send back you should stuff the response info into the
 * current {@link com.nike.riposte.server.http.HttpProcessingState} and use {@link
 * LastOutboundMessageSendFullResponseInfo} instead.
 * <p/>
 * NOTE: This is intended to be used by endpoint handlers only when calling the various various {@code
 * io.netty.channel.ChannelHandlerContext#fire...(Object msg)} methods.
 *
 * @author Nic Munroe
 */
public class OutboundMessageSendContentChunk implements ChunkedOutboundMessage {

    public final HttpContent contentChunk;

    public OutboundMessageSendContentChunk(HttpContent contentChunk) {
        this.contentChunk = contentChunk;
    }

}
