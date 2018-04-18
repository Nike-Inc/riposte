package com.nike.riposte.server.error.handler;

import com.nike.backstopper.model.riposte.ErrorResponseBodyImpl;

/**
 * Represents the response body content for an error response. The only thing strictly required is {@link #errorId()},
 * although it's recommended that you have a consistent error contract for all errors. {@link #errorId()} will be
 * returned as a response header to uniquely identify this error, and {@link #bodyToSerialize()} will be serialized
 * to provide the response payload. By default {@link #bodyToSerialize()} returns this instance, so this object is
 * what gets serialized if you don't override it, but you can override it to return {@code null} for a blank payload,
 * or have it return some other object if needed (i.e. delegating to an object from a third-party library that doesn't
 * implement this interface).
 *
 * <p>You can create your own instance of this class, however it's highly recommended that you just use the prebuilt
 * {@link ErrorResponseBodyImpl} class which is part of the default error handling and validation system that is
 * designed to make error handling and validation easy and is based on Backstopper.
 *
 * @author Nic Munroe
 */
public interface ErrorResponseBody {

    /**
     * @return The unique ID associated with this error. This is usually just the string value of a {@link
     * java.util.UUID}.
     */
    String errorId();

    /**
     * @return The object that should be serialized into the response body payload, or null if you want a blank/empty
     * response body payload.
     */
    default Object bodyToSerialize() {
        return this;
    }
}
