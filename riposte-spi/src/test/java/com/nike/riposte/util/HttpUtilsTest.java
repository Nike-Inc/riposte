package com.nike.riposte.util;

import com.nike.riposte.server.error.exception.InvalidCharsetInContentTypeHeaderException;
import com.nike.riposte.server.http.RequestInfo;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.util.CharsetUtil;

import static junit.framework.TestCase.fail;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link HttpUtils}
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class HttpUtilsTest {

    @Test
    public void code_coverage_hoops() {
        // jump!
        new HttpUtils();
    }

    @Test
    @DataProvider(value = {
            "/some/uri?foo=bar  |   /some/uri",
            "/some/uri          |   /some/uri",
            "null               |   "
    }, splitBy = "\\|")
    public void extractPath_works_as_expected(String uri, String expected) {
        // when
        String actual = HttpUtils.extractPath(uri);

        // then
        assertThat(actual, is(expected));
    }

    @Test
    @DataProvider(value = {
            "somecontent1234!@#$%^&*/?.,<>;:'\"{}[]()     | alsosomecontent1234!@#$%^&*/?.,<>;:'\"{}[]()    |   UTF-8",
            "somecontent1234!@#$%^&*/?.,<>;:'\"{}[]()     | alsosomecontent1234!@#$%^&*/?.,<>;:'\"{}[]()    |   UTF-16",
            "somecontent1234!@#$%^&*/?.,<>;:'\"{}[]()     | alsosomecontent1234!@#$%^&*/?.,<>;:'\"{}[]()    |   ISO-8859-1"
    }, splitBy = "\\|")
    public void convertContentChunksToRawString_and_convertContentChunksToRawBytes_works(String chunk1Base, String chunk2Base, String charsetString) throws IOException {
        // given
        Charset contentCharset = Charset.forName(charsetString);
        String chunk1Content = chunk1Base + "-" + UUID.randomUUID().toString();
        String chunk2Content = chunk2Base + "-" + UUID.randomUUID().toString();
        byte[] chunk1Bytes = chunk1Content.getBytes(contentCharset);
        byte[] chunk2Bytes = chunk2Content.getBytes(contentCharset);
        ByteBuf chunk1ByteBuf = Unpooled.copiedBuffer(chunk1Bytes);
        ByteBuf chunk2ByteBuf = Unpooled.copiedBuffer(chunk2Bytes);
        Collection<HttpContent> chunkCollection = Arrays.asList(new DefaultHttpContent(chunk1ByteBuf), new DefaultHttpContent(chunk2ByteBuf));

        // when
        String resultString = HttpUtils.convertContentChunksToRawString(contentCharset, chunkCollection);
        byte[] resultBytes = HttpUtils.convertContentChunksToRawBytes(chunkCollection);

        // then
        String expectedResultString = chunk1Content + chunk2Content;
        assertThat(resultString, is(expectedResultString));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(chunk1Bytes);
        baos.write(chunk2Bytes);
        assertThat(resultBytes, is(baos.toByteArray()));
    }

    @Test
    public void convertContentChunksToRawString_and_convertContentChunksToRawBytes_works_with_EmptyByteBuf_chunks() throws IOException {
        // given
        Charset contentCharset = CharsetUtil.UTF_8;
        String chunk1Content = UUID.randomUUID().toString();
        String chunk2Content = UUID.randomUUID().toString();
        byte[] chunk1Bytes = chunk1Content.getBytes(contentCharset);
        byte[] chunk2Bytes = chunk2Content.getBytes(contentCharset);
        ByteBuf chunk1ByteBuf = Unpooled.copiedBuffer(chunk1Bytes);
        ByteBuf chunk2ByteBuf = Unpooled.copiedBuffer(chunk2Bytes);
        Collection<HttpContent> chunkCollection = Arrays.asList(
                new DefaultHttpContent(chunk1ByteBuf),
                new DefaultHttpContent(new EmptyByteBuf(ByteBufAllocator.DEFAULT)),
                new DefaultHttpContent(chunk2ByteBuf),
                new DefaultHttpContent(new EmptyByteBuf(ByteBufAllocator.DEFAULT))
        );

        // when
        String resultString = HttpUtils.convertContentChunksToRawString(contentCharset, chunkCollection);
        byte[] resultBytes = HttpUtils.convertContentChunksToRawBytes(chunkCollection);

        // then
        String expectedResultString = chunk1Content + chunk2Content;
        assertThat(resultString, is(expectedResultString));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(chunk1Bytes);
        baos.write(chunk2Bytes);
        assertThat(resultBytes, is(baos.toByteArray()));
    }

    @Test
    public void convertContentChunksToRawString_and_convertContentChunksToRawBytes_returns_null_if_chunks_arg_is_null() {
        // when
        String resultString = HttpUtils.convertContentChunksToRawString(CharsetUtil.UTF_8, null);
        byte[] resultBytes = HttpUtils.convertContentChunksToRawBytes(null);

        // then
        assertThat(resultString, nullValue());
        assertThat(resultBytes, nullValue());
    }

    @Test
    public void convertContentChunksToRawString_and_convertContentChunksToRawBytes_returns_null_if_chunks_arg_is_empty() {
        // when
        String resultString = HttpUtils.convertContentChunksToRawString(CharsetUtil.UTF_8, Collections.emptyList());
        byte[] resultBytes = HttpUtils.convertContentChunksToRawBytes(Collections.emptyList());

        // then
        assertThat(resultString, nullValue());
        assertThat(resultBytes, nullValue());
    }

    @Test
    public void convertContentChunksToRawBytes_returns_null_if_total_bytes_is_zero() {
        // given
        Collection<HttpContent> chunkCollection = Arrays.asList(new DefaultHttpContent(new EmptyByteBuf(ByteBufAllocator.DEFAULT)),
                new DefaultHttpContent(new EmptyByteBuf(ByteBufAllocator.DEFAULT)));

        // when
        byte[] resultBytes = HttpUtils.convertContentChunksToRawBytes(chunkCollection);

        // then
        assertThat(resultBytes, nullValue());
    }

    @Test
    @DataProvider(value = {
            "UTF-8",
            "UTF-16",
            "ISO-8859-1"
    }, splitBy = "\\|")
    public void convertRawBytesToString_works(String charsetString) {
        // given
        Charset contentCharset = Charset.forName(charsetString);
        String uuidString = UUID.randomUUID().toString();
        byte[] rawBytes = uuidString.getBytes(contentCharset);

        // when
        String result = HttpUtils.convertRawBytesToString(contentCharset, rawBytes);

        // then
        assertThat(result, is(uuidString));
    }

    @Test
    public void convertRawBytesToString_removes_byte_order_marks() {
        // given
        String uuid1String = UUID.randomUUID().toString();
        String uuid2String = UUID.randomUUID().toString();
        String contentWithBOM = uuid1String + "\uFEFF" + uuid2String + "\uFEFF";

        // when
        String result = HttpUtils.convertRawBytesToString(CharsetUtil.UTF_8, contentWithBOM.getBytes(CharsetUtil.UTF_8));

        // then
        assertThat(result, is(uuid1String + uuid2String));
    }

    @Test
    public void convertRawBytesToString_returns_null_when_byte_array_is_null() {
        // expect
        assertThat(HttpUtils.convertRawBytesToString(CharsetUtil.UTF_8, null), nullValue());
    }

    @Test
    public void convertRawBytesToString_returns_null_when_byte_array_is_empty() {
        // expect
        assertThat(HttpUtils.convertRawBytesToString(CharsetUtil.UTF_8, new byte[0]), is(""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void convertRawBytesToString_throws_IllegalArgumentException_if_contentCharset_is_null() {
        // expect
        HttpUtils.convertRawBytesToString(null, new byte[0]);
        fail("Expected IllegalArgumentException but no exception was thrown");
    }

    @Test
    @DataProvider(value = {
            "text/text charset=US-ASCII |   UTF-8   | US-ASCII",
            "text/text charset=us-ascii |   UTF-8   | US-ASCII",
            "text/text                  |   UTF-8   | UTF-8",
            "                           |   UTF-8   | UTF-8",
            "null                       |   UTF-8   | UTF-8",
    }, splitBy = "\\|")
    public void determineCharsetFromContentType_works(String contentTypeHeader, String defaultCharsetString, String expectedCharsetString) {
        // given
        Charset defaultCharset = Charset.forName(defaultCharsetString);
        Charset expectedCharset = Charset.forName(expectedCharsetString);
        HttpHeaders headers = new DefaultHttpHeaders().add(HttpHeaders.Names.CONTENT_TYPE, String.valueOf(contentTypeHeader));

        // when
        Charset actualCharset = HttpUtils.determineCharsetFromContentType(headers, defaultCharset);

        // then
        assertThat(actualCharset, is(expectedCharset));
    }

    @Test
    public void determineCharsetFromContentType_returns_default_if_passed_null_headers() {
        // given
        Charset defaultCharset = CharsetUtil.US_ASCII;

        // when
        Charset actualCharset = HttpUtils.determineCharsetFromContentType(null, defaultCharset);

        // then
        assertThat(actualCharset, is(defaultCharset));
    }

    @Test(expected = InvalidCharsetInContentTypeHeaderException.class)
    public void determineCharsetFromContentType_throws_InvalidCharsetInContentTypeHeaderException_if_passed_header_with_invalid_charset() {
        // given
        HttpHeaders headers = new DefaultHttpHeaders().add(HttpHeaders.Names.CONTENT_TYPE, "text/text charset=garbagio");

        // expect
        HttpUtils.determineCharsetFromContentType(headers, CharsetUtil.UTF_8);
        fail("Expected an exception but none was thrown");
    }

    @Test
    public void extractCookies_works_if_cookies_defined_in_headers() {
        // given
        Cookie cookie1 = new DefaultCookie(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        Cookie cookie2 = new DefaultCookie(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        HttpHeaders headers = new DefaultHttpHeaders().add(HttpHeaders.Names.COOKIE, ClientCookieEncoder.LAX.encode(cookie1, cookie2));

        HttpRequest nettyRequestMock = mock(HttpRequest.class);
        doReturn(headers).when(nettyRequestMock).headers();

        // when
        Set<Cookie> extractedCookies = HttpUtils.extractCookies(nettyRequestMock);

        // then
        assertThat(extractedCookies.contains(cookie1), is(true));
        assertThat(extractedCookies.contains(cookie2), is(true));
    }

    @Test
    public void extractCookies_works_if_cookies_defined_in_trailing_headers() {
        // given
        Cookie cookie1 = new DefaultCookie(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        Cookie cookie2 = new DefaultCookie(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        HttpHeaders trailingHeaders = new DefaultHttpHeaders().add(HttpHeaders.Names.COOKIE, ClientCookieEncoder.LAX.encode(cookie1, cookie2));

        FullHttpRequest nettyRequestMock = mock(FullHttpRequest.class);
        doReturn(new DefaultHttpHeaders()).when(nettyRequestMock).headers();
        doReturn(trailingHeaders).when(nettyRequestMock).trailingHeaders();

        // when
        Set<Cookie> extractedCookies = HttpUtils.extractCookies(nettyRequestMock);

        // then
        assertThat(extractedCookies.contains(cookie1), is(true));
        assertThat(extractedCookies.contains(cookie2), is(true));
    }

    @Test
    public void extractCookies_returns_empty_set_if_no_cookies_defined() {
        // given
        FullHttpRequest nettyRequestMock = mock(FullHttpRequest.class);
        doReturn(new DefaultHttpHeaders()).when(nettyRequestMock).headers();
        doReturn(new DefaultHttpHeaders()).when(nettyRequestMock).trailingHeaders();

        // when
        Set<Cookie> extractedCookies = HttpUtils.extractCookies(nettyRequestMock);

        // then
        assertThat(extractedCookies, notNullValue());
        assertThat(extractedCookies.isEmpty(), is(true));
    }

    @Test
    public void extractCookies_does_not_use_trailing_headers_if_trailing_headers_is_null() {
        // given
        HttpRequest nettyRequestMock = mock(HttpRequest.class);
        doReturn(new DefaultHttpHeaders()).when(nettyRequestMock).headers();

        // when
        Set<Cookie> extractedCookies = HttpUtils.extractCookies(nettyRequestMock);

        // then
        assertThat(extractedCookies, notNullValue());
        assertThat(extractedCookies.isEmpty(), is(true));
    }

    @Test
    public void extractCookies_handles_cookie_values_leniently() {
        // given
        //these are cookie values seen in the wild...
        Cookie cookie1 = new DefaultCookie(UUID.randomUUID().toString(), "2094%3Az%7C2021%3Ab");
        Cookie cookie2 = new DefaultCookie(UUID.randomUUID().toString(), "geoloc=cc=US,rc=OR,tp=vhigh,tz=PST,la=45.4978,lo=-122.6937,bw=5000");
        Cookie cookie3 = new DefaultCookie(UUID.randomUUID().toString(), "\"dm=n.com&si=27431295-a282-4745-8cd5-542e7fce" +
                "429e&ss=1477551008358&sl=76&tt=437632&obo=12&sh=1477552753923%3D76%3A12%3A437632%2C1477552698670%3D75%3" +
                "A12%3A429879%2C1477552677137%3D74%3A12%3A426596%2C1477552672564%3D73%3A12%3A425585%2C1477552669893%3D72" +
                "%3A12%3A423456&bcn=%2F%2F3408178b.mpstat.us%2F&ld=1477552753923&r=http%3A%2F%2Fwww.nike.com%2Fbe%2Fde_de%" +
                "2F&ul=1477552756811\"");
        HttpHeaders headers = new DefaultHttpHeaders().add(HttpHeaders.Names.COOKIE, ClientCookieEncoder.LAX.encode(cookie1, cookie2, cookie3));

        HttpRequest nettyRequestMock = mock(HttpRequest.class);
        doReturn(headers).when(nettyRequestMock).headers();

        // when
        Set<Cookie> extractedCookies = HttpUtils.extractCookies(nettyRequestMock);

        // then
        assertThat(extractedCookies.contains(cookie1), is(true));
        assertThat(extractedCookies.contains(cookie2), is(true));
        assertThat(extractedCookies.contains(cookie3), is(true));
    }

    @Test
    public void extractContentChunks_works_for_request_that_is_also_HttpContent() {
        // given
        HttpRequest request = mock(FullHttpRequest.class);

        // when
        List<HttpContent> result = HttpUtils.extractContentChunks(request);

        // then
        assertThat(result, notNullValue());
        assertThat(result.size(), is(1));
        assertThat(result.get(0), is(request));
    }

    @Test
    public void extractContentChunks_returns_null_for_request_that_is_not_HttpContent() {
        // given
        HttpRequest request = mock(HttpRequest.class);

        // when
        List<HttpContent> result = HttpUtils.extractContentChunks(request);

        // then
        assertThat(result, nullValue());
    }

    @Test
    public void replaceUriPathVariables_works_as_expected() {
        // given
        RequestInfo<?> reqMock = mock(RequestInfo.class);
        Map<String, String> params = new HashMap<>();
        params.put("foo", "fooVal");
        params.put("bar", "barVal");
        doReturn(params).when(reqMock).getPathParams();
        doReturn("fooVal").when(reqMock).getPathParam("foo");
        doReturn("barVal").when(reqMock).getPathParam("bar");
        String uriWithPlaceholders = "/some/{foo}/path/{bar}";

        // when
        String result = HttpUtils.replaceUriPathVariables(reqMock, uriWithPlaceholders);

        // then
        assertThat(result, is("/some/fooVal/path/barVal"));
    }

    @DataProvider(value = {
        "null               |   null",
        "/some/path         |   null",
        "/some/path?        |   null",
        "/some/path?foo=bar |   foo=bar"
    }, splitBy = "\\|")
    @Test
    public void extractQueryString_works_as_expected(String uri, String expectedResult) {
        // when
        String result = HttpUtils.extractQueryString(uri);

        // then
        Assertions.assertThat(result).isEqualTo(expectedResult);
    }

}