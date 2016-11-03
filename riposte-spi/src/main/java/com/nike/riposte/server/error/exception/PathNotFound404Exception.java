package com.nike.riposte.server.error.exception;

/**
 * Thrown when a request tries to hit an endpoint that doesn't exist (no matching path). Represents a HTTP 404 response
 * code.
 *
 * @author Nic Munroe
 */
public class PathNotFound404Exception extends RuntimeException {

    public PathNotFound404Exception(String message) {
        super(message);
    }

}
