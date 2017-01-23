package com.nike.riposte.server.http.impl;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.error.exception.PathParameterMatchingException;
import com.nike.riposte.server.error.exception.RequestContentDeserializationException;
import com.nike.riposte.server.http.RequestInfo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.util.reflection.Whitebox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.CharsetUtil;

import static junit.framework.TestCase.fail;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link RequestInfoImpl}
 */
@RunWith(DataProviderRunner.class)
public class RequestInfoImplTest {

    @Test
    public void uber_constructor_works_for_valid_values() {
        // given
        String uri = "/some/uri/path/%24foobar%26?notused=blah";
        HttpMethod method = HttpMethod.PATCH;
        Charset contentCharset = CharsetUtil.US_ASCII;
        HttpHeaders headers = new DefaultHttpHeaders().add("header1", "val1").add(HttpHeaders.Names.CONTENT_TYPE, "text/text charset=" + contentCharset.displayName());
        QueryStringDecoder queryParams = new QueryStringDecoder(uri + "?foo=bar&secondparam=secondvalue");
        Set<Cookie> cookies = new HashSet<>(Arrays.asList(new DefaultCookie("cookie1", "val1"), new DefaultCookie("cookie2", "val2")));
        Map<String, String> pathParams = Arrays.stream(new String[][] {{"pathParam1", "val1"}, {"pathParam2", "val2"}}).collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));
        String content = UUID.randomUUID().toString();
        byte[] contentBytes = content.getBytes();
        LastHttpContent chunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(contentBytes));
        chunk.trailingHeaders().add("trailingHeader1", "trailingVal1");
        List<HttpContent> contentChunks = Collections.singletonList(chunk);
        HttpVersion protocolVersion = HttpVersion.HTTP_1_1;
        boolean keepAlive = true;
        boolean fullRequest = true;
        boolean isMultipart = false;

        // when
        RequestInfoImpl<?> requestInfo = new RequestInfoImpl<>(uri, method, headers, chunk.trailingHeaders(), queryParams, cookies, pathParams, contentChunks, protocolVersion, keepAlive, fullRequest, isMultipart);

        // then
        assertThat("getUri should return passed in value", requestInfo.getUri(), is(uri));
        assertThat("getPath did not decode as expected", requestInfo.getPath(), is("/some/uri/path/$foobar&"));
        assertThat(requestInfo.getMethod(), is(method));
        assertThat(requestInfo.getHeaders(), is(headers));
        assertThat(requestInfo.getTrailingHeaders(), is(chunk.trailingHeaders()));
        assertThat(requestInfo.getQueryParams(), is(queryParams));
        assertThat(requestInfo.getCookies(), is(cookies));
        assertThat(requestInfo.pathTemplate, nullValue());
        assertThat(requestInfo.pathParams, is(pathParams));
        assertThat(requestInfo.getRawContentBytes(), is(contentBytes));
        assertThat(requestInfo.getRawContent(), is(content));
        assertThat(requestInfo.content, nullValue());
        assertThat(requestInfo.getContentCharset(), is(contentCharset));
        assertThat(requestInfo.getProtocolVersion(), is(protocolVersion));
        assertThat(requestInfo.isKeepAliveRequested(), is(keepAlive));
        assertThat(requestInfo.isCompleteRequestWithAllChunks, is(fullRequest));
        assertThat(requestInfo.isMultipart, is(isMultipart));
    }

    @Test
    public void uber_constructor_handles_null_values_in_graceful_way() {
        // when
        RequestInfoImpl<?> requestInfo = new RequestInfoImpl<>(null, null, null, null, null, null, null, null, null, false, true, false);

        // then
        assertThat(requestInfo.getUri(), is(""));
        assertThat(requestInfo.getPath(), is(""));
        assertThat(requestInfo.getMethod(), nullValue());
        assertThat(requestInfo.getHeaders(), notNullValue());
        assertThat(requestInfo.getHeaders().isEmpty(), is(true));
        assertThat(requestInfo.getTrailingHeaders(), notNullValue());
        assertThat(requestInfo.getTrailingHeaders().isEmpty(), is(true));
        assertThat(requestInfo.getQueryParams(), notNullValue());
        assertThat(requestInfo.getQueryParams().parameters().isEmpty(), is(true));
        assertThat(requestInfo.getCookies(), notNullValue());
        assertThat(requestInfo.getCookies().isEmpty(), is(true));
        assertThat(requestInfo.pathParams, notNullValue());
        assertThat(requestInfo.pathParams.isEmpty(), is(true));
        assertThat(requestInfo.rawContent, nullValue());
        assertThat(requestInfo.getContentCharset(), is(RequestInfo.DEFAULT_CONTENT_CHARSET));
        assertThat(requestInfo.getProtocolVersion(), nullValue());
    }

    @Test
    public void netty_helper_constructor_populates_request_info_appropriately() {
        // given
        String uri = "/some/uri/path/%24foobar%26?foo=bar&secondparam=secondvalue";
        Map<String, List<String>> expectedQueryParamMap = new HashMap<>();
        expectedQueryParamMap.put("foo", Arrays.asList("bar"));
        expectedQueryParamMap.put("secondparam", Arrays.asList("secondvalue"));
        HttpMethod method = HttpMethod.PATCH;
        String cookieName = UUID.randomUUID().toString();
        String cookieValue = UUID.randomUUID().toString();
        String content = UUID.randomUUID().toString();
        byte[] contentBytes = content.getBytes();
        Charset contentCharset = CharsetUtil.UTF_8;
        ByteBuf contentByteBuf = Unpooled.copiedBuffer(contentBytes);
        HttpHeaders headers = new DefaultHttpHeaders()
                .add("header1", "val1")
                .add(HttpHeaders.Names.CONTENT_TYPE, contentCharset)
                .add(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
                .add(HttpHeaders.Names.COOKIE, ClientCookieEncoder.LAX.encode(cookieName, cookieValue));
        HttpHeaders trailingHeaders = new DefaultHttpHeaders().add("trailingHeader1", "trailingVal1");
        HttpVersion protocolVersion = HttpVersion.HTTP_1_1;

        FullHttpRequest nettyRequestMock = mock(FullHttpRequest.class);
        doReturn(uri).when(nettyRequestMock).getUri();
        doReturn(method).when(nettyRequestMock).getMethod();
        doReturn(headers).when(nettyRequestMock).headers();
        doReturn(trailingHeaders).when(nettyRequestMock).trailingHeaders();
        doReturn(contentByteBuf).when(nettyRequestMock).content();
        doReturn(protocolVersion).when(nettyRequestMock).getProtocolVersion();

        // when
        RequestInfoImpl<?> requestInfo = new RequestInfoImpl<>(nettyRequestMock);

        // then
        assertThat("getUri was not the same value sent in", requestInfo.getUri(), is(uri));
        assertThat("getPath did not decode as expected", requestInfo.getPath(), is("/some/uri/path/$foobar&"));
        assertThat(requestInfo.getMethod(), is(method));
        assertThat(requestInfo.getHeaders(), is(headers));
        assertThat(requestInfo.getTrailingHeaders(), is(trailingHeaders));
        assertThat(requestInfo.getQueryParams(), notNullValue());
        assertThat(requestInfo.getQueryParams().parameters(), is(expectedQueryParamMap));
        assertThat(requestInfo.getCookies(), is(Sets.newHashSet(new DefaultCookie(cookieName, cookieValue))));
        assertThat(requestInfo.pathTemplate, nullValue());
        assertThat(requestInfo.pathParams.isEmpty(), is(true));
        assertThat(requestInfo.getRawContentBytes(), is(contentBytes));
        assertThat(requestInfo.getRawContent(), is(content));
        assertThat(requestInfo.content, nullValue());
        assertThat(requestInfo.getContentCharset(), is(contentCharset));
        assertThat(requestInfo.getProtocolVersion(), is(protocolVersion));
        assertThat(requestInfo.isKeepAliveRequested(), is(true));
    }

    @Test
    public void dummyInstanceForUnknownRequests_creates_instance_with_expected_data() {
        // when
        RequestInfo<?> dummyRequestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();

        // then
        assertThat(dummyRequestInfo.getUri(), is(RequestInfo.NONE_OR_UNKNOWN_TAG));
        assertThat(dummyRequestInfo.getHeaders().get(RequestInfo.NONE_OR_UNKNOWN_TAG), is("true"));
        assertThat(dummyRequestInfo.getQueryParams().parameters().get(RequestInfo.NONE_OR_UNKNOWN_TAG), is(Arrays.asList("true")));
    }

    @Test
    public void getRawContentLengthInBytes_returns_zero_if_request_not_complete() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.isCompleteRequestWithAllChunks = false;
        requestInfo.rawContentLengthInBytes = 42;

        // when
        int result = requestInfo.getRawContentLengthInBytes();

        // then
        assertThat(result, is(0));
    }

    @Test
    public void getRawContentLengthInBytes_returns_value_if_request_is_complete() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.isCompleteRequestWithAllChunks = true;
        requestInfo.rawContentLengthInBytes = 42;

        // when
        int result = requestInfo.getRawContentLengthInBytes();

        // then
        assertThat(result, is(42));
    }

    @Test
    @DataProvider(value = {
            "UTF-8",
            "UTF-16",
            "UTF-16BE",
            "UTF-16LE",
            "ISO-8859-1",
            "US-ASCII"
    })
    public void setupContentDeserializer_and_getContent_work_as_expected(String charsetName) throws IOException {
        // given
        ObjectMapper objectMapper = new ObjectMapper();
        TypeReference<TestContentObject> typeRef = new TypeReference<TestContentObject>() { };
        TestContentObject contentObj = new TestContentObject(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        String contentString = objectMapper.writeValueAsString(contentObj);
        RequestInfoImpl<TestContentObject> requestInfo = (RequestInfoImpl<TestContentObject>) RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.rawContentBytes = contentString.getBytes(Charset.forName(charsetName));

        // when
        requestInfo.setupContentDeserializer(objectMapper, typeRef);

        // then
        assertThat(requestInfo.getContent(), notNullValue());
        assertThat(requestInfo.getContent().foo, is(contentObj.foo));
        assertThat(requestInfo.getContent().bar, is(contentObj.bar));
    }

    @Test
    public void setupContentDeserializer_and_getContent_sets_content_to_null_if_deserializer_is_null() throws IOException {
        // given
        RequestInfo<TestContentObject> requestInfo = (RequestInfo<TestContentObject>) RequestInfoImpl.dummyInstanceForUnknownRequests();

        // when
        requestInfo.setupContentDeserializer(null, new TypeReference<TestContentObject>() {});

        // then
        assertThat(requestInfo.getContent(), nullValue());
    }

    @Test
    public void setupContentDeserializer_and_getContent_sets_content_to_null_if_type_ref_is_null() throws IOException {
        // given
        RequestInfo<TestContentObject> requestInfo = (RequestInfo<TestContentObject>) RequestInfoImpl.dummyInstanceForUnknownRequests();

        // when
        requestInfo.setupContentDeserializer(new ObjectMapper(), null);

        // then
        assertThat(requestInfo.getContent(), nullValue());
    }

    @Test
    public void setupContentDeserializer_and_getContent_sets_content_to_null_if_raw_content_is_null() throws IOException {
        // given
        RequestInfo<TestContentObject> requestInfo = (RequestInfo<TestContentObject>) RequestInfoImpl.dummyInstanceForUnknownRequests();
        assertThat(requestInfo.getRawContent(), nullValue());

        // when
        requestInfo.setupContentDeserializer(new ObjectMapper(), new TypeReference<TestContentObject>() {});

        // then
        assertThat(requestInfo.getContent(), nullValue());
    }

    @Test
    public void setupContentDeserializer_and_getContent_sets_content_to_null_if_raw_content_bytes_is_null() throws IOException {
        // given
        RequestInfo<TestContentObject> requestInfo = (RequestInfo<TestContentObject>) RequestInfoImpl.dummyInstanceForUnknownRequests();
        assertThat(requestInfo.getRawContentBytes(), nullValue());

        // when
        requestInfo.setupContentDeserializer(new ObjectMapper(), new TypeReference<TestContentObject>() {});

        // then
        assertThat(requestInfo.getContent(), nullValue());
    }

    @Test
    public void setupContentDeserializer_and_getContent_handle_non_class_Type() throws IOException {
        // given
        RequestInfo<TestContentObject> requestInfo = (RequestInfo<TestContentObject>) RequestInfoImpl.dummyInstanceForUnknownRequests();
        assertThat(requestInfo.getRawContentBytes(), nullValue());
        Type notAClassType = new Type() {
            @Override
            public String getTypeName() {
                return "FooType";
            }
        };
        TypeReference<TestContentObject> typeRefMock = mock(TypeReference.class);
        doReturn(notAClassType).when(typeRefMock).getType();

        // when
        requestInfo.setupContentDeserializer(new ObjectMapper(), typeRefMock);

        // then
        assertThat(requestInfo.getContent(), nullValue());
    }

    @Test
    public void getContent_returns_raw_string_if_content_type_is_CharSequence() throws IOException {
        // given
        RequestInfo<CharSequence> requestInfoSpy = spy((RequestInfo<CharSequence>) RequestInfoImpl.dummyInstanceForUnknownRequests());
        String rawContentString = UUID.randomUUID().toString();
        doReturn(rawContentString).when(requestInfoSpy).getRawContent();

        // when
        requestInfoSpy.setupContentDeserializer(new ObjectMapper(), new TypeReference<CharSequence>() {});

        // then
        assertThat(requestInfoSpy.getContent(), is(rawContentString));
        verify(requestInfoSpy).getRawContent();
        verify(requestInfoSpy, never()).getRawContentBytes();
    }

    @Test
    public void getContent_returns_raw_string_if_content_type_is_String() throws IOException {
        // given
        RequestInfo<String> requestInfoSpy = spy((RequestInfo<String>) RequestInfoImpl.dummyInstanceForUnknownRequests());
        String rawContentString = UUID.randomUUID().toString();
        doReturn(rawContentString).when(requestInfoSpy).getRawContent();

        // when
        requestInfoSpy.setupContentDeserializer(new ObjectMapper(), new TypeReference<String>() {});

        // then
        assertThat(requestInfoSpy.getContent(), is(rawContentString));
        verify(requestInfoSpy).getRawContent();
        verify(requestInfoSpy, never()).getRawContentBytes();
    }

    @Test
    public void getContent_returns_object_deserialized_from_raw_bytes_if_content_type_is_not_CharSequence_or_String() throws IOException {
        // given
        RequestInfo<TestContentObject> requestInfoSpy = spy((RequestInfo<TestContentObject>) RequestInfoImpl.dummyInstanceForUnknownRequests());
        ObjectMapper objectMapper = new ObjectMapper();
        TestContentObject expectedTco = new TestContentObject(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        byte[] rawBytes = objectMapper.writeValueAsString(expectedTco).getBytes(CharsetUtil.UTF_8);
        doReturn(rawBytes).when(requestInfoSpy).getRawContentBytes();
        ObjectMapper objectMapperSpy = spy(objectMapper);
        TypeReference<TestContentObject> typeRef = new TypeReference<TestContentObject>() {};

        // when
        requestInfoSpy.setupContentDeserializer(objectMapperSpy, typeRef);
        TestContentObject result = requestInfoSpy.getContent();

        // then
        assertThat(result, notNullValue());
        assertThat(result.foo, is(expectedTco.foo));
        assertThat(result.bar, is(expectedTco.bar));
        verify(requestInfoSpy).getRawContentBytes();
        verify(requestInfoSpy, never()).getRawContent();
        verify(objectMapperSpy).readValue(rawBytes, typeRef);
    }

    @Test
    public void getContent_throws_RequestContentDeserializationException_if_an_error_occurs_during_deserialization() throws IOException {
        // given
        RequestInfo<TestContentObject> requestInfoSpy = spy((RequestInfo<TestContentObject>) RequestInfoImpl.dummyInstanceForUnknownRequests());
        ObjectMapper objectMapperSpy = spy(new ObjectMapper());
        byte[] rawBytes = new byte[]{};
        doReturn(rawBytes).when(requestInfoSpy).getRawContentBytes();
        RuntimeException expectedRootCause = new RuntimeException("splat");
        doThrow(expectedRootCause).when(objectMapperSpy).readValue(any(byte[].class), any(TypeReference.class));
        HttpMethod method = HttpMethod.CONNECT;
        String path = UUID.randomUUID().toString();
        TypeReference<TestContentObject> typeRef = new TypeReference<TestContentObject>() {};
        doReturn(method).when(requestInfoSpy).getMethod();
        doReturn(path).when(requestInfoSpy).getPath();

        // when
        requestInfoSpy.setupContentDeserializer(objectMapperSpy, typeRef);
        Throwable actualEx = catchThrowable(() -> requestInfoSpy.getContent());

        // then
        assertThat(actualEx, notNullValue());
        assertThat(actualEx, instanceOf(RequestContentDeserializationException.class));
        RequestContentDeserializationException rcde = (RequestContentDeserializationException)actualEx;
        assertThat(rcde.desiredObjectType, sameInstance(typeRef));
        assertThat(rcde.httpMethod, is(String.valueOf(method)));
        assertThat(rcde.requestPath, is(path));
        assertThat(actualEx.getCause(), is(expectedRootCause));
    }

    @Test
    public void getContent_returns_null_if_request_is_not_complete_with_all_chunks() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.isCompleteRequestWithAllChunks = false;

        // when
        Object result = requestInfo.getContent();

        // then
        assertThat(result, nullValue());
    }

    @Test
    @DataProvider(value = {
            "/some/path/foo/bar  | /some/path/{param1}/{param2}  | foo | bar",
            "/some/path/foo/bar/ | /some/path/{param1}/{param2}  | foo | bar",
            "/some/path/foo/bar  | /some/path/{param1}/{param2}/ | foo | bar",
            "/some/path/foo/bar/ | /some/path/{param1}/{param2}/ | foo | bar",
            "/some/path/foo%26bar/%24test%26 | /some/path/{param1}/{param2} | foo&bar | $test&",
            "/stuff/foo/pre.bar.post | /stuff/{param1}/pre.{param2}.post | foo | bar"
    }, splitBy = "\\|")
    public void setPathParamsBasedOnPathTemplate_works_as_expected(String path, String pathTemplate, String expectedParam1, String expectedParam2) {
        // given
        RequestInfo<TestContentObject> requestInfo = new RequestInfoImpl<>(path, null, null, null, null, null, null, null, null, false, true, false);

        // when
        requestInfo.setPathParamsBasedOnPathTemplate(pathTemplate);

        // then
        assertThat(requestInfo.getPathParam("param1"), is(expectedParam1));
        assertThat(requestInfo.getPathParam("param2"), is(expectedParam2));
        assertThat(requestInfo.getPathParams().get("param1"), is(expectedParam1));
        assertThat(requestInfo.getPathParams().get("param2"), is(expectedParam2));
    }

    @Test(expected = PathParameterMatchingException.class)
    public void setPathParamsBasedOnPathTemplate_throws_PathParameterMatchingException_if_path_and_template_are_not_compatible() {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Whitebox.setInternalState(requestInfo, "path", "/some/path/42");

        // expect
        requestInfo.setPathParamsBasedOnPathTemplate("/other/path/{universeAnswer}");
        fail("Expected an exception but none was thrown");
    }

    @Test
    public void setPathParams_sets_map_as_expected() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Map<String, String> newMap = Arrays.asList(Pair.of("somekey", "someval")).stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight));

        // when
        requestInfo.setPathParams(newMap);

        // then
        assertThat(requestInfo.getPathParams(), is(newMap));
    }

    @Test
    public void setPathParams_sets_empty_map_if_passed_null() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Map<String, String> oldMap = new HashMap<>();
        oldMap.put("key", "val");
        requestInfo.pathParams = oldMap;
        assertThat(requestInfo.getPathParams(), is(oldMap));

        // when
        requestInfo.setPathParams(null);

        // then
        assertThat(requestInfo.getPathParams(), notNullValue());
        assertThat(requestInfo.getPathParams().isEmpty(), is(true));
    }

    @Test(expected = IllegalStateException.class)
    public void addContentChunk_throws_IllegalStateException_if_request_is_complete_with_all_chunks() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.isCompleteRequestWithAllChunks = true;

        // expect
        requestInfo.addContentChunk(mock(HttpContent.class));
        fail("Expected an IllegalStateException, but no exception was thrown");
    }

    @Test
    public void addContentChunk_adds_chunk_to_list_and_retains_it_for_chunks_that_are_not_the_last() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.isCompleteRequestWithAllChunks = false;
        HttpContent chunk1Mock = mock(HttpContent.class);
        HttpContent chunk2Mock = mock(HttpContent.class);
        doReturn(mock(ByteBuf.class)).when(chunk1Mock).content();
        doReturn(mock(ByteBuf.class)).when(chunk2Mock).content();

        // when
        requestInfo.addContentChunk(chunk1Mock);
        requestInfo.addContentChunk(chunk2Mock);

        // then
        assertThat(requestInfo.contentChunks.size(), is(2));
        assertThat(requestInfo.contentChunks.get(0), is(chunk1Mock));
        assertThat(requestInfo.contentChunks.get(1), is(chunk2Mock));
        assertThat(requestInfo.isCompleteRequestWithAllChunks(), is(false));
        assertThat(requestInfo.getRawContentBytes(), nullValue());
        assertThat(requestInfo.getRawContent(), nullValue());
        verify(chunk1Mock).retain();
        verify(chunk1Mock).content();
        verifyNoMoreInteractions(chunk1Mock);
        verify(chunk2Mock).retain();
        verify(chunk2Mock).content();
        verifyNoMoreInteractions(chunk2Mock);
    }

    @Test
    public void addContentChunk_and_getRawConent_and_getRawContentBytes_work_as_expected_for_last_chunk() throws IOException {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.isCompleteRequestWithAllChunks = false;
        String chunk1String = UUID.randomUUID().toString();
        String lastChunkString = UUID.randomUUID().toString();
        byte[] chunk1Bytes = chunk1String.getBytes();
        byte[] lastChunkBytes = lastChunkString.getBytes();
        HttpContent chunk1 = new DefaultHttpContent(Unpooled.copiedBuffer(chunk1Bytes));
        HttpContent lastChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(lastChunkBytes));
        assertThat(chunk1.refCnt(), is(1));
        assertThat(lastChunk.refCnt(), is(1));
        assertThat(requestInfo.getRawContentBytes(), nullValue());
        assertThat(requestInfo.getRawContent(), nullValue());

        // when
        requestInfo.addContentChunk(chunk1);
        requestInfo.addContentChunk(lastChunk);

        // then
        assertThat(chunk1.refCnt(), is(2));
        assertThat(lastChunk.refCnt(), is(2));
        assertThat(requestInfo.contentChunks.size(), is(2));
        assertThat(requestInfo.isCompleteRequestWithAllChunks(), is(true));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(chunk1Bytes);
        baos.write(lastChunkBytes);
        assertThat(requestInfo.getRawContentBytes(), is(baos.toByteArray()));
        String rawContentString = requestInfo.getRawContent();
        assertThat(requestInfo.getRawContent(), is(chunk1String + lastChunkString));
        assertThat(requestInfo.getRawContent() == rawContentString, is(true)); // Verify that the raw content string is cached the first time it's loaded and reused for subsequent calls
        assertThat(chunk1.refCnt(), is(1));
        assertThat(lastChunk.refCnt(), is(1));
    }

    @Test
    public void addContentChunk_adds_last_chunk_trailing_headers() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.isCompleteRequestWithAllChunks = false;
        LastHttpContent lastChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(UUID.randomUUID().toString(), CharsetUtil.UTF_8));
        String headerKey = UUID.randomUUID().toString();
        List<String> headerVal = Arrays.asList(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        lastChunk.trailingHeaders().add(headerKey, headerVal);

        // when
        requestInfo.addContentChunk(lastChunk);

        // then
        assertThat(requestInfo.trailingHeaders.names().size(), is(1));
        assertThat(requestInfo.trailingHeaders.getAll(headerKey), is(headerVal));
    }

    @Test
    public void addContentChunk_adds_chunk_content_length_to_rawContentLengthInBytes() throws IOException {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.isCompleteRequestWithAllChunks = false;
        String chunk1String = UUID.randomUUID().toString();
        String lastChunkString = UUID.randomUUID().toString();
        byte[] chunk1Bytes = chunk1String.getBytes();
        byte[] lastChunkBytes = lastChunkString.getBytes();
        HttpContent chunk1 = new DefaultHttpContent(Unpooled.copiedBuffer(chunk1Bytes));
        HttpContent lastChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(lastChunkBytes));

        // when
        requestInfo.addContentChunk(chunk1);
        requestInfo.addContentChunk(lastChunk);

        // then
        assertThat(requestInfo.contentChunks.size(), is(2));
        assertThat(requestInfo.isCompleteRequestWithAllChunks(), is(true));
        assertThat(requestInfo.getRawContentLengthInBytes(), is(chunk1Bytes.length + lastChunkBytes.length));
    }

    @Test(expected = IllegalStateException.class)
    public void addContentChunk_throws_IllegalStateException_if_requestInfo_trailingHeaders_is_already_populated_when_last_chunk_arrives() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.isCompleteRequestWithAllChunks = false;
        LastHttpContent lastChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(UUID.randomUUID().toString(), CharsetUtil.UTF_8));
        requestInfo.trailingHeaders.add("somekey", "someval");

        // expect
        requestInfo.addContentChunk(lastChunk);
        fail("Expected an IllegalStateException, but no exception was thrown");
    }

    @Test
    public void addContentChunk_does_not_throw_IllegalStateException_if_requestInfo_trailingHeaders_is_already_populated_when_last_chunk_arrives_if_same_instance() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.isCompleteRequestWithAllChunks = false;
        LastHttpContent lastChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(UUID.randomUUID().toString(), CharsetUtil.UTF_8));
        lastChunk.trailingHeaders().add("somekey", "someval");
        requestInfo.trailingHeaders = lastChunk.trailingHeaders();

        // when
        requestInfo.addContentChunk(lastChunk);

        // then
        assertThat(requestInfo.trailingHeaders, is(lastChunk.trailingHeaders()));
    }

    private static final String KNOWN_MULTIPART_DATA_CONTENT_TYPE_HEADER = "multipart/form-data; boundary=OnbiRR2K8-ZzW3rj0wLh_r9td9w_XD34jBR";
    private static final String KNOWN_MULTIPART_DATA_NAME = "someFile";
    private static final String KNOWN_MULTIPART_DATA_FILENAME = "someFile.txt";
    private static final String KNOWN_MULTIPART_DATA_ATTR_UUID = UUID.randomUUID().toString();
    private static final String KNOWN_MULTIPART_DATA_BODY =
            "--OnbiRR2K8-ZzW3rj0wLh_r9td9w_XD34jBR\n"+
            "Content-Disposition: form-data; name=\"" + KNOWN_MULTIPART_DATA_NAME + "\"; filename=\"" + KNOWN_MULTIPART_DATA_FILENAME + "\"\n"+
            "Content-Type: application/octet-stream\n"+
            "Content-Transfer-Encoding: binary\n"+
            "\n"+
            KNOWN_MULTIPART_DATA_ATTR_UUID + "\n" +
            "--OnbiRR2K8-ZzW3rj0wLh_r9td9w_XD34jBR--\n";

    @Test
    public void getMultipartParts_works_as_expected_with_known_valid_data() throws IOException {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Whitebox.setInternalState(requestInfo, "isMultipart", true);
        Whitebox.setInternalState(requestInfo, "contentCharset", CharsetUtil.UTF_8);
        Whitebox.setInternalState(requestInfo, "protocolVersion", HttpVersion.HTTP_1_1);
        Whitebox.setInternalState(requestInfo, "method", HttpMethod.POST);
        requestInfo.isCompleteRequestWithAllChunks = true;
        requestInfo.rawContentBytes = KNOWN_MULTIPART_DATA_BODY.getBytes(CharsetUtil.UTF_8);
        requestInfo.getHeaders().set("Content-Type", KNOWN_MULTIPART_DATA_CONTENT_TYPE_HEADER);

        // when
        List<InterfaceHttpData> result = requestInfo.getMultipartParts();

        // then
        assertThat(result, notNullValue());
        assertThat(result.size(), is(1));
        InterfaceHttpData data = result.get(0);
        assertThat(data, instanceOf(FileUpload.class));
        FileUpload fileUploadData = (FileUpload)data;
        assertThat(fileUploadData.getName(), is(KNOWN_MULTIPART_DATA_NAME));
        assertThat(fileUploadData.getFilename(), is(KNOWN_MULTIPART_DATA_FILENAME));
        assertThat(fileUploadData.getString(CharsetUtil.UTF_8), is(KNOWN_MULTIPART_DATA_ATTR_UUID));
    }

    @Test
    public void getMultipartParts_works_as_expected_with_known_empty_data() throws IOException {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Whitebox.setInternalState(requestInfo, "isMultipart", true);
        Whitebox.setInternalState(requestInfo, "contentCharset", CharsetUtil.UTF_8);
        Whitebox.setInternalState(requestInfo, "protocolVersion", HttpVersion.HTTP_1_1);
        Whitebox.setInternalState(requestInfo, "method", HttpMethod.POST);
        requestInfo.isCompleteRequestWithAllChunks = true;
        requestInfo.rawContentBytes = null;
        requestInfo.getHeaders().set("Content-Type", KNOWN_MULTIPART_DATA_CONTENT_TYPE_HEADER);

        // when
        List<InterfaceHttpData> result = requestInfo.getMultipartParts();

        // then
        assertThat(result, notNullValue());
        assertThat(result.isEmpty(), is(true));
    }

    @Test(expected = IllegalStateException.class)
    public void getMultipartParts_explodes_if_multipartData_had_been_released() throws IOException {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Whitebox.setInternalState(requestInfo, "isMultipart", true);
        Whitebox.setInternalState(requestInfo, "contentCharset", CharsetUtil.UTF_8);
        Whitebox.setInternalState(requestInfo, "protocolVersion", HttpVersion.HTTP_1_1);
        Whitebox.setInternalState(requestInfo, "method", HttpMethod.POST);
        requestInfo.isCompleteRequestWithAllChunks = true;
        requestInfo.rawContentBytes = KNOWN_MULTIPART_DATA_BODY.getBytes(CharsetUtil.UTF_8);
        requestInfo.getHeaders().set("Content-Type", KNOWN_MULTIPART_DATA_CONTENT_TYPE_HEADER);
        List<InterfaceHttpData> result = requestInfo.getMultipartParts();
        assertThat(result, notNullValue());
        assertThat(result.size(), is(1));

        // expect
        requestInfo.releaseMultipartData();
        requestInfo.getMultipartParts();
        fail("Expected an error, but none was thrown");
    }

    @Test
    public void getMultipartParts_returns_data_from_multipartData() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Whitebox.setInternalState(requestInfo, "isMultipart", true);
        requestInfo.isCompleteRequestWithAllChunks = true;
        HttpPostMultipartRequestDecoder multipartDataMock = mock(HttpPostMultipartRequestDecoder.class);
        List<InterfaceHttpData> dataListMock = mock(List.class);
        doReturn(dataListMock).when(multipartDataMock).getBodyHttpDatas();
        requestInfo.multipartData = multipartDataMock;

        // when
        List<InterfaceHttpData> result = requestInfo.getMultipartParts();

        // then
        assertThat(result, is(dataListMock));
    }

    @Test
    public void getMultipartParts_returns_null_if_isMultipart_is_false() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Whitebox.setInternalState(requestInfo, "isMultipart", false);
        requestInfo.isCompleteRequestWithAllChunks = true;

        // when
        List<InterfaceHttpData> result = requestInfo.getMultipartParts();

        // then
        assertThat(result, nullValue());
    }

    @Test
    public void getMultipartParts_returns_null_if_isCompleteRequestWithAllChunks_is_false() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Whitebox.setInternalState(requestInfo, "isMultipart", true);
        requestInfo.isCompleteRequestWithAllChunks = false;

        // when
        List<InterfaceHttpData> result = requestInfo.getMultipartParts();

        // then
        assertThat(result, nullValue());
    }

    @Test
    public void releaseContentChunks_calls_release_on_each_chunk_and_calls_clear_on_chunk_list() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        List<HttpContent> contentChunkList = Arrays.asList(mock(HttpContent.class), mock(HttpContent.class));
        requestInfo.contentChunks.addAll(contentChunkList);
        assertThat(requestInfo.contentChunks.size(), is(contentChunkList.size()));

        // when
        requestInfo.releaseContentChunks();

        // then
        for (HttpContent chunkMock : contentChunkList) {
            verify(chunkMock).release();
        }
        assertThat(requestInfo.contentChunks.isEmpty(), is(true));
    }

    @Test
    public void releaseMultipartData_works_as_expected_and_does_nothing_on_subsequent_calls() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.multipartDataIsDestroyed = false;
        HttpPostMultipartRequestDecoder multipartDataMock = mock(HttpPostMultipartRequestDecoder.class);
        requestInfo.multipartData = multipartDataMock;

        // when
        requestInfo.releaseMultipartData();

        // then
        assertThat(requestInfo.multipartDataIsDestroyed, is(true));
        verify(multipartDataMock).destroy();

        // and when subsequent calls come in
        requestInfo.releaseMultipartData();

        // then nothing else happened
        verifyNoMoreInteractions(multipartDataMock);
    }

    @Test
    public void releaseMultipartData_does_nothing_if_multipartData_is_null() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        requestInfo.multipartDataIsDestroyed = false;
        requestInfo.multipartData = null;

        // when
        requestInfo.releaseMultipartData();

        // then
        assertThat(requestInfo.multipartDataIsDestroyed, is(false));
    }

    @Test
    public void releaseAllResources_calls_both_releaseContentChunks_and_releaseMultipartData() {
        // given
        RequestInfoImpl<?> requestInfoSpy = spy(RequestInfoImpl.dummyInstanceForUnknownRequests());

        // when
        requestInfoSpy.releaseAllResources();

        // then
        verify(requestInfoSpy).releaseContentChunks();
        verify(requestInfoSpy).releaseMultipartData();
    }

    @Test
    public void addRequestAttribute_works_as_expected() {
        // given
        RequestInfoImpl<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        String attributeName = UUID.randomUUID().toString();
        String attributeValue = UUID.randomUUID().toString();

        // when
        requestInfo.addRequestAttribute(attributeName, attributeValue);

        // then
        assertThat(requestInfo.getRequestAttributes().get(attributeName), is(attributeValue));
    }

    public static class TestContentObject {
        public final String foo;
        public final String bar;

        public TestContentObject(String foo, String bar) {
            this.foo = foo;
            this.bar = bar;
        }

        private TestContentObject() {
            // For jackson deserialization
            this(null, null);
        }
    }

}