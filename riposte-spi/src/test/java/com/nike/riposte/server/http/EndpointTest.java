package com.nike.riposte.server.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nike.riposte.util.Matcher;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link Endpoint}
 */
@RunWith(DataProviderRunner.class)
public class EndpointTest {

    @DataProvider(value = {
            "50000  |   false",
            "50001  |   true"
    }, splitBy = "\\|")
    @Test
    public void default_method_implementations_return_expected_values(int rawContentLengthBytes,
                                                                      boolean shouldValidateAsync) {
        // given
        Endpoint<?> defaultImpl = (Endpoint<Object>) () -> null;
        RequestInfo<?> reqMock = mock(RequestInfo.class);
        doReturn(rawContentLengthBytes).when(reqMock).getRawContentLengthInBytes();

        // expect
        assertThat(defaultImpl.isValidateRequestContent(null)).isTrue();
        assertThat(defaultImpl.validationGroups(null)).isNull();
        assertThat(defaultImpl.customRequestContentDeserializer(null)).isNull();
        assertThat(defaultImpl.customResponseContentSerializer(null)).isNull();
        assertThat(defaultImpl.requestContentType()).isNull();
        assertThat(defaultImpl.completableFutureTimeoutOverrideMillis()).isNull();
        assertThat(defaultImpl.shouldValidateAsynchronously(reqMock)).isEqualTo(shouldValidateAsync);
    }

    @DataProvider(value = {
            "null             | false",
            "java.lang.Void   | false",
            "java.lang.String | true"
    }, splitBy = "\\|")
    @Test
    public void isRequireRequestContent_returnsExpectedValueBasedOnRequestContentTypeValue(String inputType, boolean expectedValue) throws ClassNotFoundException {
        // given
        Class<?> type = inputType == null ? null : Class.forName(inputType);

        // when
        Endpoint<?> defaultImpl = getEndpointWithRequestContentType(type);

        // then
        assertThat(defaultImpl.isRequireRequestContent()).isEqualTo(expectedValue);
    }

    private Endpoint<?> getEndpointWithRequestContentType(Class<?> type) {
        return new Endpoint<Object>() {
            @Override
            public Matcher requestMatcher() {
                return null;
            }

            @Override
            public TypeReference<Object> requestContentType() {
                if (type == null) {
                    return null;
                } else {
                    return new TypeReference<Object>() {
                        @Override
                        public Type getType() {
                            return type;
                        }
                    };
                }
            }
        };
    }
}