package com.nike.riposte.server.error.exception;

import com.nike.riposte.server.config.ServerConfig;

/**
 * This will be thrown when the server detects that too much idle time has passed after receiving the first HTTP chunk
 * in a call but before the last chunk is received (based on {@link ServerConfig#incompleteHttpCallTimeoutMillis()}.
 * See the javadocs for {@link ServerConfig#incompleteHttpCallTimeoutMillis()} for more information on when this
 * exception should be thrown and how the server handles it.
 *
 * @author Nic Munroe
 */
public class IncompleteHttpCallTimeoutException extends RuntimeException {

    @SuppressWarnings("WeakerAccess")
    public final long timeoutMillis;

    public IncompleteHttpCallTimeoutException(long timeoutMillis) {
        super("Too much time passed without receiving any HTTP chunks from the caller after starting a request. The "
              + "HTTP request is incomplete and invalid, and the caller doesn't seem to be sending any more data. "
              + "Timeout value in millis: " + timeoutMillis);
        this.timeoutMillis = timeoutMillis;
    }
}
