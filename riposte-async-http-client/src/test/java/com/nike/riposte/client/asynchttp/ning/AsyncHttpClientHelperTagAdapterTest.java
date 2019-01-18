package com.nike.riposte.client.asynchttp.ning;

import com.ning.http.client.Response;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link AsyncHttpClientHelperTagAdapter}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class AsyncHttpClientHelperTagAdapterTest {

    private AsyncHttpClientHelperTagAdapter adapterSpy;
    private RequestBuilderWrapper requestMock;
    private Response responseMock;

    @Before
    public void setup() {
        adapterSpy = spy(new AsyncHttpClientHelperTagAdapter());
        requestMock = mock(RequestBuilderWrapper.class);
        responseMock = mock(Response.class);
    }

    @Test
    public void getDefaultInstance_returns_DEFAULT_INSTANCE() {
        // expect
        assertThat(AsyncHttpClientHelperTagAdapter.getDefaultInstance())
            .isSameAs(AsyncHttpClientHelperTagAdapter.DEFAULT_INSTANCE);
    }

    @Test
    public void getRequestUrl_works_as_expected() {
        // given
        String expectedResult = UUID.randomUUID().toString();

        doReturn(expectedResult).when(requestMock).getUrl();

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
        // Basic HTTP URIs
        "http://foo.bar/some/path                       |   /some/path",
        "http://foo.bar/                                |   /",

        "http://foo.bar:4242/some/path                  |   /some/path",
        "http://foo.bar:4242/                           |   /",

        // Same thing, but for HTTPS
        "https://foo.bar/some/path                      |   /some/path",
        "https://foo.bar/                               |   /",

        "https://foo.bar:4242/some/path                 |   /some/path",
        "https://foo.bar:4242/                          |   /",

        // Basic HTTP URIs with query string
        "http://foo.bar/some/path?thing=stuff           |   /some/path",
        "http://foo.bar/?thing=stuff                    |   /",

        "http://foo.bar:4242/some/path?thing=stuff      |   /some/path",
        "http://foo.bar:4242/?thing=stuff               |   /",

        // Same thing, but for HTTPS (with query string)
        "https://foo.bar/some/path?thing=stuff          |   /some/path",
        "https://foo.bar/?thing=stuff                   |   /",

        "https://foo.bar:4242/some/path?thing=stuff     |   /some/path",
        "https://foo.bar:4242/?thing=stuff              |   /",

        // URIs missing path
        "http://no.real.path                            |   /",
        "https://no.real.path                           |   /",
        "http://no.real.path?thing=stuff                |   /",
        "https://no.real.path?thing=stuff               |   /",

        // URIs missing scheme and host - just path
        "/some/path                                     |   /some/path",
        "/some/path?thing=stuff                         |   /some/path",
        "/                                              |   /",
        "/?thing=stuff                                  |   /",

        // Broken URIs
        "nothttp://foo.bar/some/path                    |   null",
        "missing/leading/slash                          |   null",
        "http//missing.scheme.colon/some/path           |   null",
        "http:/missing.scheme.double.slash/some/path    |   null",
    }, splitBy = "\\|")
    @Test
    public void getRequestPath_works_as_expected(String url, String expectedResult) {
        // given
        doReturn(url).when(requestMock).getUrl();

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
        verifyZeroInteractions(requestMock, responseMock);
    }

    @Test
    public void getResponseHttpStatus_works_as_expected() {
        // given
        Integer expectedResult = 42;
        doReturn(expectedResult).when(responseMock).getStatusCode();

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
        String expectedResult = UUID.randomUUID().toString();
        doReturn(expectedResult).when(requestMock).getHttpMethod();

        // when
        String result = adapterSpy.getRequestHttpMethod(requestMock);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void getRequestHttpMethod_returns_null_if_passed_null() {
        // expect
        assertThat(adapterSpy.getRequestHttpMethod(null)).isNull();
    }

    @Test
    public void getHeaderSingleValue_returns_null() {
        // when
        String result = adapterSpy.getHeaderSingleValue(requestMock, "foo");

        // then
        assertThat(result).isNull();
        verifyZeroInteractions(requestMock);
    }

    @Test
    public void getHeaderMultipleValue_returns_null() {
        // when
        List<String> result = adapterSpy.getHeaderMultipleValue(requestMock, "foo");

        // then
        assertThat(result).isNull();
        verifyZeroInteractions(requestMock);
    }

    @Test
    public void getSpanHandlerTagValue_returns_expected_value() {
        // expect
        assertThat(adapterSpy.getSpanHandlerTagValue(requestMock, responseMock))
            .isEqualTo("riposte.ningasynchttpclienthelper");
    }
}