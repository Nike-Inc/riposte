package com.nike.trace.netty;

import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link RequestWithHeadersNettyAdapter}.
 *
 * @author Nic Munroe
 */
public class RequestWithHeadersNettyAdapterTest {

    private HttpHeaders headersMock;
    private HttpRequest requestMock;
    private RequestWithHeadersNettyAdapter adapter;

    @Before
    public void beforeMethod() {
        headersMock = mock(HttpHeaders.class);
        requestMock = mock(HttpRequest.class);

        doReturn(headersMock).when(requestMock).headers();

        adapter = new RequestWithHeadersNettyAdapter(requestMock);
    }

    @Test
    public void constructor_sets_fields_as_expected() {
        // when
        RequestWithHeadersNettyAdapter instance = new RequestWithHeadersNettyAdapter(requestMock);

        // then
        assertThat(instance.httpRequest).isSameAs(requestMock);
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_passed_null() {
        // when
        Throwable ex = catchThrowable(() -> new RequestWithHeadersNettyAdapter(null));

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void getHeader_delegates_to_httpRequest() {
        // given
        String headerKey = UUID.randomUUID().toString();
        String headerVal = UUID.randomUUID().toString();
        doReturn(headerVal).when(headersMock).get(headerKey);

        // when
        String result = adapter.getHeader(headerKey);

        // then
        verify(headersMock).get(headerKey);
        assertThat(result).isEqualTo(headerVal);
    }

    @Test
    public void getHeader_returns_null_if_request_headers_is_null() {
        // given
        String headerKey = UUID.randomUUID().toString();
        String headerVal = UUID.randomUUID().toString();
        doReturn(headerVal).when(headersMock).get(headerKey);
        doReturn(null).when(requestMock).headers();

        // when
        String result = adapter.getHeader("foo");

        // then
        verifyZeroInteractions(headersMock);
        assertThat(result).isNull();
    }

    @Test
    public void getAttribute_returns_null() {
        // expect
        assertThat(adapter.getAttribute("foo")).isNull();
    }

}