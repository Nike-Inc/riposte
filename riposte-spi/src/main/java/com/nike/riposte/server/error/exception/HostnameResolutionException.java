package com.nike.riposte.server.error.exception;

/**
 * Thrown when a hostname resolution attempt fails. Should usually map to a 503 HTTP status code.
 *
 * @author Nic Munroe
 */
public class HostnameResolutionException extends RuntimeException {

    public HostnameResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
