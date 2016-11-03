package com.nike.riposte.server.channelpipeline.message;

/**
 * Implementation of {@link OutboundMessage} that indicates to the response sender that it should send the first
 * response info data back to the user (the first chunk with headers/etc). Since all the necessary info (headers &
 * status code) is contained in the current {@link com.nike.riposte.server.http.HttpProcessingState#getResponseInfo()}
 * this message does not need to store anything. Users of this class should just refer to {@link #INSTANCE} to avoid
 * creating unnecessary objects.
 * <p/>
 * NOTE: This is intended to be used by endpoint handlers only when calling the various various {@code
 * io.netty.channel.ChannelHandlerContext#fire...(Object msg)} methods.
 *
 * @author Nic Munroe
 */
public class OutboundMessageSendHeadersChunkFromResponseInfo implements ChunkedOutboundMessage {

    public static final OutboundMessageSendHeadersChunkFromResponseInfo INSTANCE =
        new OutboundMessageSendHeadersChunkFromResponseInfo();

}
