package com.nike.riposte.server.error.exception;

import com.nike.riposte.server.config.ServerConfig;

/**
 * This will be thrown when the server detects too many channels are open (based on {@link
 * ServerConfig#maxOpenIncomingServerChannels()}). See the javadocs for {@link
 * ServerConfig#maxOpenIncomingServerChannels()} for more information on when this exception should be thrown and how
 * the server handles it.
 *
 * @author Nic Munroe
 */
public class TooManyOpenChannelsException extends RuntimeException {

    public final int actualOpenChannelsCount;
    public final int maxOpenChannelsLimit;

    public TooManyOpenChannelsException(int actualOpenChannelsCount, int maxOpenChannelsLimit) {
        super("Too many open channels were detected. This new channel will be immediately closed. Current number of "
              + "open channels: " + actualOpenChannelsCount + ", max allowed: " + maxOpenChannelsLimit);
        this.actualOpenChannelsCount = actualOpenChannelsCount;
        this.maxOpenChannelsLimit = maxOpenChannelsLimit;
    }
}
