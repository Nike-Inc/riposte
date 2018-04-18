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
                if (errorResponseBody == null || errorResponseBody.bodyToSerialize() == null) {
                    // errorResponseBody itself is null, or errorResponseBody.bodyToSerialize() is null. Either case
                    //      indicates empty response body payload, so we should return null.
                    return null;
                }
                return objectMapper.writeValueAsString(errorResponseBody.bodyToSerialize());
            }
            catch (JsonProcessingException e) {
                throw new RuntimeException("An error occurred while serializing an ErrorResponseBody to a string", e);
            }
        };
    }
}
