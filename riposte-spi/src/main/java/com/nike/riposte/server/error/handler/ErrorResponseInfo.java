package com.nike.riposte.server.error.handler;

import com.nike.backstopper.model.riposte.ErrorResponseInfoImpl;

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
     * @return The response body content that should be sent to the user.
     */
    ErrorResponseBody getErrorResponseBody();

    /**
     * @return The HTTP status code that should be returned to the user with the response.
     */
    int getErrorHttpStatusCode();

    /**
     * @return A map of any extra headers that should be added to the response sent to the user.
     */
    Map<String, List<String>> getExtraHeadersToAddToResponse();

}
