package com.nike.riposte.server.error.handler;

import com.nike.backstopper.handler.riposte.RiposteUnhandledExceptionHandler;
import com.nike.riposte.server.http.RequestInfo;

import org.jetbrains.annotations.NotNull;

/**
 * Interface describing a "backstop / last chance" error handler for Riposte that is guaranteed to handle the given error
 * by returning a generic error response.
 * <p/>
 * You can create your own instance of this class, however it's highly recommended that you just use the prebuilt {@link
 * RiposteUnhandledExceptionHandler} class which is part of the default error handling and validation system that is
 * designed to make error handling and validation easy and is based on Backstopper.
 *
 * @author Nic Munroe
 */
public interface RiposteUnhandledErrorHandler {

    /**
     * @param error
     *     The error that ABSOLUTELY MUST be handled.
     * @param requestInfo
     *     The {@link com.nike.riposte.server.http.RequestInfo} associated with the request that threw the error. Useful
     *     for logging information to make debugging easier.
     *
     * @return A generic {@link com.nike.riposte.server.error.handler.ErrorResponseInfo} that should be used to send the
     * response back to the user. This should never return null. The various arguments should be used to log as much
     * info as possible on the request and the error to make debugging easier.
     */
    @NotNull ErrorResponseInfo handleError(@NotNull Throwable error, @NotNull RequestInfo<?> requestInfo);

}
