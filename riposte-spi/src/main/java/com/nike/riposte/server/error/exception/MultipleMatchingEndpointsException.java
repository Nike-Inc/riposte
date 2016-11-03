package com.nike.riposte.server.error.exception;

import com.nike.riposte.server.http.Endpoint;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when multiple endpoints want to match a request (with both path and HTTP method matching).
 *
 * @author Nic Munroe
 */
public class MultipleMatchingEndpointsException extends RuntimeException {

    public final List<String> matchingEndpointsDetails;
    public final String requestPath;
    public final String requestMethod;

    public MultipleMatchingEndpointsException(String message, List<Endpoint<?>> fullyMatchingEndpoints,
                                              String requestPath, String requestMethod) {
        super(message);
        this.matchingEndpointsDetails = fullyMatchingEndpoints.stream()
                                                              .map(e -> e.getClass().getName())
                                                              .collect(Collectors.toList());
        this.requestPath = requestPath;
        this.requestMethod = requestMethod;
    }

}
