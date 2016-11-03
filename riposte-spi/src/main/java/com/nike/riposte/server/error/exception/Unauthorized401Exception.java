package com.nike.riposte.server.error.exception;

import com.nike.internal.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Thrown when a request does not have a valid authorization header. Represents a HTTP 401 response code.
 *
 * @author Florin Dragu
 */
public class Unauthorized401Exception extends RuntimeException {

    public final String requestPath;
    public final String authorizationHeader;
    public final List<Pair<String, String>> extraDetailsForLogging;

    public Unauthorized401Exception(String message, String requestPath, String authorizationHeader) {
        this(message, requestPath, authorizationHeader, new ArrayList<>());
    }

    public Unauthorized401Exception(String message, String requestPath, String authorizationHeader,
                                    List<Pair<String, String>> extraDetailsForLogging) {
        super(message);

        this.requestPath = requestPath;
        this.authorizationHeader = authorizationHeader;
        this.extraDetailsForLogging = extraDetailsForLogging;
    }

}
