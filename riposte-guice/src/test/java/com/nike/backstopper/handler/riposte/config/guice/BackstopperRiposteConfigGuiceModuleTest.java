package com.nike.backstopper.handler.riposte.config.guice;

import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.apierror.testutil.ProjectApiErrorsForTesting;
import com.nike.backstopper.handler.listener.ApiExceptionHandlerListener;
import com.nike.backstopper.handler.listener.impl.ClientDataValidationErrorHandlerListener;
import com.nike.backstopper.handler.listener.impl.DownstreamNetworkExceptionHandlerListener;
import com.nike.backstopper.handler.listener.impl.GenericApiExceptionHandlerListener;
import com.nike.backstopper.handler.listener.impl.ServersideValidationErrorHandlerListener;
import com.nike.backstopper.handler.riposte.RiposteApiExceptionHandler;
import com.nike.backstopper.handler.riposte.RiposteUnhandledExceptionHandler;
import com.nike.backstopper.handler.riposte.listener.impl.BackstopperRiposteFrameworkErrorHandlerListener;
import com.nike.backstopper.service.riposte.BackstopperRiposteValidatorAdapter;
import com.nike.riposte.server.error.handler.RiposteErrorHandler;
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler;
import com.nike.riposte.server.error.validation.RequestValidator;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.validation.Validator;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit test for {@link BackstopperRiposteConfigGuiceModule}
 */
public class BackstopperRiposteConfigGuiceModuleTest {

    private static final AbstractModule miscellaneousDependenciesModule = new AbstractModule() {
        @Override
        protected void configure() {
            bind(ProjectApiErrors.class).toInstance(ProjectApiErrorsForTesting.withProjectSpecificData(null, null));
            bind(Validator.class).toInstance(mock(Validator.class));
        }
    };
    private static final BackstopperRiposteConfigGuiceModule module = new BackstopperRiposteConfigGuiceModule();
    private static final Injector injector = Guice.createInjector(module, miscellaneousDependenciesModule);

    private static final class InjectMe {
        @Inject
        public RiposteErrorHandler errorHandler;

        @Inject
        public RiposteUnhandledErrorHandler unhandledErrorHandler;

        @Inject
        public RequestValidator requestValidator;

        @Inject
        public List<ApiExceptionHandlerListener> listeners;
    }

    private InjectMe injectedObj;

    @Before
    public void beforeMethod() {
        injectedObj = new InjectMe();
        injector.injectMembers(injectedObj);
    }

    @Test
    public void riposteErrorHandlerIsBoundToAdapter() {
        assertThat(injectedObj.errorHandler, notNullValue());
        assertThat(injectedObj.errorHandler, instanceOf(RiposteApiExceptionHandler.class));
    }

    @Test
    public void riposteUnhandledErrorHandlerIsBoundToAdapter() {
        assertThat(injectedObj.unhandledErrorHandler, notNullValue());
        assertThat(injectedObj.unhandledErrorHandler, instanceOf(RiposteUnhandledExceptionHandler.class));
    }

    @Test
    public void requestValidatorIsBoundToAdapter() {
        assertThat(injectedObj.requestValidator, notNullValue());
        assertThat(injectedObj.requestValidator, instanceOf(BackstopperRiposteValidatorAdapter.class));
    }

    @Test
    public void apiExceptionHandlerListenersHasExpectedValues() {
        assertThat(injectedObj.listeners, notNullValue());
        List<Class<?>> expectedListeners = Arrays.asList(
                GenericApiExceptionHandlerListener.class,
                ServersideValidationErrorHandlerListener.class,
                ClientDataValidationErrorHandlerListener.class,
                DownstreamNetworkExceptionHandlerListener.class,
                BackstopperRiposteFrameworkErrorHandlerListener.class);
        List<Class<?>> actualListeners = injectedObj.listeners.stream().map(listener -> listener.getClass()).collect(Collectors.toList());
        assertThat(actualListeners, is(expectedListeners));
    }

}