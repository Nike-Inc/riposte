package com.nike.riposte.server.error.exception;

/**
 * This will be thrown when the server detects an invalid state that should be prevented by a proper Riposte handler
 * pipeline. This represents a major unrecoverable error.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class InvalidRipostePipelineException extends RuntimeException {

    public InvalidRipostePipelineException() {
        super();
    }

    public InvalidRipostePipelineException(String message) {
        super(message);
    }

    public InvalidRipostePipelineException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidRipostePipelineException(Throwable cause) {
        super(cause);
    }

}
