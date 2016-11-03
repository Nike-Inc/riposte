package com.nike.riposte.server.error.exception;

/**
 * This will be thrown when the server has trouble extracting the charset from the Content-Type header. The server falls
 * back gracefully if the charset is not defined, so this exception is only when the specified charset is truly
 * invalid.
 *
 * @author Nic Munroe
 */
public class InvalidCharsetInContentTypeHeaderException extends RuntimeException {

    public final String invalidContentTypeHeader;

    public InvalidCharsetInContentTypeHeaderException(String message, Throwable cause,
                                                      String invalidContentTypeHeader) {
        super(message, cause);
        this.invalidContentTypeHeader = invalidContentTypeHeader;
    }

}
