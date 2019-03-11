package com.nike.backstopper.model.riposte;

import com.nike.backstopper.handler.ErrorResponseInfo;
import com.nike.internal.util.MapBuilder;
import com.nike.riposte.server.error.handler.ErrorResponseBody;

import org.assertj.core.api.Assertions;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.catchThrowable;
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
        ErrorResponseInfoImpl adapter = new ErrorResponseInfoImpl(mock(ErrorResponseBody.class), 42, null);
        assertThat(adapter.headersToAddToResponse, notNullValue());
        assertThat(adapter.headersToAddToResponse.isEmpty(), is(true));
    }

    @Test
    public void kitchen_sink_constructor_throws_IllegalArgumentException_when_passed_null_ErrorResponseBody() {
        // when
        Throwable ex = catchThrowable(() -> new ErrorResponseInfoImpl(null, 400, Collections.emptyMap()));

        // then
        Assertions.assertThat(ex)
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("errorResponseBody cannot be null.");
    }

    @Test
    public void backstopper_copy_constructor_throws_NullPointerException_when_passed_null_arg() {
        // when
        Throwable ex = catchThrowable(() -> new ErrorResponseInfoImpl(null));

        // then
        Assertions.assertThat(ex).isInstanceOf(NullPointerException.class);
    }
}