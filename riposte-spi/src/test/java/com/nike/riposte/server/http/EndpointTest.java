package com.nike.riposte.server.http;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
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
        assertThat(defaultImpl.isValidateRequestContent(null), is(true));
        assertThat(defaultImpl.validationGroups(null), nullValue());
        assertThat(defaultImpl.customRequestContentDeserializer(null), nullValue());
        assertThat(defaultImpl.customResponseContentSerializer(null), nullValue());
        assertThat(defaultImpl.requestContentType(), nullValue());
        assertThat(defaultImpl.completableFutureTimeoutOverrideMillis(), nullValue());
        assertThat(defaultImpl.shouldValidateAsynchronously(reqMock), is(shouldValidateAsync));
    }

}