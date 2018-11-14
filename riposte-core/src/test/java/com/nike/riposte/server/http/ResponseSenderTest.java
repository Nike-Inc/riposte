package com.nike.riposte.server.http;

import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.error.handler.ErrorResponseBodySerializer;
import com.nike.riposte.server.http.impl.FullResponseInfo;
import com.nike.riposte.server.testutils.TestUtil;
import com.nike.wingtips.Span;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(DataProviderRunner.class)
public class ResponseSenderTest {

    ResponseSender responseSender;
    ChannelHandlerContext ctx;
    ResponseInfo<?> responseInfo;
    HttpResponse actualResponseObject;
    RequestInfo requestInfo;
    HttpHeaders httpHeaders;
    DistributedTracingConfig<Span> distributedTracingConfigMock;

    @Before
    public void setup() {
        ctx = TestUtil.mockChannelHandlerContext().mockContext;
        distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        responseSender = new ResponseSender(null, null, distributedTracingConfigMock);

        requestInfo = mock(RequestInfo.class);
        httpHeaders = new DefaultHttpHeaders();

        responseInfo = new FullResponseInfo();
        responseInfo.setDesiredContentWriterEncoding(Charset.forName("UTF-8"));
        responseInfo.setDesiredContentWriterMimeType("application/json");

        when(requestInfo.getHeaders()).thenReturn(httpHeaders);
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_distributedTracingConfig_is_null() {
        // when
        Throwable ex = catchThrowable(
            () -> new ResponseSender(
                mock(ObjectMapper.class), mock(ErrorResponseBodySerializer.class), null
            )
        );

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("distributedTracingConfig cannot be null");
    }

    @DataProvider(value = {
            "-1",
            "0",
            "1",
            "2",
            "4"
    }, splitBy = "\\|")
    @Test
    public void synchronizeAndSetupResponseInfoAndFirstChunk_shouldSetCookieHeadersWhenCookiesPresent(int numberOfCookies) {
        // given
        actualResponseObject = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        when(requestInfo.isKeepAliveRequested()).thenReturn(false);

        Set<Cookie> cookies = createCookies(numberOfCookies);
        responseInfo.setCookies(cookies);

        // when
        responseSender.synchronizeAndSetupResponseInfoAndFirstChunk(responseInfo, actualResponseObject, requestInfo, ctx);

        // then
        List<String> setCookieHeaderValues = actualResponseObject.headers().getAll(HttpHeaders.Names.SET_COOKIE);

        if (numberOfCookies > 0) {
            assertThat(setCookieHeaderValues).isNotEmpty();
            assertThat(setCookieHeaderValues.size()).isEqualTo(numberOfCookies);
        } else {
            assertThat(setCookieHeaderValues).isEmpty();
        }

        if (!setCookieHeaderValues.isEmpty()) {
            DefaultCookie[] originalCookies = cookies.toArray(new DefaultCookie[cookies.size()]);
            for (int x = 0; x < numberOfCookies; x++) {
                assertThat(setCookieHeaderValues.get(x)).startsWith(originalCookies[x].name() + "=" + originalCookies[x].value());
                assertThat(setCookieHeaderValues.get(x)).contains("Max-Age=" + originalCookies[x].maxAge());
                if (originalCookies[x].isHttpOnly()) {
                    assertThat(setCookieHeaderValues.get(x)).contains("HTTPOnly");
                }
            }
        }
    }

    private Set<Cookie> createCookies(int numberOfCookies) {
        if (numberOfCookies < 0) {
            return null;
        }

        Set<Cookie> cookies = new HashSet<>();

        for (int x = 0; x < numberOfCookies; x++) {
            Cookie cookie = new DefaultCookie(UUID.randomUUID().toString(), UUID.randomUUID().toString());
            cookie.setHttpOnly(new Random().ints(0, 1000).findAny().getAsInt() % 2 == 0);
            cookie.setMaxAge(new Random().longs(0, 1000).findAny().getAsLong());
            cookies.add(cookie);
        }

        return cookies;
    }

}
