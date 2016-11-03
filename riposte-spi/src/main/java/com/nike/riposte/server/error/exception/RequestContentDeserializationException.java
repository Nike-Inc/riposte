package com.nike.riposte.server.error.exception;

import com.nike.riposte.server.http.RequestInfo;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Thrown when the server is unable to deserialize request content, usually because it is malformed (e.g. invalid JSON).
 *
 * @author Nic Munroe
 */
public class RequestContentDeserializationException extends RuntimeException {

    public final String httpMethod;
    public final String requestPath;
    public final TypeReference<?> desiredObjectType;

    public RequestContentDeserializationException(String exceptionMessage, Throwable cause, RequestInfo<?> requestInfo,
                                                  TypeReference<?> desiredObjectType) {
        super(exceptionMessage, cause);
        this.httpMethod = String.valueOf(requestInfo.getMethod());
        this.requestPath = requestInfo.getPath();
        this.desiredObjectType = desiredObjectType;
    }

}
