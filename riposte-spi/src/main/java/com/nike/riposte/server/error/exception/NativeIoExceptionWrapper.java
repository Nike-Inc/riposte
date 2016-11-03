package com.nike.riposte.server.error.exception;

import io.netty.channel.unix.Errors;

/**
 * Thrown when we receive a {@link Errors.NativeIoException} so that a usable stack trace shows up in the logs. Should
 * usually map to a 503 HTTP status code.
 *
 * @author Nic Munroe
 */
public class NativeIoExceptionWrapper extends RuntimeException {

    public NativeIoExceptionWrapper(String message, Errors.NativeIoException cause) {
        super(message, cause);
    }
}
