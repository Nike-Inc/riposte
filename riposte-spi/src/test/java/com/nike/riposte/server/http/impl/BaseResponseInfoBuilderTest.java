package com.nike.riposte.server.http.impl;

import com.google.common.collect.Sets;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.util.CharsetUtil;
import org.junit.Test;

import java.nio.charset.Charset;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link BaseResponseInfoBuilder}
 *
 * @author Nic Munroe
 */
public class BaseResponseInfoBuilderTest {

    @Test
    public void builder_stores_values_as_expected() {
        // given
        String content = UUID.randomUUID().toString();
        int httpStatusCode = 200;
        HttpHeaders headers = new DefaultHttpHeaders();
        String mimeType = "text/text";
        Charset contentCharset = CharsetUtil.ISO_8859_1;
        Set<Cookie> cookies = Sets.newHashSet(new DefaultCookie("key1", "val1"), new DefaultCookie("key2", "val2"));
        boolean preventCompressedOutput = true;

        // when
        BaseResponseInfoBuilder<String> responseInfoBuilder = new BaseResponseInfoBuilder<String>(){}
                .withHttpStatusCode(httpStatusCode)
                .withHeaders(headers)
                .withDesiredContentWriterMimeType(mimeType)
                .withDesiredContentWriterEncoding(contentCharset).withCookies(cookies)
                .withPreventCompressedOutput(preventCompressedOutput);

        // then
        assertThat(responseInfoBuilder.getHttpStatusCode(), is(httpStatusCode));
        assertThat(responseInfoBuilder.getHeaders(), is(headers));
        assertThat(responseInfoBuilder.getDesiredContentWriterMimeType(), is(mimeType));
        assertThat(responseInfoBuilder.getDesiredContentWriterEncoding(), is(contentCharset));
        assertThat(responseInfoBuilder.getCookies(), is(cookies));
        assertThat(responseInfoBuilder.isPreventCompressedOutput(), is(preventCompressedOutput));
    }
}