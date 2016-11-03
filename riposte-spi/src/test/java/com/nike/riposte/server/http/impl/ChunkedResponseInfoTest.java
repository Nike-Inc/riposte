package com.nike.riposte.server.http.impl;

import com.nike.riposte.server.http.ResponseInfo;

import com.google.common.collect.Sets;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.Set;
import java.util.UUID;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.util.CharsetUtil;

import static junit.framework.TestCase.fail;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link ChunkedResponseInfo}
 *
 * @author Nic Munroe
 */
public class ChunkedResponseInfoTest {

    @Test
    public void uber_constructor_for_chunked_response_sets_fields_as_expected() {
        // given
        int httpStatusCode = 200;
        HttpHeaders headers = new DefaultHttpHeaders();
        String mimeType = "text/text";
        Charset contentCharset = CharsetUtil.UTF_8;
        Set<Cookie> cookies = Sets.newHashSet(new DefaultCookie("key1", "val1"), new DefaultCookie("key2", "val2"));
        boolean preventCompressedResponse = true;

        // when
        ChunkedResponseInfo responseInfo = new ChunkedResponseInfo(httpStatusCode, headers, mimeType, contentCharset, cookies, preventCompressedResponse);

        // then
        assertThat(responseInfo.getHttpStatusCode(), is(httpStatusCode));
        assertThat(responseInfo.getHeaders(), is(headers));
        assertThat(responseInfo.getDesiredContentWriterMimeType(), is(mimeType));
        assertThat(responseInfo.getDesiredContentWriterEncoding(), is(contentCharset));
        assertThat(responseInfo.getCookies(), is(cookies));
        assertThat(responseInfo.getUncompressedRawContentLength(), nullValue());
        assertThat(responseInfo.getFinalContentLength(), nullValue());
        assertThat(responseInfo.isPreventCompressedOutput(), is(preventCompressedResponse));
        assertThat(responseInfo.isChunkedResponse(), is(true));
        assertThat(responseInfo.isResponseSendingStarted(), is(false));
        assertThat(responseInfo.isResponseSendingLastChunkSent(), is(false));

    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_passed_mime_type_with_charset_in_it() {
        // expect
        new ChunkedResponseInfo(null, null, "text/text charset=UTF-8", null, null, false);
        fail("Exception was expected but none was thrown");
    }

    @Test
    public void default_constructor_uses_reasonable_default_values() {
        // when
        ChunkedResponseInfo responseInfo = new ChunkedResponseInfo();

        // then
        assertThat(responseInfo.getHttpStatusCode(), nullValue());
        assertThat(responseInfo.getHeaders(), notNullValue());
        assertThat(responseInfo.getHeaders().isEmpty(), is(true));
        assertThat(responseInfo.getDesiredContentWriterMimeType(), nullValue());
        assertThat(responseInfo.getDesiredContentWriterEncoding(), nullValue());
        assertThat(responseInfo.getCookies(), nullValue());
        assertThat(responseInfo.getUncompressedRawContentLength(), nullValue());
        assertThat(responseInfo.getFinalContentLength(), nullValue());
        assertThat(responseInfo.isChunkedResponse(), is(true));
        assertThat(responseInfo.isPreventCompressedOutput(), is(false));
        assertThat(responseInfo.isResponseSendingStarted(), is(false));
        assertThat(responseInfo.isResponseSendingLastChunkSent(), is(false));
    }

    @Test
    public void default_constructor_is_identical_to_blank_builder() {
        // given
        ChunkedResponseInfo ri = new ChunkedResponseInfo();
        ChunkedResponseInfo riFromBuilder = ResponseInfo.newChunkedResponseBuilder().build();

        // expect
        assertThat(ri.getHttpStatusCode(), is(riFromBuilder.getHttpStatusCode()));
        assertThat(ri.getHeaders().entries(), is(riFromBuilder.getHeaders().entries()));
        assertThat(ri.getDesiredContentWriterMimeType(), is(riFromBuilder.getDesiredContentWriterMimeType()));
        assertThat(ri.getDesiredContentWriterEncoding(), is(riFromBuilder.getDesiredContentWriterEncoding()));
        assertThat(ri.getCookies(), is(riFromBuilder.getCookies()));
        assertThat(ri.getUncompressedRawContentLength(), is(riFromBuilder.getUncompressedRawContentLength()));
        assertThat(ri.getFinalContentLength(), is(riFromBuilder.getFinalContentLength()));
        assertThat(ri.isChunkedResponse(), is(riFromBuilder.isChunkedResponse()));
        assertThat(ri.isPreventCompressedOutput(), is(riFromBuilder.isPreventCompressedOutput()));
        assertThat(ri.isResponseSendingStarted(), is(riFromBuilder.isResponseSendingStarted()));
        assertThat(ri.isResponseSendingLastChunkSent(), is(riFromBuilder.isResponseSendingLastChunkSent()));
    }

    @Test(expected = IllegalStateException.class)
    public void getContentForFullResponse_throws_IllegalStateException() {
        // given
        ChunkedResponseInfo responseInfo = ResponseInfo.newChunkedResponseBuilder().build();

        // expect
        responseInfo.getContentForFullResponse();
    }

    @Test(expected = IllegalStateException.class)
    public void setContentForFullResponse_throws_IllegalStateException() {
        // given
        ChunkedResponseInfo responseInfo = ResponseInfo.newChunkedResponseBuilder().build();

        // expect
        responseInfo.setContentForFullResponse(null);
    }

    @Test
    public void isChunkedResponse_returns_true() {
        // given
        ChunkedResponseInfo responseInfo = ResponseInfo.newChunkedResponseBuilder().build();

        // expect
        assertThat(responseInfo.isChunkedResponse(), is(true));
    }

    @Test
    public void builder_works_as_expected_for_all_fields() {
        // given
        ChunkedResponseInfo.ChunkedResponseInfoBuilder builder = ResponseInfo.newChunkedResponseBuilder();
        int statusCode = 42;
        HttpHeaders headers = mock(HttpHeaders.class);
        String mimeType = UUID.randomUUID().toString();
        Charset encoding = CharsetUtil.US_ASCII;
        Set<Cookie> cookies = mock(Set.class);
        boolean preventCompressedOutput = Math.random() > 0.5;

        // when
        ChunkedResponseInfo responseInfo = builder
                .withHttpStatusCode(statusCode)
                .withHeaders(headers)
                .withDesiredContentWriterMimeType(mimeType)
                .withDesiredContentWriterEncoding(encoding)
                .withCookies(cookies)
                .withPreventCompressedOutput(preventCompressedOutput)
                .build();

        // then
        assertThat(responseInfo.getHttpStatusCode(), is(statusCode));
        assertThat(responseInfo.getHeaders(), is(headers));
        assertThat(responseInfo.getDesiredContentWriterMimeType(), is(mimeType));
        assertThat(responseInfo.getDesiredContentWriterEncoding(), is(encoding));
        assertThat(responseInfo.getCookies(), is(cookies));
        assertThat(responseInfo.isPreventCompressedOutput(), is(preventCompressedOutput));
    }
}