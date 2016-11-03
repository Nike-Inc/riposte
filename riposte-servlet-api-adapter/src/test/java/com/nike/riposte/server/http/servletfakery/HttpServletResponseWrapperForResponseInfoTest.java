package com.nike.riposte.server.http.servletfakery;

import com.nike.riposte.server.http.ResponseInfo;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.assertj.core.api.ThrowableAssert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;

import javax.servlet.http.Cookie;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link HttpServletResponseWrapperForResponseInfo}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class HttpServletResponseWrapperForResponseInfoTest {

    private ResponseInfo<?> responseInfoMock;
    private HttpHeaders headers;
    private HttpServletResponseWrapperForResponseInfo wrapper;

    @Before
    public void beforeMethod() {
        headers = new DefaultHttpHeaders();
        responseInfoMock = mock(ResponseInfo.class);
        doReturn(headers).when(responseInfoMock).getHeaders();

        wrapper = new HttpServletResponseWrapperForResponseInfo<>(responseInfoMock);
    }

    @Test
    public void constructor_sets_fields_as_expected() {
        // when
        HttpServletResponseWrapperForResponseInfo<?> instance = new HttpServletResponseWrapperForResponseInfo<>(responseInfoMock);

        // then
        assertThat(instance.responseInfo).isSameAs(responseInfoMock);
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_requestInfo_is_null() {
        // when
        Throwable ex = catchThrowable(() -> new HttpServletResponseWrapperForResponseInfo<>(null));

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class);
    }

    private void verifyUnsupportedOperation(ThrowableAssert.ThrowingCallable operation) {
        // when
        Throwable ex = catchThrowable(operation);

        // then
        assertThat(ex).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void addCookie_adds_to_cookie_header() {
        // given
        Cookie cookie = new Cookie(UUID.randomUUID().toString(), UUID.randomUUID().toString());

        // when
        wrapper.addCookie(cookie);

        // then
        assertThat(headers.get(HttpHeaders.Names.SET_COOKIE))
            .isEqualTo(ServerCookieEncoder.LAX.encode(cookie.getName(), cookie.getValue()));
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void containsHeader_delegates_to_responseInfo(boolean containsHeader) {
        // given
        HttpHeaders headersMock = mock(HttpHeaders.class);
        doReturn(headersMock).when(responseInfoMock).getHeaders();
        String headerName = UUID.randomUUID().toString();
        doReturn(containsHeader).when(headersMock).contains(headerName);

        // expect
        assertThat(wrapper.containsHeader(headerName)).isEqualTo(containsHeader);
        verify(headersMock).contains(headerName);
    }

    @Test
    public void encodeURL_returns_url_unchanged() {
        // given
        String url = UUID.randomUUID().toString();

        // expect
        assertThat(wrapper.encodeURL(url)).isEqualTo(url);
    }

    @Test
    public void encodeRedirectURL_returns_url_unchanged() {
        // given
        String url = UUID.randomUUID().toString();

        // expect
        assertThat(wrapper.encodeRedirectURL(url)).isEqualTo(url);
    }

    @Test
    public void encodeUrl_returns_url_unchanged() {
        // given
        String url = UUID.randomUUID().toString();

        // expect
        assertThat(wrapper.encodeUrl(url)).isEqualTo(url);
    }

    @Test
    public void encodeRedirectUrl_returns_url_unchanged() {
        // given
        String url = UUID.randomUUID().toString();

        // expect
        assertThat(wrapper.encodeRedirectUrl(url)).isEqualTo(url);
    }

    @Test
    public void sendError_with_message_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.sendError(42, "foo"));
    }

    @Test
    public void sendError_without_message_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.sendError(42));
    }

    @Test
    public void sendRedirect_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.sendRedirect(null));
    }

    @Test
    public void setDateHeader_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.setDateHeader(null, 42));
    }

    @Test
    public void addDateHeader_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.addDateHeader(null, 42));
    }

    @Test
    public void setHeader_delegates_to_responseInfo() {
        // given
        String headerKey = UUID.randomUUID().toString();
        String headerVal = UUID.randomUUID().toString();
        headers.set(headerKey, "notthefinalvalue");

        // when
        wrapper.setHeader(headerKey, headerVal);

        // then
        assertThat(headers.getAll(headerKey)).isEqualTo(singletonList(headerVal));
    }

    @Test
    public void addHeader_delegates_to_responseInfo() {
        // given
        String headerKey = UUID.randomUUID().toString();
        String headerVal1 = UUID.randomUUID().toString();
        String headerVal2 = UUID.randomUUID().toString();
        headers.set(headerKey, headerVal1);

        // when
        wrapper.addHeader(headerKey, headerVal2);

        // then
        assertThat(headers.getAll(headerKey)).isEqualTo(Arrays.asList(headerVal1, headerVal2));
    }

    @Test
    public void setIntHeader_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.setIntHeader(null, 42));
    }

    @Test
    public void addIntHeader_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.addIntHeader(null, 42));
    }

    @Test
    public void setStatus_delegates_to_responseInfo() {
        // when
        wrapper.setStatus(42);

        // then
        verify(responseInfoMock).setHttpStatusCode(42);
    }

    @Test
    public void setStatus_with_message_delegates_to_responseInfo() {
        // when
        wrapper.setStatus(42, "foo");

        // then
        verify(responseInfoMock).setHttpStatusCode(42);
    }

    @Test
    public void getStatus_delegates_to_responseInfo() {
        // given
        int returnVal = 42;
        doReturn(returnVal).when(responseInfoMock).getHttpStatusCode();

        // when
        int result = wrapper.getStatus();

        // then
        assertThat(result).isEqualTo(returnVal);
        verify(responseInfoMock).getHttpStatusCode();
    }

    @Test
    public void getHeader_delegates_to_responseInfo() {
        // given
        String headerKey = UUID.randomUUID().toString();
        String headerVal = UUID.randomUUID().toString();
        headers.set(headerKey, headerVal);

        // when
        String result = wrapper.getHeader(headerKey);

        // then
        assertThat(result).isEqualTo(headerVal);
    }

    @Test
    public void getHeaders_delegates_to_responseInfo() {
        // given
        String headerKey = UUID.randomUUID().toString();
        String headerVal1 = UUID.randomUUID().toString();
        String headerVal2 = UUID.randomUUID().toString();
        headers.set(headerKey, headerVal1);
        headers.add(headerKey, headerVal2);

        // when
        Collection result = wrapper.getHeaders(headerKey);

        // then
        assertThat(result).isEqualTo(headers.getAll(headerKey));
        assertThat(headers.getAll(headerKey)).hasSize(2);
    }

    @Test
    public void getHeaderNames_delegates_to_responseInfo() {
        // given
        String headerKey1 = UUID.randomUUID().toString();
        String headerKey2 = UUID.randomUUID().toString();
        headers.set(headerKey1, UUID.randomUUID().toString());
        headers.set(headerKey2, UUID.randomUUID().toString());

        // when
        Collection result = wrapper.getHeaderNames();

        // then
        assertThat(result).isEqualTo(headers.names());
        assertThat(headers.names()).hasSize(2);
    }

    @DataProvider(value = {
        "null       |   ISO-8859-1",
        "ISO-8859-1 |   ISO-8859-1",
        "UTF-8      |   UTF-8"
    }, splitBy = "\\|")
    @Test
    public void getCharacterEncoding_delegates_to_responseInfo(String contentWriterEncodingName, String expectedResult) {
        // given
        Charset contentWriterEncoding = (contentWriterEncodingName == null) ? null : Charset.forName(contentWriterEncodingName);
        doReturn(contentWriterEncoding).when(responseInfoMock).getDesiredContentWriterEncoding();

        // when
        String result = wrapper.getCharacterEncoding();

        // expect
        assertThat(result).isEqualTo(expectedResult);
    }

    @DataProvider(value = {
        "null               |   null        |   null",
        "text/plain         |   null        |   text/plain",
        "text/plain         |   ISO-8859-1  |   text/plain; charset=ISO-8859-1",
        "application/json   |   UTF-8       |   application/json; charset=UTF-8"
    }, splitBy = "\\|")
    @Test
    public void getContentType_delegates_to_responseInfo(
        String contentWriterMimeType, String contentWriterEncodingName, String expectedResult
    ) {
        // given
        doReturn(contentWriterMimeType).when(responseInfoMock).getDesiredContentWriterMimeType();
        Charset contentWriterEncoding = (contentWriterEncodingName == null) ? null : Charset.forName(contentWriterEncodingName);
        doReturn(contentWriterEncoding).when(responseInfoMock).getDesiredContentWriterEncoding();

        // when
        String result = wrapper.getContentType();

        // expect
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void getOutputStream_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getOutputStream());
    }

    @Test
    public void getWriter_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getWriter());
    }

    @Test
    public void setCharacterEncoding_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.setCharacterEncoding(null));
    }

    @Test
    public void setContentLength_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.setContentLength(42));
    }

    @Test
    public void setContentLengthLong_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.setContentLengthLong(42));
    }

    @Test
    public void setContentType_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.setContentType(null));
    }

    @Test
    public void setBufferSize_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.setBufferSize(42));
    }

    @Test
    public void getBufferSize_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getBufferSize());
    }

    @Test
    public void flushBuffer_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.flushBuffer());
    }

    @Test
    public void resetBuffer_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.resetBuffer());
    }

    @Test
    public void isCommitted_returns_false() {
        // expect
        assertThat(wrapper.isCommitted()).isFalse();
    }

    @Test
    public void reset_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.reset());
    }

    @Test
    public void setLocale_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.setLocale(null));
    }

    @Test
    public void getLocale_throws_UnsupportedOperationException() {
        // expect
        verifyUnsupportedOperation(() -> wrapper.getLocale());
    }

}