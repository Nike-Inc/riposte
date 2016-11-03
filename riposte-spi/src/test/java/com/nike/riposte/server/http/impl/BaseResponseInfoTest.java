package com.nike.riposte.server.http.impl;

import com.google.common.collect.Sets;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

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
 * Tests the functionality of {@link BaseResponseInfo}
 */
@RunWith(DataProviderRunner.class)
public class BaseResponseInfoTest {

    public static <T> BaseResponseInfo<T> createNewBaseResponseInfoForTesting(Integer httpStatusCode,
                                                                       HttpHeaders headers,
                                                                       String desiredContentWriterMimeType,
                                                                       Charset desiredContentWriterEncoding,
                                                                       Set<Cookie> cookies,
                                                                       boolean preventCompressedOutput) {

        return new BaseResponseInfo<T>(httpStatusCode, headers, desiredContentWriterMimeType, desiredContentWriterEncoding, cookies, preventCompressedOutput) {
            @Override
            public boolean isChunkedResponse() {
                throw new UnsupportedOperationException("not implemented, don't call me during the test");
            }

            @Override
            public T getContentForFullResponse() {
                throw new UnsupportedOperationException("not implemented, don't call me during the test");
            }

            @Override
            public void setContentForFullResponse(T contentForFullResponse) {
                throw new UnsupportedOperationException("not implemented, don't call me during the test");
            }
        };

    }

    @Test
    public void uber_constructor_for_full_response_sets_fields_as_expected() {
        // given
        int httpStatusCode = 200;
        HttpHeaders headers = new DefaultHttpHeaders();
        String mimeType = "text/text";
        Charset contentCharset = CharsetUtil.UTF_8;
        Set<Cookie> cookies = Sets.newHashSet(new DefaultCookie("key1", "val1"), new DefaultCookie("key2", "val2"));
        boolean preventCompressedResponse = true;

        // when
        BaseResponseInfo<?> responseInfo = createNewBaseResponseInfoForTesting(httpStatusCode, headers, mimeType, contentCharset, cookies, preventCompressedResponse);

        // then
        assertThat(responseInfo.getHttpStatusCode(), is(httpStatusCode));
        assertThat(responseInfo.getHeaders(), is(headers));
        assertThat(responseInfo.getDesiredContentWriterMimeType(), is(mimeType));
        assertThat(responseInfo.getDesiredContentWriterEncoding(), is(contentCharset));
        assertThat(responseInfo.getCookies(), is(cookies));
        assertThat(responseInfo.getUncompressedRawContentLength(), nullValue());
        assertThat(responseInfo.isPreventCompressedOutput(), is(preventCompressedResponse));
        assertThat(responseInfo.isResponseSendingStarted(), is(false));
        assertThat(responseInfo.isResponseSendingLastChunkSent(), is(false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_passed_mime_type_with_charset_in_it() {
        // expect
        createNewBaseResponseInfoForTesting(null, null, "text/text charset=UTF-8", null, null, false);
        fail("Exception was expected but none was thrown");
    }

    @Test
    public void passing_nulls_to_constructor_uses_reasonable_default_values() {
        // when
        BaseResponseInfo<?> responseInfo = createNewBaseResponseInfoForTesting(null, null, null, null, null, false);

        // then
        assertThat(responseInfo.getHttpStatusCode(), nullValue());
        assertThat(responseInfo.getHeaders(), notNullValue());
        assertThat(responseInfo.getHeaders().isEmpty(), is(true));
        assertThat(responseInfo.getDesiredContentWriterMimeType(), nullValue());
        assertThat(responseInfo.getDesiredContentWriterEncoding(), nullValue());
        assertThat(responseInfo.getCookies(), nullValue());
        assertThat(responseInfo.getUncompressedRawContentLength(), nullValue());
        assertThat(responseInfo.getFinalContentLength(), nullValue());
        assertThat(responseInfo.isResponseSendingStarted(), is(false));
        assertThat(responseInfo.isResponseSendingLastChunkSent(), is(false));
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void getter_setter_code_coverage(boolean boolVal) {
        // given
        BaseResponseInfo<?> responseInfo = createNewBaseResponseInfoForTesting(null, null, null, null, null, false);
        String randomString = UUID.randomUUID().toString();
        Set<Cookie> cookiesMock = mock(Set.class);
        long longVal = 4242;
        int intVal = 42;
        Charset charsetMock = mock(Charset.class);

        // expect
        responseInfo.setHttpStatusCode(intVal);
        assertThat(responseInfo.getHttpStatusCode(), is(intVal));

        responseInfo.setDesiredContentWriterMimeType(randomString);
        assertThat(responseInfo.getDesiredContentWriterMimeType(), is(randomString));

        responseInfo.setDesiredContentWriterEncoding(charsetMock);
        assertThat(responseInfo.getDesiredContentWriterEncoding(), is(charsetMock));

        responseInfo.setCookies(cookiesMock);
        assertThat(responseInfo.getCookies(), is(cookiesMock));

        responseInfo.setPreventCompressedOutput(boolVal);
        assertThat(responseInfo.isPreventCompressedOutput(), is(boolVal));

        responseInfo.setUncompressedRawContentLength(longVal);
        assertThat(responseInfo.getUncompressedRawContentLength(), is(longVal));

        responseInfo.setFinalContentLength(longVal);
        assertThat(responseInfo.getFinalContentLength(), is(longVal));

        responseInfo.setResponseSendingStarted(boolVal);
        assertThat(responseInfo.isResponseSendingStarted(), is(boolVal));

        responseInfo.setResponseSendingLastChunkSent(boolVal);
        assertThat(responseInfo.isResponseSendingLastChunkSent(), is(boolVal));

        responseInfo.setForceConnectionCloseAfterResponseSent(boolVal);
        assertThat(responseInfo.isForceConnectionCloseAfterResponseSent(), is(boolVal));
    }
}