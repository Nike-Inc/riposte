package com.nike.riposte.server.error.handler;

import com.nike.backstopper.model.riposte.ErrorResponseBodyImpl;

/**
 * Represents the response body content for an error response. The only thing strictly required is {@link #errorId()},
 * although it's recommended that you have a consistent error contract for all errors.
 * <p/>
 * You can create your own instance of this class, however it's highly recommended that you just use the prebuilt {@link
 * ErrorResponseBodyImpl} class which is part of the default error handling and validation system that is designed to
 * make error handling and validation easy and is based on Backstopper.
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
