package com.nike.riposte.serviceregistration.eureka;

/**
 * Encapsulates Eureka related exception.
 */
@SuppressWarnings("WeakerAccess")
public class EurekaException extends RuntimeException {

    public EurekaException(String message, Throwable cause) {
        super(message, cause);
    }
}
