package com.nike.riposte.server.error.exception;

public class InvalidHttpRequestException extends RuntimeException {

    public InvalidHttpRequestException(String message, Throwable cause) {
        super(message, cause);
    }

}
