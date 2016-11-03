package com.nike.backstopper.handler.riposte.config.guice;

import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.handler.ApiExceptionHandlerUtils;
import com.nike.backstopper.handler.listener.ApiExceptionHandlerListener;
import com.nike.backstopper.handler.riposte.RiposteApiExceptionHandler;
import com.nike.backstopper.handler.riposte.RiposteUnhandledExceptionHandler;
import com.nike.backstopper.handler.riposte.config.BackstopperRiposteConfigHelper;
import com.nike.backstopper.service.riposte.BackstopperRiposteValidatorAdapter;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.error.handler.RiposteErrorHandler;
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler;
import com.nike.riposte.server.error.validation.RequestValidator;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;

import java.util.List;

import javax.inject.Singleton;

/**
 * Wires the Backstopper error handling and validation system into a Riposte based project. Just make sure this Guice
 * module is used to generate the {@link ServerConfig#riposteErrorHandler()}, {@link
 * ServerConfig#riposteUnhandledErrorHandler()}, and {@link ServerConfig#requestContentValidationService()} for the
 * {@link ServerConfig} used by your application's {@link com.nike.riposte.server.Server}).
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class BackstopperRiposteConfigGuiceModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(RiposteErrorHandler.class).to(RiposteApiExceptionHandler.class);
        bind(RiposteUnhandledErrorHandler.class).to(RiposteUnhandledExceptionHandler.class);
        bind(RequestValidator.class).to(BackstopperRiposteValidatorAdapter.class);
    }

    /**
     * @return The basic set of handler listeners that are appropriate for most Riposte applications.
     */
    @Provides
    @Singleton
    @SuppressWarnings("unused")
    public List<ApiExceptionHandlerListener> apiExceptionHandlerListeners(ProjectApiErrors projectApiErrors,
                                                                          ApiExceptionHandlerUtils utils) {
        return BackstopperRiposteConfigHelper.defaultHandlerListeners(projectApiErrors, utils);
    }

}
