package com.nike.riposte.server.error.handler;

import com.nike.backstopper.handler.riposte.RiposteApiExceptionHandler;
import com.nike.riposte.server.error.exception.UnexpectedMajorErrorHandlingError;
import com.nike.riposte.server.http.RequestInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Interface describing an error handler for Netty that takes in the error that occurred along with some info useful for
 * logging and returns an {@link ErrorResponseInfo} that can be used to build the response sent to the user (or null if
 * this handler doesn't know how to deal with the given error and {@link RiposteUnhandledErrorHandler} should take care
 * of it).
 * <p/>
 * You can create your own instance of this class, however it's highly recommended that you just use the prebuilt {@link
 * RiposteApiExceptionHandler} class which is part of the default error handling and validation system that is designed
 * to make error handling and validation easy and is based on Backstopper.
 *
 * @author Nic Munroe
 */
public interface RiposteErrorHandler {

    /**
     * @param error
     *     The error that may or may not be handled by this handler.
     * @param requestInfo
     *     The {@link com.nike.riposte.server.http.RequestInfo} associated with the request that threw the error. Useful
     *     for logging information to make debugging easier.
     *
     * @return The {@link com.nike.riposte.server.error.handler.ErrorResponseInfo} that should be used to build the
     * response for the user, or null if this handler doesn't know how to deal with the error. If this returns null then
     * you should have {@link RiposteUnhandledErrorHandler} handle the error instead.
     *
     * @throws UnexpectedMajorErrorHandlingError
     *     This should never be thrown - if it is it indicates something major went wrong in the handler and is likely a
     *     bug that should be fixed.
     */
    @Nullable ErrorResponseInfo maybeHandleError(@NotNull Throwable error, @NotNull RequestInfo<?> requestInfo)
        throws UnexpectedMajorErrorHandlingError;

}
