package com.nike.riposte.util;

import com.nike.backstopper.model.util.JsonUtilWithDefaultErrorContractDTOSupport;
import com.nike.riposte.server.error.handler.ErrorResponseBodySerializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Helper class for error contract serializers.
 */
@SuppressWarnings("WeakerAccess")
public class ErrorContractSerializerHelper {

    public static final ObjectMapper SMART_ERROR_MAPPER = generateErrorContractObjectMapper(true, true);
    public static final ErrorResponseBodySerializer SMART_ERROR_SERIALIZER =
        asErrorResponseBodySerializer(SMART_ERROR_MAPPER);

    public static ObjectMapper generateErrorContractObjectMapper(boolean excludeEmptyMetadataFromJson,
                                                                 boolean serializeErrorCodeFieldAsIntegerIfPossible) {
        return JsonUtilWithDefaultErrorContractDTOSupport.generateErrorContractObjectMapper(
            excludeEmptyMetadataFromJson, serializeErrorCodeFieldAsIntegerIfPossible
        );
    }

    @SuppressWarnings("unused")
    public static ErrorResponseBodySerializer generateErrorContractSerializer(
        boolean excludeEmptyMetadataFromJson, boolean serializeErrorCodeFieldAsIntegerIfPossible
    ) {
        return asErrorResponseBodySerializer(
            generateErrorContractObjectMapper(excludeEmptyMetadataFromJson, serializeErrorCodeFieldAsIntegerIfPossible)
        );
    }

    public static ErrorResponseBodySerializer asErrorResponseBodySerializer(ObjectMapper objectMapper) {
        return errorResponseBody -> {
            try {
                return objectMapper.writeValueAsString(errorResponseBody);
            }
            catch (JsonProcessingException e) {
                throw new RuntimeException("An error occurred while serializing an ErrorResponseBody to a string", e);
            }
        };
    }
}
