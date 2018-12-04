package com.nike.trace.netty;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link RiposteWingtipsServerTagAdapter}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class RiposteWingtipsServerTagAdapterTest {

    private RiposteWingtipsServerTagAdapter adapterSpy;
    private RequestInfo<?> requestMock;
    private ResponseInfo<?> responseMock;
    private HttpHeaders requestHeadersMock;

    @Before
    public void setup() {
        adapterSpy = spy(new RiposteWingtipsServerTagAdapter());
        requestMock = mock(RequestInfo.class);
        responseMock = mock(ResponseInfo.class);
        requestHeadersMock = mock(HttpHeaders.class);

        doReturn(requestHeadersMock).when(requestMock).getHeaders();
    }

    @Test
    public void getDefaultInstance_returns_DEFAULT_INSTANCE() {
        // expect
        assertThat(RiposteWingtipsServerTagAdapter.getDefaultInstance())
            .isSameAs(RiposteWingtipsServerTagAdapter.DEFAULT_INSTANCE);
    }

    @DataProvider(value = {
        "null   |   null",
        "200    |   null",
        "300    |   null",
        "400    |   null",
        "499    |   null",
        "500    |   500",
        "599    |   599",
        "999    |   999"
    }, splitBy = "\\|")
    @Test
    public void getErrorResponseTagValue_works_as_expected(Integer statusCode, String expectedTagValue) {
        // given
        doReturn(statusCode).when(adapterSpy).getResponseHttpStatus(any(ResponseInfo.class));

        // when
        String result = adapterSpy.getErrorResponseTagValue(responseMock);

        // then
        assertThat(result).isEqualTo(expectedTagValue);
        verify(adapterSpy).getErrorResponseTagValue(responseMock);
        verify(adapterSpy).getResponseHttpStatus(responseMock);
        verifyNoMoreInteractions(adapterSpy);
    }

    @Test
    public void getRequestUrl_works_as_expected() {
        // given
        String expectedResult = UUID.randomUUID().toString();

        doReturn(expectedResult).when(requestMock).getUri();

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

    @Test
    public void getRequestPath_works_as_expected() {
        // given
        String expectedResult = UUID.randomUUID().toString();
        doReturn(expectedResult).when(requestMock).getPath();

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

    @DataProvider(value = {
        "/some/path/{tmplt} |   /some/path/{tmplt}",
        "null               |   null",
        "                   |   null",
        "[whitespace]       |   null",
    }, splitBy = "\\|")
    @Test
    public void getRequestUriPathTemplate_works_as_expected(
        String requestPathTemplate,
        String expectedResult
    ) {
        // given
        if ("[whitespace]".equals(requestPathTemplate)) {
            requestPathTemplate = "  \t\r\n  ";
        }

        doReturn(requestPathTemplate).when(requestMock).getPathTemplate();

        // when
        String result = adapterSpy.getRequestUriPathTemplate(requestMock, responseMock);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void getRequestUriPathTemplate_returns_null_when_passed_null_request() {
        // when
        String result = adapterSpy.getRequestUriPathTemplate(null, responseMock);

        // then
        assertThat(result).isNull();
    }

    @Test
    public void getResponseHttpStatus_works_as_expected() {
        // given
        Integer expectedResult = 42;
        doReturn(expectedResult).when(responseMock).getHttpStatusCode();

        // when
        Integer result = adapterSpy.getResponseHttpStatus(responseMock);

        // then
        assertThat(result).isEqualTo(expectedResult);
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
        doReturn(expectedResult).when(requestMock).getMethod();

        // when
        String result = adapterSpy.getRequestHttpMethod(requestMock);

        // then
        assertThat(result).isEqualTo(expectedResult.name());
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
    public void getHeaderMultipleValue_returns_null_if_passed_null_request() {
        // expect
        assertThat(adapterSpy.getHeaderMultipleValue(null, "foo")).isNull();
    }

    @Test
    public void getSpanHandlerTagValue_returns_expected_value() {
        // expect
        assertThat(adapterSpy.getSpanHandlerTagValue(requestMock, responseMock)).isEqualTo("riposte.server");
    }
}