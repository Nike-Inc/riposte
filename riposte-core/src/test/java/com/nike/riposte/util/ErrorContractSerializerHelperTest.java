package com.nike.riposte.util;

import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.error.handler.ErrorResponseBodySerializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.function.Supplier;

import static com.nike.riposte.util.ErrorContractSerializerHelper.asErrorResponseBodySerializer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link ErrorContractSerializerHelper}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class ErrorContractSerializerHelperTest {

    private enum ErrorResponseBodyScenario {
        NULL_INSTANCE(() -> null),
        NULL_BODY_TO_SERIALIZE(() -> new ErrorResponseBody() {
            @Override
            public String errorId() {
                return null;
            }

            @Override
            public Object bodyToSerialize() {
                return null;
            }
        });

        private final Supplier<ErrorResponseBody> generator;

        ErrorResponseBodyScenario(
            Supplier<ErrorResponseBody> generator) {
            this.generator = generator;
        }

        public ErrorResponseBody generateErrorResponseBody() {
            return generator.get();
        }
    }

    @DataProvider(value = {
        "NULL_INSTANCE",
        "NULL_BODY_TO_SERIALIZE"
    })
    @Test
    public void asErrorResponseBodySerializer_returns_serializer_that_returns_null_when_it_is_supposed_to(
        ErrorResponseBodyScenario scenario
    ) {
        // given
        ErrorResponseBody errorResponseBody = scenario.generateErrorResponseBody();
        ObjectMapper objectMapperMock = mock(ObjectMapper.class);
        ErrorResponseBodySerializer serializer = asErrorResponseBodySerializer(objectMapperMock);

        // when
        String result = serializer.serializeErrorResponseBodyToString(errorResponseBody);

        // then
        assertThat(result).isNull();
        verifyZeroInteractions(objectMapperMock);
    }

    @Test
    public void asErrorResponseBodySerializer_returns_serializer_that_serializes_as_expected(
    ) throws JsonProcessingException {
        // given
        ObjectMapper objectMapperMock = mock(ObjectMapper.class);
        ErrorResponseBodySerializer serializer = asErrorResponseBodySerializer(objectMapperMock);

        String objectMapperResult = UUID.randomUUID().toString();
        doReturn(objectMapperResult).when(objectMapperMock).writeValueAsString(anyObject());

        Object objectToSerialize = new Object();
        ErrorResponseBody errorResponseBodyMock = mock(ErrorResponseBody.class);
        doReturn(objectToSerialize).when(errorResponseBodyMock).bodyToSerialize();

        // when
        String result = serializer.serializeErrorResponseBodyToString(errorResponseBodyMock);

        // then
        assertThat(result).isEqualTo(objectMapperResult);
        verify(objectMapperMock).writeValueAsString(objectToSerialize);
    }

    @Test
    public void asErrorResponseBodySerializer_returns_serializer_that_propagates_JsonProcessingException_as_RuntimeException(
    ) throws JsonProcessingException {
        // given
        ObjectMapper objectMapperMock = mock(ObjectMapper.class);
        ErrorResponseBodySerializer serializer = asErrorResponseBodySerializer(objectMapperMock);

        ErrorResponseBody errorResponseBodyMock = mock(ErrorResponseBody.class);
        doReturn(new Object()).when(errorResponseBodyMock).bodyToSerialize();

        JsonProcessingException jsonProcessingExceptionMock = mock(JsonProcessingException.class);
        doThrow(jsonProcessingExceptionMock).when(objectMapperMock).writeValueAsString(anyObject());

        // when
        Throwable ex = catchThrowable(() -> serializer.serializeErrorResponseBodyToString(errorResponseBodyMock));

        // then
        assertThat(ex)
            .isNotNull()
            .isExactlyInstanceOf(RuntimeException.class)
            .hasCause(jsonProcessingExceptionMock);
    }

}