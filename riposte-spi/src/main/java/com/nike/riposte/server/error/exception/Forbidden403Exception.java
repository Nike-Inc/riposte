/**
 * 
 */
package com.nike.riposte.server.error.exception;

import java.util.ArrayList;
import java.util.List;

import com.nike.internal.util.Pair;

/**
 * Thrown when a request does not have a valid authorization header. Represents a HTTP 403 response code.
 * 
 * @author pevans
 *
 */
public class Forbidden403Exception extends RuntimeException {

    public final String requestPath;
    public final String authorizationHeader;
    public final List<Pair<String, String>> extraDetailsForLogging;
    
    public Forbidden403Exception(String message, String requestPath, String authorizationHeader) {
        this(message, requestPath, authorizationHeader, new ArrayList<>());
    }

    public Forbidden403Exception(String message, String requestPath, String authorizationHeader,
                                    List<Pair<String, String>> extraDetailsForLogging) {
        super(message);

        this.requestPath = requestPath;
        this.authorizationHeader = authorizationHeader;
        this.extraDetailsForLogging = extraDetailsForLogging;
    }
	private static final long serialVersionUID = 4921880566299500314L;

}
