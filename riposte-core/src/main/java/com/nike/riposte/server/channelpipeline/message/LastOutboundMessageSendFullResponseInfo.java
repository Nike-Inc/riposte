package com.nike.riposte.server.channelpipeline.message;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ResponseInfo;

import io.netty.channel.ChannelHandlerContext;

/**
 * Implementation of {@link LastOutboundMessage} that indicates the response sender should use the current {@link
 * ResponseInfo} found in {@link ChannelAttributes#getHttpProcessingStateForChannel(ChannelHandlerContext)}'s {@link
 * HttpProcessingState#getResponseInfo()} as a full response. There will be no other response chunks coming - just use
 * the data contained in the state's response info as the full response.
 * <p/>
 * Since all the necessary info (including content) is contained in the current {@link
 * HttpProcessingState#getResponseInfo()} this message does not need to store anything. Users of this class should just
 * refer to {@link #INSTANCE} to avoid creating unnecessary objects.
 * <p/>
 * NOTE: This is intended to be used by endpoint handlers only when calling the various various {@code
 * io.netty.channel.ChannelHandlerContext#fire...(Object msg)} methods.
 *
 * @author Nic Munroe
 */
public class LastOutboundMessageSendFullResponseInfo implements LastOutboundMessage {

    public static final LastOutboundMessageSendFullResponseInfo INSTANCE =
        new LastOutboundMessageSendFullResponseInfo();

}
