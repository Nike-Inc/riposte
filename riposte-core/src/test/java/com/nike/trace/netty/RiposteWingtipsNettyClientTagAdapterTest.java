package com.nike.trace.netty;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link RiposteWingtipsNettyClientTagAdapter}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class RiposteWingtipsNettyClientTagAdapterTest {

    private RiposteWingtipsNettyClientTagAdapter adapterSpy;
    private HttpRequest requestMock;
    private HttpResponse responseMock;
    private HttpHeaders requestHeadersMock;

    @Before
    public void setup() {
        adapterSpy = spy(new RiposteWingtipsNettyClientTagAdapter());
        requestMock = mock(HttpRequest.class);
        responseMock = mock(HttpResponse.class);
        requestHeadersMock = mock(HttpHeaders.class);

        doReturn(requestHeadersMock).when(requestMock).headers();
    }

    @Test
    public void getDefaultInstance_returns_DEFAULT_INSTANCE() {
        // expect
        assertThat(RiposteWingtipsNettyClientTagAdapter.getDefaultInstance())
            .isSameAs(RiposteWingtipsNettyClientTagAdapter.DEFAULT_INSTANCE);
    }

    @Test
    public void getRequestUrl_works_as_expected() {
        // given
        String expectedResult = UUID.randomUUID().toString();

        doReturn(expectedResult).when(requestMock).uri();

        // when
        String result = adapterSpy.getRequestUrl(requestMock);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void getRequestUrl_returns_null_if_passed_null() {
        // expect
        assertThat(adapterSpy.getRequestUrl(null)).isNull();
    }

    @DataProvider(value = {
        "/foo/bar?someQuery=stringBlah  |   /foo/bar",
        "/foo/bar/?someQuery=stringBlah |   /foo/bar/",
        "/foo/bar                       |   /foo/bar",
        "/foo/bar/                      |   /foo/bar/",
        "foobar                         |   foobar",
        "null                           |   null",
        "                               |   null",
        "[whitespace]                   |   null"
    }, splitBy = "\\|")
    @Test
    public void getRequestPath_works_as_expected(String uri, String expectedResult) {
        // given
        if ("[whitespace]".equals(uri)) {
            uri = "  \r\n\t  ";
        }
        doReturn(uri).when(requestMock).uri();

        // when
        String result = adapterSpy.getRequestPath(requestMock);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void getRequestPath_returns_null_if_passed_null() {
        // expect
        assertThat(adapterSpy.getRequestPath(null)).isNull();
    }

    @Test
    public void getRequestUriPathTemplate_returns_null() {
        // when
        String result = adapterSpy.getRequestUriPathTemplate(requestMock, responseMock);

        // then
        assertThat(result).isNull();
    }

    @Test
    public void getResponseHttpStatus_works_as_expected() {
        // given
        int expectedResult = 42;
        HttpResponseStatus responseStatusObj = HttpResponseStatus.valueOf(expectedResult);
        doReturn(responseStatusObj).when(responseMock).status();

        // when
        Integer result = adapterSpy.getResponseHttpStatus(responseMock);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void getResponseHttpStatus_returns_null_if_HttpResponseStatus_obj_is_null() {
        // given
        doReturn(null).when(responseMock).status();

        // when
        Integer result = adapterSpy.getResponseHttpStatus(responseMock);

        // then
        assertThat(result).isNull();
    }

    @Test
    public void getResponseHttpStatus_returns_null_if_passed_null() {
        // expect
        assertThat(adapterSpy.getResponseHttpStatus(null)).isNull();
    }

    @Test
    public void getRequestHttpMethod_works_as_expected() {
        // given
        HttpMethod expectedResult = HttpMethod.valueOf(UUID.randomUUID().toString());
        doReturn(expectedResult).when(requestMock).method();

        // when
        String result = adapterSpy.getRequestHttpMethod(requestMock);

        // then
        assertThat(result).isEqualTo(expectedResult.name());
    }

    @Test
    public void getRequestHttpMethod_returns_null_if_HttpMethod_obj_is_null() {
        // given
        doReturn(null).when(requestMock).method();

        // when
        String result = adapterSpy.getRequestHttpMethod(requestMock);

        // then
        assertThat(result).isNull();
    }

    @Test
    public void getRequestHttpMethod_returns_null_if_passed_null() {
        // expect
        assertThat(adapterSpy.getRequestHttpMethod(null)).isNull();
    }

    @Test
    public void getHeaderSingleValue_works_as_expected() {
        // given
        String headerKey = UUID.randomUUID().toString();

        String expectedResult = UUID.randomUUID().toString();
        doReturn(expectedResult).when(requestHeadersMock).get(anyString());

        // when
        String result = adapterSpy.getHeaderSingleValue(requestMock, headerKey);

        // then
        assertThat(result).isEqualTo(expectedResult);
        verify(requestHeadersMock).get(headerKey);
    }

    @Test
    public void getHeaderSingleValue_returns_null_if_HttpHeaders_obj_is_null() {
        // given
        doReturn(null).when(requestMock).headers();

        // when
        String result = adapterSpy.getHeaderSingleValue(requestMock, "foo");

        // then
        assertThat(result).isNull();
    }

    @Test
    public void getHeaderSingleValue_returns_null_if_passed_null_request() {
        // expect
        assertThat(adapterSpy.getHeaderSingleValue(null, "foo")).isNull();
    }

    @Test
    public void getHeaderMultipleValue_works_as_expected() {
        // given
        String headerKey = UUID.randomUUID().toString();

        List<String> expectedResult = Arrays.asList(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        doReturn(expectedResult).when(requestHeadersMock).getAll(anyString());

        // when
        List<String> result = adapterSpy.getHeaderMultipleValue(requestMock, headerKey);

        // then
        assertThat(result).isEqualTo(expectedResult);
        verify(requestHeadersMock).getAll(headerKey);
    }

    @Test
    public void getHeaderMultipleValue_returns_null_if_HttpHeaders_obj_is_null() {
        // given
        doReturn(null).when(requestMock).headers();

        // when
        List<String> result = adapterSpy.getHeaderMultipleValue(requestMock, "foo");

        // then
        assertThat(result).isNull();
    }

    @Test
    public void getHeaderMultipleValue_returns_null_if_passed_null_request() {
        // expect
        assertThat(adapterSpy.getHeaderMultipleValue(null, "foo")).isNull();
    }

    @Test
    public void getSpanHandlerTagValue_returns_expected_value() {
        // expect
        assertThat(adapterSpy.getSpanHandlerTagValue(requestMock, responseMock)).isEqualTo("netty.httpclient");
    }
}