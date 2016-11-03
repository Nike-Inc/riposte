package com.nike.riposte.server.error.exception;

import io.netty.channel.Channel;

/**
 * This will be thrown when a Netty {@link io.netty.channel.Channel} used for an async downstream call sits idle beyond
 * the specified timeout value. This generally occurs in two situations:
 * <ol>
 *     <li>An active downstream call takes too long to return and passes the timeout threshold.</li>
 *     <li>
 *         A pooled connection sits idle beyond the threshold (no active downstream call, just sitting in the pool
 *         unused).
 *     </li>
 * </ol>
 *
 * @author Nic Munroe
 */
public class DownstreamIdleChannelTimeoutException extends RuntimeException {

    public final long timeoutValueMillis;
    public final String channelId;

    public DownstreamIdleChannelTimeoutException(long timeoutValueMillis, Channel channel) {
        super("The downstream channel was idle too long. downstream_channel_timeout_value_millis=" + timeoutValueMillis
              + ", idle_channel_id=" + getChannelId(channel)
        );
        this.timeoutValueMillis = timeoutValueMillis;
        this.channelId = getChannelId(channel);
    }

    @SuppressWarnings("WeakerAccess")
    protected static String getChannelId(Channel channel) {
        if (channel == null)
            return "null";

        return channel.toString();
    }
}
