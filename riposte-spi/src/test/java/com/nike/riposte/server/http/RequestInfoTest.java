package com.nike.riposte.server.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the default functionality of {@link RequestInfo}
 *
 * @author Nic Munroe
 */
public class RequestInfoTest {

    private <T> RequestInfo<T> getSpy() {
        return spy(new RequestInfoForTesting<>());
    }

    @Test
    public void getQueryParamSingle_returns_null_if_queryParams_is_null() {
        // given
        RequestInfo<?> requestInfoSpy = getSpy();
        doReturn(null).when(requestInfoSpy).getQueryParams();

        // when
        String value = requestInfoSpy.getQueryParamSingle("foo");

        // then
        assertThat(value, nullValue());
    }

    @Test
    public void getQueryParamSingle_returns_null_if_queryParams_dot_parameters_is_null() {
        // given
        QueryStringDecoder queryParamsMock = mock(QueryStringDecoder.class);
        doReturn(null).when(queryParamsMock).parameters();
        RequestInfo<?> requestInfoSpy = getSpy();
        doReturn(queryParamsMock).when(requestInfoSpy).getQueryParams();

        // when
        String value = requestInfoSpy.getQueryParamSingle("foo");

        // then
        assertThat(value, nullValue());
    }

    @Test
    public void getQueryParamSingle_returns_null_if_param_value_list_is_null() {
        // given
        QueryStringDecoder queryParamsMock = mock(QueryStringDecoder.class);
        Map<String, List<String>> params = new HashMap<>();
        doReturn(params).when(queryParamsMock).parameters();
        RequestInfo<?> requestInfoSpy = getSpy();
        doReturn(queryParamsMock).when(requestInfoSpy).getQueryParams();

        // when
        String value = requestInfoSpy.getQueryParamSingle("foo");

        // then
        assertThat(value, nullValue());
    }

    @Test
    public void getQueryParamSingle_returns_null_if_param_value_list_is_empty() {
        // given
        QueryStringDecoder queryParamsMock = mock(QueryStringDecoder.class);
        Map<String, List<String>> params = new HashMap<>();
        params.put("foo", Collections.emptyList());
        doReturn(params).when(queryParamsMock).parameters();
        RequestInfo<?> requestInfoSpy = getSpy();
        doReturn(queryParamsMock).when(requestInfoSpy).getQueryParams();

        // when
        String value = requestInfoSpy.getQueryParamSingle("foo");

        // then
        assertThat(value, nullValue());
    }

    @Test
    public void getQueryParamSingle_returns_first_item_if_param_value_list_has_multiple_entries() {
        // given
        QueryStringDecoder queryParamsMock = mock(QueryStringDecoder.class);
        Map<String, List<String>> params = new HashMap<>();
        params.put("foo", Arrays.asList("bar", "stuff"));
        doReturn(params).when(queryParamsMock).parameters();
        RequestInfo<?> requestInfoSpy = getSpy();
        doReturn(queryParamsMock).when(requestInfoSpy).getQueryParams();

        // when
        String value = requestInfoSpy.getQueryParamSingle("foo");

        // then
        assertThat(value, is("bar"));
    }

    @Test
    public void getPathParam_works_as_expected() {
        // given
        RequestInfo<?> requestInfoSpy = getSpy();
        Map<String, String> pathParamsMock = mock(Map.class);
        String key = UUID.randomUUID().toString();
        String value = UUID.randomUUID().toString();
        doReturn(value).when(pathParamsMock).get(key);
        doReturn(pathParamsMock).when(requestInfoSpy).getPathParams();

        // when
        String actual = requestInfoSpy.getPathParam(key);

        // then
        assertThat(actual, is(value));
        verify(pathParamsMock).get(key);
    }

    private static class RequestInfoForTesting<T> implements RequestInfo<T> {

        @Override
        public @NotNull String getUri() {
            return "fooUri";
        }

        @Override
        public @NotNull String getPath() {
            return "/fooPath";
        }

        @Override
        public @Nullable HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public @NotNull HttpHeaders getHeaders() {
            return new DefaultHttpHeaders();
        }

        @Override
        public @NotNull HttpHeaders getTrailingHeaders() {
            return new DefaultHttpHeaders();
        }

        @Override
        public @NotNull QueryStringDecoder getQueryParams() {
            return new QueryStringDecoder(getUri());
        }

        @Override
        public @NotNull Map<String, String> getPathParams() {
            return Collections.emptyMap();
        }

        @Override
        public @NotNull RequestInfo<T> setPathParamsBasedOnPathTemplate(@NotNull String pathTemplate) {
            return this;
        }

        @Override
        public int getRawContentLengthInBytes() {
            return 0;
        }

        @Override
        public @Nullable byte[] getRawContentBytes() {
            return null;
        }

        @Override
        public @Nullable String getRawContent() {
            return null;
        }

        @Override
        public @Nullable T getContent() {
            return null;
        }

        @Override
        public boolean isMultipartRequest() {
            return false;
        }

        @Override
        public @Nullable List<InterfaceHttpData> getMultipartParts() {
            return null;
        }

        @Override
        public @NotNull RequestInfo<T> setupContentDeserializer(
            @NotNull ObjectMapper deserializer, @NotNull TypeReference<T> typeReference
        ) {
            return this;
        }

        @Override
        public boolean isContentDeserializerSetup() {
            return false;
        }

        @Override
        public @NotNull Set<Cookie> getCookies() {
            return Collections.emptySet();
        }

        @Override
        public @NotNull Charset getContentCharset() {
            return DEFAULT_CONTENT_CHARSET;
        }

        @Override
        public @Nullable HttpVersion getProtocolVersion() {
            return null;
        }

        @Override
        public boolean isKeepAliveRequested() {
            return false;
        }

        @Override
        public int addContentChunk(@NotNull HttpContent chunk) {
            return 0;
        }

        @Override
        public boolean isCompleteRequestWithAllChunks() {
            return false;
        }

        @Override
        public void addRequestAttribute(@NotNull String attributeName, @NotNull Object attributeValue) {
        }

        @Override
        public @NotNull Map<String, Object> getRequestAttributes() {
            return Collections.emptyMap();
        }

        @Override
        public void releaseAllResources() {
        }

        @Override
        public void releaseContentChunks() {
        }

        @Override
        public void releaseMultipartData() {
        }

        @Override
        public @NotNull String getPathTemplate() {
            return "";
        }
    }
}