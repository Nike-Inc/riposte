package com.nike.backstopper.handler.riposte;

import com.nike.backstopper.handler.RequestInfoForLogging.GetBodyException;
import com.nike.internal.util.MapBuilder;
import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;
import com.nike.riposte.testutils.Whitebox;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link RequestInfoForLoggingRiposteAdapter}
 */
public class RequestInfoForLoggingRiposteAdapterTest {
    private RequestInfoForLoggingRiposteAdapter adapter;
    private RequestInfo requestInfoSpy;

    @Before
    public void beforeMethod() {
        requestInfoSpy = spy(new RequestInfoImpl(null, null, null, null, null, null, null, null, null, false, true, false));
        adapter = new RequestInfoForLoggingRiposteAdapter(requestInfoSpy);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorThrowsIllegalArgumentExceptionIfPassedNullRequest() {
        new RequestInfoForLoggingRiposteAdapter(null);
    }

    private void setFieldOnRequestInfo(String fieldName, Object value) {
        Whitebox.setInternalState(requestInfoSpy, fieldName, value);
    }

    @Test
    public void getRequestUriDelegatesToRequestInfoPath() {
        String expectedValue = UUID.randomUUID().toString();
        setFieldOnRequestInfo("path", expectedValue);
        assertThat(adapter.getRequestUri(), is(expectedValue));
    }

    @Test
    public void getRequestHttpMethodDelegatesToRequestInfo() {
        String expectedValue = UUID.randomUUID().toString();
        setFieldOnRequestInfo("method", HttpMethod.valueOf(expectedValue));
        assertThat(adapter.getRequestHttpMethod(), is(expectedValue));
    }

    @Test
    public void getQueryStringDelegatesToRequestInfo() {
        String expectedValue = UUID.randomUUID().toString();
        setFieldOnRequestInfo("uri", "/some/path?" + expectedValue);
        assertThat(adapter.getQueryString(), is(expectedValue));
    }

    @Test
    public void getHeaderMapDelegatesToRequestInfoAndCachesResult() {
        Map<String, List<String>> expectedHeaderMap = new TreeMap<>(MapBuilder.<String, List<String>>builder()
                                                                              .put("header1", Arrays.asList("h1val1"))
                                                                              .put("header2", Arrays.asList("h2val1", "h2val2"))
                                                                              .build());

        HttpHeaders nettyHeaders = new DefaultHttpHeaders();
        for (Map.Entry<String, List<String>> headerEntry : expectedHeaderMap.entrySet()) {
            nettyHeaders.add(headerEntry.getKey(), headerEntry.getValue());
        }
        setFieldOnRequestInfo("headers", nettyHeaders);
        Map<String, List<String>> actualHeaderMap = adapter.getHeadersMap();
        assertThat(actualHeaderMap, is(expectedHeaderMap));
        assertThat(adapter.getHeadersMap(), sameInstance(actualHeaderMap));
    }

    @Test
    public void getHeaderMapReturnsEmptyMapIfNettyHeaderNamesReturnsNull() {
        HttpHeaders headersMock = mock(HttpHeaders.class);
        doReturn(null).when(headersMock).names();
        setFieldOnRequestInfo("headers", headersMock);
        Map<String, List<String>> actualHeaderMap = adapter.getHeadersMap();
        assertThat(actualHeaderMap, notNullValue());
        assertThat(actualHeaderMap.isEmpty(), is(true));
    }

    @Test
    public void getHeaderMapIgnoresHeadersWhereNettyGetHeadersMethodReturnsNull() {
        Map<String, List<String>> expectedHeaderMap = new TreeMap<>(MapBuilder.<String, List<String>>builder()
                                                                              .put("header1", Arrays.asList("h1val1"))
                                                                              .build());
        HttpHeaders headersMock = mock(HttpHeaders.class);
        doReturn(new HashSet<>(Arrays.asList("header1", "header2"))).when(headersMock).names();
        doReturn(expectedHeaderMap.get("header1")).when(headersMock).getAll("header1");
        doReturn(null).when(headersMock).getAll("header2");
        setFieldOnRequestInfo("headers", headersMock);
        assertThat(adapter.getHeadersMap(), is(expectedHeaderMap));
    }

    @Test
    public void getHeaderDelegatesToRequestInfo() {
        String headerName = "someheader";
        String expectedValue = UUID.randomUUID().toString();
        HttpHeaders headersMock = mock(HttpHeaders.class);
        doReturn(expectedValue).when(headersMock).get(headerName);
        setFieldOnRequestInfo("headers", headersMock);
        assertThat(adapter.getHeader(headerName), is(expectedValue));
    }

    @Test
    public void getHeadersDelegatesToRequestInfo() {
        Pair<String, List<String>> header1 = Pair.of("header1", Arrays.asList("h1val1"));
        Pair<String, List<String>> header2 = Pair.of("header2", Arrays.asList("h2val1", "h2val2"));
        Map<String, List<String>> expectedHeaderMap = new TreeMap<>(MapBuilder.<String, List<String>>builder()
                                                                              .put(header1.getKey(), header1.getValue())
                                                                              .put(header2.getKey(), header2.getValue())
                                                                              .build());
        HttpHeaders headersMock = mock(HttpHeaders.class);
        doReturn(expectedHeaderMap.keySet()).when(headersMock).names();
        for (Map.Entry<String, List<String>> entry : expectedHeaderMap.entrySet()) {
            doReturn(entry.getValue()).when(headersMock).getAll(entry.getKey());
        }
        setFieldOnRequestInfo("headers", headersMock);
        assertThat(adapter.getHeaders(header1.getKey()), is(header1.getValue()));
        assertThat(adapter.getHeaders(header2.getKey()), is(header2.getValue()));
    }

    @Test
    public void getAttributeReturnsNull() {
        assertThat(adapter.getAttribute("someattribute"), nullValue());
    }

    @Test
    public void getBody_delegates_to_request_getRawContent() throws GetBodyException {
        // given
        String content = UUID.randomUUID().toString();
        doReturn(content).when(requestInfoSpy).getRawContent();

        // when
        String result = adapter.getBody();

        // then
        verify(requestInfoSpy).getRawContent();
        assertThat(result, is(content));
    }

    @Test
    public void getBody_returns_empty_string_if_request_getRawContent_returns_null() throws GetBodyException {
        // given
        doReturn(null).when(requestInfoSpy).getRawContent();

        // when
        String result = adapter.getBody();

        // then
        verify(requestInfoSpy).getRawContent();
        Assertions.assertThat(result)
                  .isNotNull()
                  .isEmpty();
    }

    @Test
    public void getBody_throws_GetBodyException_if_request_getRawContent_throws_exception() {
        // given
        RuntimeException expectedCause = new RuntimeException("intentional test exception");
        doThrow(expectedCause).when(requestInfoSpy).getRawContent();

        // when
        Throwable ex = catchThrowable(() -> adapter.getBody());

        // then
        verify(requestInfoSpy).getRawContent();
        Assertions.assertThat(ex)
                  .isInstanceOf(GetBodyException.class)
                  .hasCause(expectedCause);
    }
}