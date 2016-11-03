package com.nike.backstopper.service.riposte;

import com.nike.backstopper.service.ClientDataValidationService;
import com.nike.riposte.server.error.validation.RequestValidator;
import com.nike.riposte.server.http.RequestInfo;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Adapter that lets {@link ClientDataValidationService} act as a {@link RequestValidator}.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
@Singleton
public class BackstopperRiposteValidatorAdapter implements RequestValidator {

    protected final ClientDataValidationService clientDataValidationService;

    @Inject
    public BackstopperRiposteValidatorAdapter(ClientDataValidationService clientDataValidationService) {
        this.clientDataValidationService = clientDataValidationService;
    }

    @Override
    public void validateRequestContent(RequestInfo<?> request) {
        clientDataValidationService.validateObjectsFailFast(request.getContent());
    }

    @Override
    public void validateRequestContent(RequestInfo<?> request, Class<?>... validationGroups) {
        clientDataValidationService.validateObjectsWithGroupsFailFast(validationGroups, request.getContent());
    }
}
