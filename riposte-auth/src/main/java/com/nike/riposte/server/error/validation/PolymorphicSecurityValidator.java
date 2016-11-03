package com.nike.riposte.server.error.validation;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.error.exception.Unauthorized401Exception;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RequestSecurityValidator} that allows you to use multiple types of security validators simultaneously for a
 * server/endpoint.
 */
@SuppressWarnings("WeakerAccess")
public class PolymorphicSecurityValidator implements RequestSecurityValidator {

    protected Map<Endpoint<?>, List<RequestSecurityValidator>> validationMap;
    protected final boolean isFastEnoughToRunOnNettyWorkerThread;

    /**
     * @param validators
     *     list of validators in use. Be aware that this {@link PolymorphicSecurityValidator} will use these validators
     *     in list order attempting to perform validation. This means that you should probably have the validator that
     *     is most common or lowest overhead first in the list.
     */
    public PolymorphicSecurityValidator(List<RequestSecurityValidator> validators) {
        this.validationMap = buildValidationMap(validators);
        boolean containsSlowValidator =
            (validators != null) && validators.stream().anyMatch(rsv -> !rsv.isFastEnoughToRunOnNettyWorkerThread());
        isFastEnoughToRunOnNettyWorkerThread = !containsSlowValidator;
    }

    protected Map<Endpoint<?>, List<RequestSecurityValidator>> buildValidationMap(
        List<RequestSecurityValidator> validators) {
        Map<Endpoint<?>, List<RequestSecurityValidator>> map = new HashMap<>();
        if (validators == null) {
            return map;
        }
        for (RequestSecurityValidator validator : validators) {
            for (Endpoint<?> endpoint : validator.endpointsToValidate()) {
                if (!map.containsKey(endpoint)) {
                    map.put(endpoint, new ArrayList<>());
                }
                map.get(endpoint).add(validator);
            }
        }
        return map;
    }


    /**
     * Performs security validation on the given request {@link RequestInfo} with possibly multiple validations.
     *
     * If an endpoint has multiple validators associated with it, it must pass validation with at least one of the
     * validators.
     */
    @Override
    public void validateSecureRequestForEndpoint(RequestInfo<?> requestInfo, Endpoint<?> endpoint) {
        List<RequestSecurityValidator> validators = validationMap.get(endpoint);
        if (validators == null || validators.isEmpty()) {
            // if there are no validators for the endpoint, we don't need to validate
            return;
        }
        StringBuilder errorMessages = new StringBuilder("Request failed all auth validation:");
        List<Pair<String, String>> extraDetails = new ArrayList<>();
        for (RequestSecurityValidator validator : validators) {
            try {
                validator.validateSecureRequestForEndpoint(requestInfo, endpoint);
                return;
            }
            catch (Unauthorized401Exception ex) {
                // move on to the next validator
                errorMessages.append(validator.getClass().getSimpleName()).append(": ").append(ex.getMessage())
                             .append(";");
                extraDetails.addAll(ex.extraDetailsForLogging);
            }
        }
        throw new Unauthorized401Exception(errorMessages.toString(), requestInfo.getPath(), null, extraDetails);
    }

    @Override
    public Collection<Endpoint<?>> endpointsToValidate() {
        return validationMap.keySet();
    }

    @Override
    public boolean isFastEnoughToRunOnNettyWorkerThread() {
        return isFastEnoughToRunOnNettyWorkerThread;
    }
}
