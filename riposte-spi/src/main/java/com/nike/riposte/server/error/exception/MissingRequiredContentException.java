package com.nike.riposte.server.error.exception;

import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;

public class MissingRequiredContentException extends RuntimeException {

    public final String path;
    public final String method;
    public final String endpointClassName;

    public MissingRequiredContentException() {
        this("null", "null", "null");
    }

    public MissingRequiredContentException(String path, String method, String endpointClassName) {
        this.path = path;
        this.method = method;
        this.endpointClassName = endpointClassName;
    }

    public MissingRequiredContentException(RequestInfo<?> requestInfo, Endpoint<?> endpoint) {
        this(
                requestInfo == null? "null" : requestInfo.getPath(),
                requestInfo == null? "null" : requestInfo.getMethod() == null ? "null" : requestInfo.getMethod().name(),
                endpoint == null ? "null" : endpoint.getClass().getSimpleName()
        );
    }

}
