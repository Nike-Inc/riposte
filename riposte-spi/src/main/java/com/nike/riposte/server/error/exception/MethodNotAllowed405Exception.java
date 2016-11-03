package com.nike.riposte.server.error.exception;

/**
 * Thrown when a request's path matches an endpoint's path, but that endpoint doesn't want to handle the request's HTTP
 * method. Represents a HTTP 405 response code.
 *
 * @author Nic Munroe
 */
public class MethodNotAllowed405Exception extends RuntimeException {

    public final String requestPath;
    public final String requestMethod;

    public MethodNotAllowed405Exception(String message, String requestPath, String requestMethod) {
        super(message);

        this.requestPath = requestPath;
        this.requestMethod = requestMethod;
    }

}
