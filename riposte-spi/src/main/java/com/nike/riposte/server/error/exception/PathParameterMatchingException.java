package com.nike.riposte.server.error.exception;

/**
 * Thrown when the server tries to decode path parameters for a request, but the request path doesn't match the path
 * template.
 *
 * @author Nic Munroe
 */
public class PathParameterMatchingException extends RuntimeException {

    public final String pathTemplate;
    public final String nonMatchingUriPath;

    public PathParameterMatchingException(String message, String pathTemplate, String nonMatchingUriPath) {
        super(message);

        this.pathTemplate = pathTemplate;
        this.nonMatchingUriPath = nonMatchingUriPath;
    }
}
