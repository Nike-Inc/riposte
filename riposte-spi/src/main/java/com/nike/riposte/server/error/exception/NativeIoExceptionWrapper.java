package com.nike.riposte.server.error.exception;

import java.io.IOException;

/**
 * Thrown when we receive a {@code io.netty.channel.unix.Errors.NativeIoException} so that a usable stack trace shows
 * up in the logs ({@code NativeIoException}s are often setup to suppress their stack trace, which makes debugging
 * difficult). Should usually map to a 503 HTTP status code.
 *
 * <p>NOTE: The {@link NativeIoExceptionWrapper#NativeIoExceptionWrapper(String, IOException)} constructor takes in a
 * basic {@link IOException} as the cause, but this wrapper class should only be used if the cause is actually a
 * {@code io.netty.channel.unix.Errors.NativeIoException}. We pin it to {@link IOException} to avoid pulling in
 * the {@code io.netty:netty-transport-native-unix-common} dependency just to be able to reference
 * {@code NativeIoException}.
 *
 * @author Nic Munroe
 */
public class NativeIoExceptionWrapper extends RuntimeException {

    public NativeIoExceptionWrapper(String message, IOException cause) {
        super(message, cause);
    }
}
