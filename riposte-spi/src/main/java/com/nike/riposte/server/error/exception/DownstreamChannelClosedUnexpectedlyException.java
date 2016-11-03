package com.nike.riposte.server.error.exception;

import io.netty.channel.Channel;

/**
 * This will be thrown when the channel being used to talk to a downstream system is closed unexpectedly before the call
 * has finished. This would normally happen because the downstream system force-closed the connection in a non-graceful
 * way, or some network glitch broke the connection, or some other similarly harsh event.
 *
 * @author Nic Munroe
 */
public class DownstreamChannelClosedUnexpectedlyException extends RuntimeException {

    public final String channelId;

    public DownstreamChannelClosedUnexpectedlyException(Channel channel) {
        super("The channel used to talk to the downstream system was closed while the call was active - probably by "
              + "the downstream system, but also possibly by us if an unrecoverable error occurred and we "
              + "preemptively closed the channel. closed_channel_id=" + getChannelId(channel)
        );
        this.channelId = getChannelId(channel);
    }

    @SuppressWarnings("WeakerAccess")
    protected static String getChannelId(Channel channel) {
        if (channel == null)
            return "null";

        return channel.toString();
    }

}
