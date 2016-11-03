package com.nike.riposte.server.error.validation;

import com.nike.backstopper.service.riposte.BackstopperRiposteValidatorAdapter;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;

/**
 * Interface for a request validator. Concrete implementations might perform JSR 303 validation, or custom validation
 * based on specific object types, or anything else your application needs. If an endpoint wants the request content
 * deserialized before it's called *and* your endpoint's {@link Endpoint#isValidateRequestContent(RequestInfo)}
 * method returns true, then the concrete implementation of this interface that you registered with your server will be
 * called.
 * <p/>
 * You can create your own instance of this class, however it's highly recommended that you just use the prebuilt {@link
 * BackstopperRiposteValidatorAdapter} class which is part of the default error handling and validation system that is
 * designed to make error handling and validation easy and is based on Backstopper. The {@link
 * BackstopperRiposteValidatorAdapter} implementation is based on JSR 303 (a.k.a. Java Bean Validation) and is linked
 * nicely with the Backstopper system to make it trivially easy to tie validation errors to error responses that conform
 * to a consistent error contract.
 *
 * @author Nic Munroe
 */
public interface RequestValidator {

    /**
     * Performs default validation on the given request's {@link RequestInfo#getContent()}. If this implementation uses
     * JSR 303 validation then this method call indicates using the default group.
     */
    void validateRequestContent(RequestInfo<?> request);

    /**
     * Performs validation on the given request's {@link RequestInfo#getContent()} using the given validation groups. If
     * this implementation uses JSR 303 validation then this method call indicates using the given groups with the
     * {@code javax.validation.Validator}.
     */
    void validateRequestContent(RequestInfo<?> request, Class<?>... validationGroups);

}
