package com.nike.riposte.server.error.handler;

import com.nike.backstopper.model.riposte.ErrorResponseInfoImpl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The "response info" for an error response to send to the user. Contains the body content, the HTTP status code, and
 * any extra headers that should be returned to the user.
 * <p/>
 * You can create your own instance of this class, however it's highly recommended that you just use the prebuilt {@link
 * ErrorResponseInfoImpl} class which is part of the default error handling and validation system that is designed to
 * make error handling and validation easy and is based on Backstopper.
 *
 * @author Nic Munroe
 */
public interface ErrorResponseInfo {

    /**
     * @return The response body content that should be sent to the user. This should never return null, because
     * {@link ErrorResponseBody#errorId()} is required - if you want an empty response body payload, then the
     * returned {@link ErrorResponseBody#bodyToSerialize()} can be null.
     */
    @NotNull ErrorResponseBody getErrorResponseBody();

    /**
     * @return The HTTP status code that should be returned to the user with the response.
     */
    int getErrorHttpStatusCode();

    /**
     * @return A map of any extra headers that should be added to the response sent to the user. You can safely return
     * null if there are no extra headers to add to the response.
     */
    @Nullable Map<String, List<String>> getExtraHeadersToAddToResponse();

}
