package com.nike.riposte.server.config.distributedtracing;

import com.nike.riposte.server.http.RequestInfo;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import static com.nike.riposte.server.config.distributedtracing.ProxyRouterSpanNamingAndTaggingStrategy.SPAN_NAME_FOR_OUTBOUND_PROXY_CALL_REQ_ATTR_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests the functionality of {@link ProxyRouterSpanNamingAndTaggingStrategy}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class ProxyRouterSpanNamingAndTaggingStrategyTest {
    
    @Test
    public void default_annotation_method_implementations_return_expected_values() {
        // given
        ProxyRouterSpanNamingAndTaggingStrategy<?> defaultImpl = new DefaultProxyRouterSpanNamingAndTaggingStrategy();
        HttpResponse responseMock = mock(HttpResponse.class);
        Throwable errorMock = mock(Throwable.class);

        // expect
        assertThat(defaultImpl.shouldAddConnStartAnnotation()).isTrue();
        assertThat(defaultImpl.connStartAnnotationName()).isEqualTo("conn.start");
        assertThat(defaultImpl.shouldAddConnFinishAnnotation()).isTrue();
        assertThat(defaultImpl.connFinishAnnotationName()).isEqualTo("conn.finish");

        verifyNoInteractions(responseMock, errorMock);
    }

    @Test
    public void setSpanNameOverrideForOutboundProxyRouterEndpointCall_works_as_expected() {
        // given
        String spanName = UUID.randomUUID().toString();
        RequestInfo<?> requestMock = mock(RequestInfo.class);

        // when
        ProxyRouterSpanNamingAndTaggingStrategy.setSpanNameOverrideForOutboundProxyRouterEndpointCall(
            spanName, requestMock
        );

        // then
        verify(requestMock).addRequestAttribute(SPAN_NAME_FOR_OUTBOUND_PROXY_CALL_REQ_ATTR_KEY, spanName);
    }

    @Test
    public void getInitialSpanNameOverride_delegates_to_doGetInitialSpanNameOverride() {
        // given
        ProxyRouterSpanNamingAndTaggingStrategy<?> defaultImplSpy =
            spy(new DefaultProxyRouterSpanNamingAndTaggingStrategy());

        HttpRequest nettyRequestMock = mock(HttpRequest.class);
        RequestInfo<?> riposteRequestMock = mock(RequestInfo.class);
        String initialSpanNameArg = UUID.randomUUID().toString();
        String overallRequestSpanNameArg = UUID.randomUUID().toString();

        String expectedResult = UUID.randomUUID().toString();

        doReturn(expectedResult).when(defaultImplSpy).doGetInitialSpanNameOverride(
            nettyRequestMock, riposteRequestMock, initialSpanNameArg, overallRequestSpanNameArg
        );

        // when
        String result = defaultImplSpy.getInitialSpanNameOverride(
            nettyRequestMock, riposteRequestMock, initialSpanNameArg, overallRequestSpanNameArg
        );

        // then
        assertThat(result).isEqualTo(expectedResult);
        verify(defaultImplSpy).doGetInitialSpanNameOverride(
            nettyRequestMock, riposteRequestMock, initialSpanNameArg, overallRequestSpanNameArg
        );
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true"
    }, splitBy = "\\|")
    @Test
    public void getInitialSpanNameOverride_returns_null_if_passed_null_request_arg(
        boolean nettyRequestIsNull, boolean riposteRequestIsNull
    ) {
        // given
        ProxyRouterSpanNamingAndTaggingStrategy<?> defaultImplSpy =
            spy(new DefaultProxyRouterSpanNamingAndTaggingStrategy());

        HttpRequest nettyRequestMock = (nettyRequestIsNull) ? null : mock(HttpRequest.class);
        RequestInfo<?> riposteRequestMock = (riposteRequestIsNull) ? null : mock(RequestInfo.class);
        String initialSpanNameArg = UUID.randomUUID().toString();
        String overallRequestSpanNameArg = UUID.randomUUID().toString();

        // when
        @SuppressWarnings("ConstantConditions")
        String result = defaultImplSpy.getInitialSpanNameOverride(
            nettyRequestMock, riposteRequestMock, initialSpanNameArg, overallRequestSpanNameArg
        );

        // then
        assertThat(result).isNull();
        verify(defaultImplSpy, never()).doGetInitialSpanNameOverride(
            any(HttpRequest.class), any(RequestInfo.class), anyString(), anyString()
        );
    }

    @Test
    public void getInitialSpanNameOverride_returns_null_if_delegate_method_throws_exception() {
        // given
        ProxyRouterSpanNamingAndTaggingStrategy<?> defaultImplSpy =
            spy(new DefaultProxyRouterSpanNamingAndTaggingStrategy());

        HttpRequest nettyRequestMock = mock(HttpRequest.class);
        RequestInfo<?> riposteRequestMock = mock(RequestInfo.class);
        String initialSpanNameArg = UUID.randomUUID().toString();
        String overallRequestSpanNameArg = UUID.randomUUID().toString();

        doThrow(new RuntimeException("intentional test exception")).when(defaultImplSpy).doGetInitialSpanNameOverride(
            nettyRequestMock, riposteRequestMock, initialSpanNameArg, overallRequestSpanNameArg
        );

        // when
        String result = defaultImplSpy.getInitialSpanNameOverride(
            nettyRequestMock, riposteRequestMock, initialSpanNameArg, overallRequestSpanNameArg
        );

        // then
        assertThat(result).isNull();
        verify(defaultImplSpy).doGetInitialSpanNameOverride(
            nettyRequestMock, riposteRequestMock, initialSpanNameArg, overallRequestSpanNameArg
        );
    }

    private enum InitialSpanNameOverrideScenario {
        REQUEST_ATTR_OVERRIDE_IS_SET(
            "someRequestAttrOverride", HttpMethod.GET, "proxy-GET", "GET /foo/bar/{baz}", "someRequestAttrOverride"
        ),
        REQUEST_ATTR_OVERRIDE_IS_SET_EVEN_WITH_CUSTOM_INITIAL_SPAN_NAME(
            "someRequestAttrOverride", HttpMethod.GET, "someCustomInitialSpanName", "GET /foo/bar/{baz}", "someRequestAttrOverride"
        ),
        INITIAL_SPAN_NAME_IS_BASIC_AND_OVERRIDEABLE_WITHOUT_PROXY_PREFIX(
            null, HttpMethod.GET, "GET", "GET /foo/bar/{baz}", "proxy-GET /foo/bar/{baz}"
        ),
        INITIAL_SPAN_NAME_IS_BASIC_AND_OVERRIDEABLE_WITH_PROXY_PREFIX(
            null, HttpMethod.GET, "proxy-GET", "GET /foo/bar/{baz}", "proxy-GET /foo/bar/{baz}"
        ),
        INITIAL_SPAN_NAME_IS_CUSTOM(
            null, HttpMethod.GET, "someCustomInitialSpanName", "GET /foo/bar/{baz}", null
        ),
        HTTP_METHOD_AND_INITIAL_SPAN_NAME_MISMATCH_WITHOUT_PROXY_PREFIX(
            null, HttpMethod.GET, "POST", "GET /foo/bar/{baz}", null
        ),
        HTTP_METHOD_AND_INITIAL_SPAN_NAME_MISMATCH_WITH_PROXY_PREFIX(
            null, HttpMethod.GET, "proxy-POST", "GET /foo/bar/{baz}", null
        ),
        INITIAL_SPAN_NAME_IS_NULL(
            null, HttpMethod.GET, null, "GET /foo/bar/{baz}", null
        ),
        OVERALL_REQUEST_SPAN_NAME_IS_NULL(
            null, HttpMethod.GET, "GET", null, null
        );

        public final String requestAttrOverride;
        public final HttpMethod httpMethod;
        public final String initialSpanName;
        public final String overallRequestSpanName;
        public final String expectedResult;

        InitialSpanNameOverrideScenario(
            String requestAttrOverride, HttpMethod httpMethod, String initialSpanName,
            String overallRequestSpanName,
            String expectedResult
        ) {
            this.requestAttrOverride = requestAttrOverride;
            this.httpMethod = httpMethod;
            this.initialSpanName = initialSpanName;
            this.overallRequestSpanName = overallRequestSpanName;
            this.expectedResult = expectedResult;
        }
    }

    @DataProvider(value = {
        "REQUEST_ATTR_OVERRIDE_IS_SET",
        "REQUEST_ATTR_OVERRIDE_IS_SET_EVEN_WITH_CUSTOM_INITIAL_SPAN_NAME",
        "INITIAL_SPAN_NAME_IS_BASIC_AND_OVERRIDEABLE_WITHOUT_PROXY_PREFIX",
        "INITIAL_SPAN_NAME_IS_BASIC_AND_OVERRIDEABLE_WITH_PROXY_PREFIX",
        "INITIAL_SPAN_NAME_IS_CUSTOM",
        "HTTP_METHOD_AND_INITIAL_SPAN_NAME_MISMATCH_WITHOUT_PROXY_PREFIX",
        "HTTP_METHOD_AND_INITIAL_SPAN_NAME_MISMATCH_WITH_PROXY_PREFIX",
        "INITIAL_SPAN_NAME_IS_NULL",
        "OVERALL_REQUEST_SPAN_NAME_IS_NULL",
    })
    @Test
    public void doGetInitialSpanNameOverride_default_impl_works_as_expected(
        InitialSpanNameOverrideScenario scenario
    ) {
        // given
        ProxyRouterSpanNamingAndTaggingStrategy<?> defaultImpl = new DefaultProxyRouterSpanNamingAndTaggingStrategy();

        RequestInfo<?> riposteRequestMock = mock(RequestInfo.class);
        Map<String, Object> requestAttrs = new HashMap<>();
        if (scenario.requestAttrOverride != null) {
            requestAttrs.put(SPAN_NAME_FOR_OUTBOUND_PROXY_CALL_REQ_ATTR_KEY, scenario.requestAttrOverride);
        }
        doReturn(requestAttrs).when(riposteRequestMock).getRequestAttributes();

        HttpRequest nettyRequestMock = mock(HttpRequest.class);
        doReturn(scenario.httpMethod).when(nettyRequestMock).method();

        // when
        String result = defaultImpl.doGetInitialSpanNameOverride(
            nettyRequestMock, riposteRequestMock, scenario.initialSpanName, scenario.overallRequestSpanName
        );

        // then
        assertThat(result).isEqualTo(scenario.expectedResult);
    }

    private static class DefaultProxyRouterSpanNamingAndTaggingStrategy<S>
        extends ProxyRouterSpanNamingAndTaggingStrategy<S> {

        @Override
        public @Nullable String doGetInitialSpanName(@NotNull HttpRequest request) { return null; }

        @Override
        public void doChangeSpanName(@NotNull S span, @NotNull String newName) { }

        @Override
        public void doHandleResponseTaggingAndFinalSpanName(
            @NotNull S span, @Nullable HttpRequest request, @Nullable HttpResponse response,
            @Nullable Throwable error
        ) { }

        @Override
        public void doHandleRequestTagging(@NotNull S span, @NotNull HttpRequest request) { }
    }
}