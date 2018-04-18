package com.nike.riposte.server.error.handler;

/**
 * Interface for a class that knows how to serialize a {@link ErrorResponseBody} to a string.
 *
 * @author Nic Munroe
 */
@FunctionalInterface
public interface ErrorResponseBodySerializer {

    /**
     * @param errorResponseBody
     *     The error response body to serialize to a string.
     *
     * @return The given {@link ErrorResponseBody} after being serialized to a string, or null if you want a blank
     * response body.
     */
    String serializeErrorResponseBodyToString(ErrorResponseBody errorResponseBody);

}
