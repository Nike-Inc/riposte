package com.nike.riposte.server.error.exception;

import com.nike.riposte.server.error.handler.RiposteErrorHandler;

/**
 * Typed exception used by {@link RiposteErrorHandler} to indicate that some unexpected (and major) error occurred while
 * handling an error. Likely indicates a bug in the error handler that needs to be fixed.
 *
 * @author Nic Munroe
 */
public class UnexpectedMajorErrorHandlingError extends Exception {

    public UnexpectedMajorErrorHandlingError(String message, Throwable cause) {
        super(message, cause);
    }
}
