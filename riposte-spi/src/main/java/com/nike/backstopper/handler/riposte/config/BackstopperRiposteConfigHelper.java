package com.nike.backstopper.handler.riposte.config;

import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.handler.ApiExceptionHandlerUtils;
import com.nike.backstopper.handler.listener.ApiExceptionHandlerListener;
import com.nike.backstopper.handler.listener.impl.ClientDataValidationErrorHandlerListener;
import com.nike.backstopper.handler.listener.impl.DownstreamNetworkExceptionHandlerListener;
import com.nike.backstopper.handler.listener.impl.GenericApiExceptionHandlerListener;
import com.nike.backstopper.handler.listener.impl.ServersideValidationErrorHandlerListener;
import com.nike.backstopper.handler.riposte.RiposteApiExceptionHandler;
import com.nike.backstopper.handler.riposte.RiposteUnhandledExceptionHandler;
import com.nike.backstopper.handler.riposte.listener.impl.BackstopperRiposteFrameworkErrorHandlerListener;
import com.nike.riposte.server.error.handler.RiposteErrorHandler;
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * Provides some static helper methods for generating a {@link RiposteErrorHandler} and {@link
 * RiposteUnhandledErrorHandler} with reasonable defaults that are likely to meet most applications' needs.
 *
 * @author Nic Munroe
 */
public class BackstopperRiposteConfigHelper {

    // Intentionally protected - use the static methods.
    @SuppressWarnings("WeakerAccess")
    protected BackstopperRiposteConfigHelper() { /* do nothing */ }

    /**
     * Returns a {@link RiposteErrorHandler} that uses the given {@link ProjectApiErrors}, and {@link
     * #defaultHandlerListeners(ProjectApiErrors, ApiExceptionHandlerUtils)} for the error handler listeners.
     */
    public static @NotNull RiposteErrorHandler defaultErrorHandler(
        @NotNull ProjectApiErrors projectApiErrors,
        @NotNull ApiExceptionHandlerUtils utils
    ) {
        return new RiposteApiExceptionHandler(
            projectApiErrors,
            defaultHandlerListeners(projectApiErrors, utils),
            utils
        );
    }

    /**
     * Returns a {@link RiposteUnhandledErrorHandler} that uses the given {@link ProjectApiErrors}.
     */
    public static @NotNull RiposteUnhandledErrorHandler defaultUnhandledErrorHandler(
        @NotNull ProjectApiErrors projectApiErrors,
        @NotNull ApiExceptionHandlerUtils utils
    ) {
        return new RiposteUnhandledExceptionHandler(projectApiErrors, utils);
    }

    /**
     * Returns the default list of {@link ApiExceptionHandlerListener}s that should work for most applications without
     * any further additions.
     */
    public static @NotNull List<ApiExceptionHandlerListener> defaultHandlerListeners(
        @NotNull ProjectApiErrors projectApiErrors,
        @NotNull ApiExceptionHandlerUtils utils
    ) {
        return Arrays.asList(new GenericApiExceptionHandlerListener(),
                             new ServersideValidationErrorHandlerListener(projectApiErrors, utils),
                             new ClientDataValidationErrorHandlerListener(projectApiErrors, utils),
                             new DownstreamNetworkExceptionHandlerListener(projectApiErrors),
                             new BackstopperRiposteFrameworkErrorHandlerListener(projectApiErrors));
    }

}
