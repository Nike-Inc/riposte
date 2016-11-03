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

/**
 * Tests the functionality of {@link FullResponseInfo}
 *
 * @author Nic Munroe
 */
public class FullResponseInfoTest {

    @Test
    public void uber_constructor_for_full_response_sets_fields_as_expected() {
        // given
        String content = UUID.randomUUID().toString();
        int httpStatusCode = 200;
        HttpHeaders headers = new DefaultHttpHeaders();
        String mimeType = "text/text";
        Charset contentCharset = CharsetUtil.UTF_8;
        Set<Cookie> cookies = Sets.newHashSet(new DefaultCookie("key1", "val1"), new DefaultCookie("key2", "val2"));
        boolean preventCompressedResponse = true;

        // when
        FullResponseInfo<String> responseInfo = new FullResponseInfo<>(content, httpStatusCode, headers, mimeType, contentCharset, cookies, preventCompressedResponse);

        // then
        assertThat(responseInfo.getContentForFullResponse(), is(content));
        assertThat(responseInfo.getHttpStatusCode(), is(httpStatusCode));
        assertThat(responseInfo.getHeaders(), is(headers));
        assertThat(responseInfo.getDesiredContentWriterMimeType(), is(mimeType));
        assertThat(responseInfo.getDesiredContentWriterEncoding(), is(contentCharset));
        assertThat(responseInfo.getCookies(), is(cookies));
        assertThat(responseInfo.getUncompressedRawContentLength(), nullValue());
        assertThat(responseInfo.getFinalContentLength(), nullValue());
        assertThat(responseInfo.isPreventCompressedOutput(), is(preventCompressedResponse));
        assertThat(responseInfo.isChunkedResponse(), is(false));
        assertThat(responseInfo.isResponseSendingStarted(), is(false));
        assertThat(responseInfo.isResponseSendingLastChunkSent(), is(false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_passed_mime_type_with_charset_in_it() {
        // expect
        new FullResponseInfo<>(null, null, null, "text/text charset=UTF-8", null, null, false);
        fail("Exception was expected but none was thrown");
    }

    @Test
    public void default_constructor_uses_reasonable_default_values() {
        // when
        FullResponseInfo<?> responseInfo = new FullResponseInfo<>();

        // then
        assertThat(responseInfo.getContentForFullResponse(), nullValue());
        assertThat(responseInfo.getHttpStatusCode(), nullValue());
        assertThat(responseInfo.getHeaders(), notNullValue());
        assertThat(responseInfo.getHeaders().isEmpty(), is(true));
        assertThat(responseInfo.getDesiredContentWriterMimeType(), nullValue());
        assertThat(responseInfo.getDesiredContentWriterEncoding(), nullValue());
        assertThat(responseInfo.getCookies(), nullValue());
        assertThat(responseInfo.getUncompressedRawContentLength(), nullValue());
        assertThat(responseInfo.getFinalContentLength(), nullValue());
        assertThat(responseInfo.isPreventCompressedOutput(), is(false));
        assertThat(responseInfo.isChunkedResponse(), is(false));
        assertThat(responseInfo.isResponseSendingStarted(), is(false));
        assertThat(responseInfo.isResponseSendingLastChunkSent(), is(false));
    }

    @Test
    public void default_constructor_is_identical_to_blank_builder() {
        // given
        FullResponseInfo<?> ri = new FullResponseInfo<>();
        FullResponseInfo<?> riFromBuilder = ResponseInfo.newBuilder().build();

        // expect
        assertThat(ri.getContentForFullResponse(), is(riFromBuilder.getContentForFullResponse()));
        assertThat(ri.getHttpStatusCode(), is(riFromBuilder.getHttpStatusCode()));
        assertThat(ri.getHeaders().entries(), is(riFromBuilder.getHeaders().entries()));
        assertThat(ri.getDesiredContentWriterMimeType(), is(riFromBuilder.getDesiredContentWriterMimeType()));
        assertThat(ri.getDesiredContentWriterEncoding(), is(riFromBuilder.getDesiredContentWriterEncoding()));
        assertThat(ri.getCookies(), is(riFromBuilder.getCookies()));
        assertThat(ri.getUncompressedRawContentLength(), is(riFromBuilder.getUncompressedRawContentLength()));
        assertThat(ri.getFinalContentLength(), is(riFromBuilder.getFinalContentLength()));
        assertThat(ri.isPreventCompressedOutput(), is(riFromBuilder.isPreventCompressedOutput()));
        assertThat(ri.isChunkedResponse(), is(riFromBuilder.isChunkedResponse()));
        assertThat(ri.isResponseSendingStarted(), is(riFromBuilder.isResponseSendingStarted()));
        assertThat(ri.isResponseSendingLastChunkSent(), is(riFromBuilder.isResponseSendingLastChunkSent()));
    }

    @Test
    public void newBuilder_with_content_sets_content_up_correctly() {
        // given
        String content = UUID.randomUUID().toString();

        // when
        FullResponseInfo<String> responseInfo = ResponseInfo.newBuilder(content).build();

        // then
        assertThat(responseInfo.getContentForFullResponse(), is(content));
    }

    @Test
    public void builder_sets_values_as_expected() {
        // given
        String content = UUID.randomUUID().toString();
        int httpStatusCode = 200;
        HttpHeaders headers = new DefaultHttpHeaders();
        String mimeType = "text/text";
        Charset contentCharset = CharsetUtil.ISO_8859_1;
        Set<Cookie> cookies = Sets.newHashSet(new DefaultCookie("key1", "val1"), new DefaultCookie("key2", "val2"));
        boolean preventCompressedOutput = true;

        // when
        FullResponseInfo<String> responseInfo = ResponseInfo.<String>newBuilder()
                                                        .withContentForFullResponse(content)
                                                        .withHttpStatusCode(httpStatusCode)
                                                        .withHeaders(headers)
                                                        .withDesiredContentWriterMimeType(mimeType)
                                                        .withDesiredContentWriterEncoding(contentCharset).withCookies(cookies)
                                                        .withPreventCompressedOutput(preventCompressedOutput).build();

        // then
        assertThat(responseInfo.getContentForFullResponse(), is(content));
        assertThat(responseInfo.getHttpStatusCode(), is(httpStatusCode));
        assertThat(responseInfo.getHeaders(), is(headers));
        assertThat(responseInfo.getDesiredContentWriterMimeType(), is(mimeType));
        assertThat(responseInfo.getDesiredContentWriterEncoding(), is(contentCharset));
        assertThat(responseInfo.getCookies(), is(cookies));
        assertThat(responseInfo.getUncompressedRawContentLength(), nullValue());
        assertThat(responseInfo.getFinalContentLength(), nullValue());
        assertThat(responseInfo.isPreventCompressedOutput(), is(preventCompressedOutput));
        assertThat(responseInfo.isChunkedResponse(), is(false));
        assertThat(responseInfo.isResponseSendingStarted(), is(false));
        assertThat(responseInfo.isResponseSendingLastChunkSent(), is(false));
    }

    @Test(expected = IllegalStateException.class)
    public void setContentForFullResponse_throws_IllegalStateException_if_isResponseSendingLastChunkSent_returns_true() {
        // given
        FullResponseInfo<String> responseInfo = ResponseInfo.<String>newBuilder().build();
        responseInfo.setResponseSendingLastChunkSent(true);

        // expect
        responseInfo.setContentForFullResponse("boom");
    }

    @Test
    public void setContentForFullResponse_works_if_isResponseSendingLastChunkSent_returns_false() {
        // given
        FullResponseInfo<String> responseInfo = ResponseInfo.<String>newBuilder().build();
        responseInfo.setResponseSendingLastChunkSent(false);
        String content = UUID.randomUUID().toString();

        // when
        responseInfo.setContentForFullResponse(content);

        // then
        assertThat(responseInfo.getContentForFullResponse(), is(content));
    }

    @Test
    public void isChunkedResponse_returns_false() {
        // given
        FullResponseInfo<String> responseInfo = ResponseInfo.<String>newBuilder().build();

        // expect
        assertThat(responseInfo.isChunkedResponse(), is(false));
    }
}