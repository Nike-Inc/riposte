package com.nike.backstopper.model.riposte;

import com.nike.backstopper.handler.ErrorResponseInfo;
import com.nike.internal.util.MapBuilder;
import com.nike.riposte.server.error.handler.ErrorResponseBody;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit test for {@link ErrorResponseInfoImpl}
 */
public class ErrorResponseInfoImplTest {

    @Test
    public void constructorWithIndividualArgsSetsValues() {
        ErrorResponseBody bodyMock = mock(ErrorResponseBody.class);
        int httpStatusCode = 42;
        Map<String, List<String>> headers = MapBuilder.<String, List<String>>builder().put("someHeader", Arrays.asList("hval1", "hval2")).build();
        ErrorResponseInfoImpl adapter = new ErrorResponseInfoImpl(bodyMock, httpStatusCode, headers);
        assertThat(adapter.errorResponseBody, sameInstance(bodyMock));
        assertThat(adapter.httpStatusCode, is(httpStatusCode));
        assertThat(adapter.headersToAddToResponse, is(headers));
    }

    @Test
    public void constructorWithErrorResponseInfoArgsSetsValues() {
        ErrorResponseBody bodyMock = mock(ErrorResponseBody.class);
        int httpStatusCode = 42;
        Map<String, List<String>> headers = MapBuilder.<String, List<String>>builder().put("someHeader", Arrays.asList("hval1", "hval2")).build();
        ErrorResponseInfo<ErrorResponseBody> backstopperErrorResponseInfo = new ErrorResponseInfo<>(httpStatusCode, bodyMock, headers);
        ErrorResponseInfoImpl adapter = new ErrorResponseInfoImpl(backstopperErrorResponseInfo);
        assertThat(adapter.errorResponseBody, sameInstance(bodyMock));
        assertThat(adapter.httpStatusCode, is(httpStatusCode));
        assertThat(adapter.headersToAddToResponse, is(headers));
    }

    @Test
    public void constructorDefaultsToEmptyHeadersMapIfPassedNull() {
        ErrorResponseInfoImpl adapter = new ErrorResponseInfoImpl(null, 42, null);
        assertThat(adapter.headersToAddToResponse, notNullValue());
        assertThat(adapter.headersToAddToResponse.isEmpty(), is(true));
    }
}