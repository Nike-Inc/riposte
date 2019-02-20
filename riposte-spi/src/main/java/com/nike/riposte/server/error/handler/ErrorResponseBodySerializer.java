package com.nike.riposte.server.error.handler;

import org.jetbrains.annotations.Nullable;

/**
 * Interface for a class that knows how to serialize a {@link ErrorResponseBody} to a string.
 *
 * @author Nic Munroe
 */
@FunctionalInterface
public interface ErrorResponseBodySerializer {

    /**
     * @param errorResponseBody
     *     The error response body to serialize to a string - this method will use {@link
     *     ErrorResponseBody#bodyToSerialize()} as the object for serialization. This parameter may be null.
     *     If this parameter is null or if {@link ErrorResponseBody#bodyToSerialize()} is null then this method will
     *     return null to indicate a blank response body is desired.
     *
     * @return The given {@link ErrorResponseBody} after being serialized to a string, or null if a blank response body
     * should be returned to the caller (null will be returned if {@link ErrorResponseBody#bodyToSerialize()} or
     * the {@code errorResponseBody} parameter itself is null).
     */
    @Nullable String serializeErrorResponseBodyToString(@Nullable ErrorResponseBody errorResponseBody);

}
