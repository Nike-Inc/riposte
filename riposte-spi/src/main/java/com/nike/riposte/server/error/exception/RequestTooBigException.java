package com.nike.riposte.server.error.exception;

/**
 * An exception that will be thrown when the total request size is larger than the max allowed. This may be a
 * short-circuit exception in the case where the request contained a content-length header that indicated the size was
 * too large and we could throw the error before accepting payload data, or it may not be thrown until after we've
 * received enough data that it went over the max (i.e. in the case of chunked transfer encoding where we don't know how
 * big the request is going to be until we've received the data).
 *
 * @author Nic Munroe
 */
public class RequestTooBigException extends RuntimeException {

    /**
     * Creates a new instance with no message or cause.
     */
    public RequestTooBigException() {
        // Do nothing
    }

    /**
     * Creates a new instance with the given message and cause.
     */
    public RequestTooBigException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance with the given message.
     */
    public RequestTooBigException(String message) {
        super(message);
    }

    /**
     * Creates a new instance with the given cause.
     */
    public RequestTooBigException(Throwable cause) {
        super(cause);
    }

}
