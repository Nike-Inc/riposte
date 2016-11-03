package com.nike.riposte.server.http.servletfakery;

import com.nike.riposte.server.http.RequestInfo;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.servlet.http.Cookie;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.DefaultCookie;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link HttpServletRequestWrapperForRequestInfo}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class HttpServletRequestWrapperForRequestInfoTest {

    private HttpServletRequestWrapperForRequestInfo<?> wrapper;
    private RequestInfo<?> requestInfoMock;
    private HttpHeaders headers;
    private Map<String, Object> attributes;
    private boolean isSsl;

    @Before
    public void beforeMethod() {
        headers = new DefaultHttpHeaders();
        attributes = new HashMap<>();
        requestInfoMock = mock(RequestInfo.class);
        doReturn(headers).when(requestInfoMock).getHeaders();
        doReturn(attributes).when(requestInfoMock).getRequestAttributes();
        isSsl = false;
        wrapper = new HttpServletRequestWrapperForRequestInfo<>(requestInfoMock, isSsl);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void constructor_sets_fields_as_expected(boolean ssl) {
        // when
        HttpServletRequestWrapperForRequestInfo<?> result = new HttpServletRequestWrapperForRequestInfo<>(requestInfoMock, ssl);

        // then
        assertThat(result.requestInfo).isEqualTo(requestInfoMock);
        assertThat(result.isSsl).isEqualTo(ssl);
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_requestInfo_is_null() {
        // when
        Throwable ex = catchThrowable(() -> new HttpServletRequestWrapperForRequestInfo<>(null, false));

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class);
    }

    private void verifyUnsupportedOperation(ThrowingCallable operation) {
        // when
        Throwable ex = catchThrowable(operation);

        // then
        assertThat(ex).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void getAuthType_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getAuthType());
    }

    @Test
    public void getCookies_returns_cookies_from_requestInfo() {
        // given
        Set<io.netty.handler.codec.http.cookie.Cookie> nettyCookies = new LinkedHashSet<>(Arrays.asList(
            new DefaultCookie(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
            new DefaultCookie(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        ));
        doReturn(nettyCookies).when(requestInfoMock).getCookies();

        List<Cookie> expectedCookieList = nettyCookies
            .stream().map(nc -> new Cookie(nc.name(), nc.value())).collect(Collectors.toList());

        // when
        Cookie[] result = wrapper.getCookies();

        // then
        for (int i = 0; i < result.length; i++) {
            Cookie expected = expectedCookieList.get(i);
            Cookie actual = result[i];
            assertThat(actual.getName()).isEqualTo(expected.getName());
            assertThat(actual.getValue()).isEqualTo(expected.getValue());
        }
    }

    @Test
    public void getCookies_uses_cached_value() {
        // given
        Cookie[] cachedValue = new Cookie[] {new Cookie(UUID.randomUUID().toString(), UUID.randomUUID().toString())};
        Whitebox.setInternalState(wrapper, "convertedCookieCache", cachedValue);

        // when
        Cookie[] result = wrapper.getCookies();

        // then
        assertThat(result).isSameAs(cachedValue);
    }

    @Test
    public void getDateHeader_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getDateHeader("foo"));
    }

    @Test
    public void getHeader_delegates_to_requestInfo_headers() {
        // given
        HttpHeaders headersMock = mock(HttpHeaders.class);
        String headerKey = UUID.randomUUID().toString();
        String headerVal = UUID.randomUUID().toString();
        doReturn(headerVal).when(headersMock).get(headerKey);
        doReturn(headersMock).when(requestInfoMock).getHeaders();

        // when
        String result = wrapper.getHeader(headerKey);

        // then
        verify(headersMock).get(headerKey);
        assertThat(result).isEqualTo(headerVal);
    }

    @Test
    public void getHeaders_delegates_to_requestInfo_headers() {
        // given
        HttpHeaders headersMock = mock(HttpHeaders.class);
        String headerKey = UUID.randomUUID().toString();
        List<String> headerVal = Arrays.asList(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        doReturn(headerVal).when(headersMock).getAll(headerKey);
        doReturn(headersMock).when(requestInfoMock).getHeaders();

        // when
        Enumeration<String> result = wrapper.getHeaders(headerKey);

        // then
        verify(headersMock).getAll(headerKey);
        assertThat(Collections.list(result)).isEqualTo(headerVal);
    }

    @Test
    public void getHeaderNames_delegates_to_requestInfo_headers() {
        // given
        HttpHeaders headersMock = mock(HttpHeaders.class);
        Set<String> headerNames = new LinkedHashSet<>(Arrays.asList(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        doReturn(headerNames).when(headersMock).names();
        doReturn(headersMock).when(requestInfoMock).getHeaders();

        // when
        Enumeration<String> result = wrapper.getHeaderNames();

        // then
        verify(headersMock).names();
        assertThat(new LinkedHashSet<>(Collections.list(result))).isEqualTo(headerNames);
    }

    @Test
    public void getIntHeader_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getIntHeader("foo"));
    }

    @DataProvider(value = {
        "null",
        "GET",
        "POST",
        "OPTIONS"
    }, splitBy = "\\|")
    @Test
    public void getMethod_delegates_to_requestInfo(String methodName) {
        // given
        HttpMethod method = (methodName == null) ? null : HttpMethod.valueOf(methodName);
        doReturn(method).when(requestInfoMock).getMethod();

        // when
        String result = wrapper.getMethod();

        // then
        assertThat(result).isEqualTo(methodName);
    }

    @Test
    public void getPathInfo_delegates_to_requestInfo() {
        // given
        String path = UUID.randomUUID().toString();
        doReturn(path).when(requestInfoMock).getPath();

        // when
        String result = wrapper.getPathInfo();

        // then
        assertThat(result).isEqualTo(path);
    }

    @Test
    public void getPathTranslated_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getPathTranslated());
    }

    @DataProvider(value = {
        "null           |   ",
        "               |   ",
        "/nosecondslash |   ",
        "/foo/bar       |   /foo"
    }, splitBy = "\\|")
    @Test
    public void getContextPath_extracts_result_from_requestInfo_path(String path, String expectedResult) {
        // given
        doReturn(path).when(requestInfoMock).getPath();

        // when
        String result = wrapper.getContextPath();

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void getQueryString_delegates_to_requestInfo() {
        // given
        String queryString = "foo=bar&baz=" + UUID.randomUUID().toString();
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder("/some/path?" + queryString);
        doReturn(queryStringDecoder).when(requestInfoMock).getQueryParams();

        // when
        String result = wrapper.getQueryString();

        // then
        assertThat(result).isEqualTo(queryString);
    }

    @Test
    public void getQueryString_returns_null_if_no_query_string_exists() {
        // given
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder("/some/path");
        doReturn(queryStringDecoder).when(requestInfoMock).getQueryParams();

        // when
        String result = wrapper.getQueryString();

        // then
        assertThat(result).isNull();
    }

    @Test
    public void getRemoteUser_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getRemoteUser());
    }

    @Test
    public void isUserInRole_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.isUserInRole("foo"));
    }

    @Test
    public void getUserPrincipal_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getUserPrincipal());
    }

    @Test
    public void getRequestedSessionId_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getRequestedSessionId());
    }

    @Test
    public void getRequestURI_delegates_to_requestInfo() {
        // given
        String path = UUID.randomUUID().toString();
        doReturn(path).when(requestInfoMock).getPath();

        // when
        String result = wrapper.getRequestURI();

        // then
        assertThat(result).isEqualTo(path);
    }

    @DataProvider(value = {
        "true   |   somehost",
        "false  |   somehost",
        "true   |   null",
        "false  |   null"
    }, splitBy = "\\|")
    @Test
    public void getRequestURL_constructs_result_from_scheme_and_host_header_and_requestInfo_path(boolean ssl,
                                                                                                 String hostHeader) {
        // given
        Whitebox.setInternalState(wrapper, "isSsl", ssl);
        if (hostHeader != null)
            headers.set(HttpHeaders.Names.HOST, hostHeader);
        String path = "/" + UUID.randomUUID().toString();
        doReturn(path).when(requestInfoMock).getPath();
        String expectedResult = (ssl) ? "https://" : "http://";
        expectedResult += (hostHeader == null) ? "unknown" : hostHeader;
        expectedResult += path;

        // when
        StringBuffer result = wrapper.getRequestURL();

        // then
        assertThat(result.toString()).isEqualTo(expectedResult);
    }

    @Test
    public void getServletPath_returns_blank_string() {
        // expect
        assertThat(wrapper.getServletPath()).isEqualTo("");
    }

    @Test
    public void getSession_with_arg_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getSession(true));
    }

    @Test
    public void getSession_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getSession());
    }

    @Test
    public void changeSessionId_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.changeSessionId());
    }

    @Test
    public void isRequestedSessionIdValid_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.isRequestedSessionIdValid());
    }

    @Test
    public void isRequestedSessionIdFromCookie_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.isRequestedSessionIdFromCookie());
    }

    @Test
    public void isRequestedSessionIdFromURL_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.isRequestedSessionIdFromURL());
    }

    @Test
    public void isRequestedSessionIdFromUrl_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.isRequestedSessionIdFromUrl());
    }

    @Test
    public void authenticate_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.authenticate(null));
    }

    @Test
    public void login_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.login(null, null));
    }

    @Test
    public void logout_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.logout());
    }

    @Test
    public void getParts_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getParts());
    }

    @Test
    public void getPart_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getPart(null));
    }

    @Test
    public void upgrade_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.upgrade(null));
    }

    @Test
    public void getAttribute_delegates_to_requestInfo() {
        // given
        String attrKey = UUID.randomUUID().toString();
        int attrValue = 42;
        attributes.put(attrKey, attrValue);

        // expect
        assertThat(wrapper.getAttribute(attrKey)).isEqualTo(attrValue);
    }

    @Test
    public void getAttributeNames_delegates_to_requestInfo() {
        // given
        String attrKey1 = UUID.randomUUID().toString();
        String attrKey2 = UUID.randomUUID().toString();
        attributes.put(attrKey1, UUID.randomUUID().toString());
        attributes.put(attrKey2, UUID.randomUUID().toString());

        // expect
        assertThat(Collections.list(wrapper.getAttributeNames()))
            .containsOnly(attrKey1, attrKey2);
    }

    @Test
    public void getCharacterEncoding_returns_null() {
        // expect
        assertThat(wrapper.getCharacterEncoding()).isNull();
    }

    @Test
    public void setCharacterEncoding_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.setCharacterEncoding(null));
    }

    @Test
    public void getContentLength_delegates_to_requestInfo() {
        // given
        doReturn(42).when(requestInfoMock).getRawContentLengthInBytes();

        // when
        int result = wrapper.getContentLength();

        // then
        assertThat(result).isEqualTo(42);
        verify(requestInfoMock).getRawContentLengthInBytes();
    }

    @Test
    public void getContentLengthLong_delegates_to_requestInfo() {
        // given
        doReturn(42).when(requestInfoMock).getRawContentLengthInBytes();

        // when
        long result = wrapper.getContentLengthLong();

        // then
        assertThat(result).isEqualTo(42);
        verify(requestInfoMock).getRawContentLengthInBytes();
    }

    @Test
    public void getContentType_delegates_to_requestInfo_content_type_header() {
        // given
        String contentType = UUID.randomUUID().toString();
        headers.set(HttpHeaders.Names.CONTENT_TYPE, contentType);

        // when
        String result = wrapper.getContentType();

        // then
        assertThat(result).isEqualTo(contentType);
    }

    @Test
    public void getInputStream_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getInputStream());
    }

    @Test
    public void getParameter_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getParameter(null));
    }

    @Test
    public void getParameterNames_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getParameterNames());
    }

    @Test
    public void getParameterValues_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getParameterValues(null));
    }

    @Test
    public void getParameterMap_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getParameterMap());
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void getProtocol_delegates_to_requestInfo(boolean useNull) {
        // given
        HttpVersion protocolVersion = (useNull) ? null : HttpVersion.HTTP_1_1;
        doReturn(protocolVersion).when(requestInfoMock).getProtocolVersion();

        // when
        String result = wrapper.getProtocol();

        // then
        if (useNull)
            assertThat(result).isNull();
        else
            assertThat(result).isEqualTo(protocolVersion.text());
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void getScheme_uses_isSsl(boolean ssl) {
        // given
        Whitebox.setInternalState(wrapper, "isSsl", ssl);
        String expectedScheme = (ssl) ? "https" : "http";

        // when
        String result = wrapper.getScheme();

        // then
        assertThat(result).isEqualTo(expectedScheme);
    }

    @DataProvider(value = {
        "null       |   unknown",
        "foobar     |   foobar",
        "host:foo   |   host"
    }, splitBy = "\\|")
    @Test
    public void getServerName_parses_value_from_host_header(String hostHeaderVal, String expectedResult) {
        // given
        if (hostHeaderVal != null)
            headers.set(HttpHeaders.Names.HOST, hostHeaderVal);

        // when
        String result = wrapper.getServerName();

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @DataProvider(value = {
        "null       |   -1",
        "foobar     |   -1",
        "host:4242  |   4242"
    }, splitBy = "\\|")
    @Test
    public void getServerPort_parses_value_from_host_header(String hostHeaderVal, int expectedResult) {
        // given
        if (hostHeaderVal != null)
            headers.set(HttpHeaders.Names.HOST, hostHeaderVal);

        // when
        int result = wrapper.getServerPort();

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void getReader_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getReader());
    }

    @Test
    public void getRemoteAddr_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getRemoteAddr());
    }

    @Test
    public void getRemoteHost_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getRemoteHost());
    }

    @Test
    public void setAttribute_delegates_to_requestInfo() {
        // given
        String attrKey = UUID.randomUUID().toString();
        double attrVal = 42.42;

        // when
        wrapper.setAttribute(attrKey, attrVal);

        // then
        verify(requestInfoMock).addRequestAttribute(attrKey, attrVal);
    }

    @Test
    public void removeAttribute_delegates_to_requestInfo() {
        // given
        String attrKey = UUID.randomUUID().toString();
        double attrVal = 42.42;
        attributes.put(attrKey, attrVal);

        // when
        wrapper.removeAttribute(attrKey);

        // then
        assertThat(attributes).doesNotContainKey(attrKey);
    }

    @Test
    public void getLocale_returns_default_locale() {
        // expect
        assertThat(wrapper.getLocale()).isEqualTo(Locale.getDefault());
    }

    @Test
    public void getLocales_returns_singleton_of_default() {
        // expect
        assertThat(Collections.list(wrapper.getLocales())).isEqualTo(singletonList(Locale.getDefault()));
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void isSecure_returns_expected_value(boolean ssl) {
        // given
        Whitebox.setInternalState(wrapper, "isSsl", ssl);

        // expect
        assertThat(wrapper.isSecure()).isEqualTo(ssl);
    }

    @Test
    public void getRequestDispatcher_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getRequestDispatcher(null));
    }

    @Test
    public void getRealPath_returns_null() {
        // expect
        assertThat(wrapper.getRealPath("foo")).isNull();
    }

    @Test
    public void getRemotePort_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getRemotePort());
    }

    @Test
    public void getLocalName_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getLocalName());
    }

    @Test
    public void getLocalAddr_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getLocalAddr());
    }

    @Test
    public void getLocalPort_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getLocalPort());
    }

    @Test
    public void getServletContext_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getServletContext());
    }

    @Test
    public void startAsync_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.startAsync());
    }

    @Test
    public void startAsync_with_args_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.startAsync(null, null));
    }

    @Test
    public void isAsyncStarted_returns_false() {
        // expect
        assertThat(wrapper.isAsyncStarted()).isFalse();
    }

    @Test
    public void isAsyncSupported_returns_false() {
        // expect
        assertThat(wrapper.isAsyncSupported()).isFalse();
    }

    @Test
    public void getAsyncContext_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getAsyncContext());
    }

    @Test
    public void getDispatcherType_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getDispatcherType());
    }
}