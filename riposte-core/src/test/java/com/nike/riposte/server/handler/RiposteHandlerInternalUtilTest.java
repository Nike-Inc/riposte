package com.nike.riposte.server.handler;

import com.nike.riposte.server.config.distributedtracing.ServerSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.error.exception.InvalidHttpRequestException;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.util.TracingState;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.assertj.core.api.Assertions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.MDC;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link RiposteHandlerInternalUtil}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class RiposteHandlerInternalUtilTest {

    private RiposteHandlerInternalUtil implSpy;
    private HttpProcessingState stateSpy;
    private HttpRequest nettyRequest;

    @Before
    public void beforeMethod() {
        implSpy = spy(new RiposteHandlerInternalUtil());
        stateSpy = spy(new HttpProcessingState());
        nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PATCH, "/some/uri");

        resetTracingAndMdc();
    }

    @After
    public void afterMethod() {
        resetTracingAndMdc();
    }

    private void resetTracingAndMdc() {
        MDC.clear();
        Tracer.getInstance().unregisterFromThread();
    }

    @Test
    public void createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary_works_as_expected_when_no_decoder_failure() {
        // given
        assertThat(stateSpy.getRequestInfo()).isNull();

        // when
        RequestInfo<?> result =
            implSpy.createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(nettyRequest, stateSpy);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getMethod()).isEqualTo(nettyRequest.method());
        assertThat(result.getUri()).isEqualTo(nettyRequest.uri());

        verify(stateSpy).setRequestInfo(result);
        assertThat(stateSpy.getRequestInfo()).isSameAs(result);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary_creates_dummy_ResultInfo_when_decoder_failure_exists(
        boolean tracingExistsOnCurrentThread
    ) {
        // given
        Throwable decoderFailureEx = new RuntimeException("intentional exception");
        nettyRequest.setDecoderResult(DecoderResult.failure(decoderFailureEx));

        RequestInfo<?> dummyRequestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();

        assertThat(stateSpy.getRequestInfo()).isNull();

        Tracer.getInstance().startRequestWithRootSpan("foo");
        TracingState tracingState = TracingState.getCurrentThreadTracingState();
        stateSpy.setDistributedTraceStack(tracingState.spanStack);
        stateSpy.setLoggerMdcContextMap(tracingState.mdcInfo);

        if (!tracingExistsOnCurrentThread) {
            Tracer.getInstance().unregisterFromThread();
            assertThat(Tracer.getInstance().getCurrentSpan()).isNull();
        }
        else {
            assertThat(Tracer.getInstance().getCurrentSpan()).isNotNull();
        }

        // when
        RequestInfo<?> result =
            implSpy.createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(nettyRequest, stateSpy);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getUri()).isEqualTo(dummyRequestInfo.getUri());

        verify(stateSpy).setRequestInfo(result);
        assertThat(stateSpy.getRequestInfo()).isSameAs(result);
    }

    @Test
    public void createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary_creates_dummy_ResultInfo_when_exception_occurs_during_ResultInfo_instantiation() {
        // given
        String brokenUri = "%notARealEscapeSequence";
        nettyRequest.setUri(brokenUri);

        // Sanity check that trying to create a new RequestInfo using the netty HttpRequest results in an exception.
        Throwable sanityCheckEx = catchThrowable(() -> new RequestInfoImpl<>(nettyRequest));
        assertThat(sanityCheckEx).isNotNull();

        RequestInfo<?> dummyRequestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();

        assertThat(stateSpy.getRequestInfo()).isNull();

        // when
        RequestInfo<?> result =
            implSpy.createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(nettyRequest, stateSpy);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getUri()).isEqualTo(dummyRequestInfo.getUri());

        verify(stateSpy).setRequestInfo(result);
        assertThat(stateSpy.getRequestInfo()).isSameAs(result);
    }

    @Test
    public void createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary_uses_RequestInfo_from_state_if_it_already_exists() {
        // given
        RequestInfo<?> alreadyExistingRequestInfoMock = mock(RequestInfo.class);
        doReturn(alreadyExistingRequestInfoMock).when(stateSpy).getRequestInfo();

        assertThat(stateSpy.getRequestInfo()).isSameAs(alreadyExistingRequestInfoMock);

        // when
        RequestInfo<?> result =
            implSpy.createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(nettyRequest, stateSpy);

        // then
        assertThat(result).isSameAs(alreadyExistingRequestInfoMock);
        assertThat(stateSpy.getRequestInfo()).isSameAs(alreadyExistingRequestInfoMock);

        verify(stateSpy, never()).setRequestInfo(any(RequestInfo.class));
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void throwExceptionIfNotSuccessfullyDecoded_works_as_expected(
        boolean decoderFailureExists
    ) {
        // given
        Throwable decoderFailureEx = new RuntimeException("intentional exception");

        if (decoderFailureExists) {
            nettyRequest.setDecoderResult(DecoderResult.failure(decoderFailureEx));
        }

        // when
        Throwable resultEx = catchThrowable(() -> implSpy.throwExceptionIfNotSuccessfullyDecoded(nettyRequest));

        // then
        if (decoderFailureExists) {
            assertThat(resultEx)
                .isInstanceOf(InvalidHttpRequestException.class)
                .hasCause(decoderFailureEx);
        }
        else {
            assertThat(resultEx).isNull();
        }
    }

    @Test
    public void getDecoderFailure_returns_DecoderResult_cause_when_it_is_a_failure() {
        // given
        HttpObject httpObjectMock = mock(HttpObject.class);
        Throwable expectedResult = mock(Throwable.class);
        doReturn(DecoderResult.failure(expectedResult)).when(httpObjectMock).decoderResult();

        // when
        Throwable result = implSpy.getDecoderFailure(httpObjectMock);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void getDecoderFailure_returns_null_when_passed_null() {
        // expect
        assertThat(implSpy.getDecoderFailure(null)).isNull();
    }

    @Test
    public void getDecoderFailure_returns_null_when_DecoderResult_is_null() {
        // given
        HttpObject httpObjectMock = mock(HttpObject.class);
        doReturn(null).when(httpObjectMock).decoderResult();

        // when
        Throwable result = implSpy.getDecoderFailure(httpObjectMock);

        // then
        assertThat(result).isNull();
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void getDecoderFailure_returns_null_when_DecoderResult_is_not_a_failure(
        boolean isUnfinished
    ) {
        // given
        HttpObject httpObjectMock = mock(HttpObject.class);
        DecoderResult nonFailureDecoderResult = (isUnfinished) ? DecoderResult.UNFINISHED : DecoderResult.SUCCESS;
        doReturn(nonFailureDecoderResult).when(httpObjectMock).decoderResult();

        // when
        Throwable result = implSpy.getDecoderFailure(httpObjectMock);

        // then
        assertThat(result).isNull();
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void getOverallRequestSpan_works_as_expected(boolean stateIsNull) {
        // given
        Span stateOverallRequestSpanMock = mock(Span.class);
        HttpProcessingState stateMock = (stateIsNull) ? null : mock(HttpProcessingState.class);

        if (!stateIsNull) {
            doReturn(stateOverallRequestSpanMock).when(stateMock).getOverallRequestSpan();
        }

        // when
        Span result = implSpy.getOverallRequestSpan(stateMock);

        // then
        if (stateIsNull) {
            assertThat(result).isNull();
        }
        else {
            assertThat(result).isSameAs(stateOverallRequestSpanMock);
            verify(stateMock).getOverallRequestSpan();
        }
    }

    private enum DetermineSpanNameScenario {
        NAMING_STRATEGY_RESULT_IS_VALID(false, "spanNameFromStrategy", true),
        NAMING_STRATEGY_RESULT_IS_NULL(false, null, false),
        NAMING_STRATEGY_RESULT_IS_EMPTY(false, "", false),
        NAMING_STRATEGY_RESULT_IS_BLANK(false, "  \r\n\t  ", false),
        RIPOSTE_REQUEST_INFO_IS_NULL(true, "doesnotmatter", false);

        public final String fallbackSpanName = "fallback-span-name-" + UUID.randomUUID().toString();
        public final boolean riposteRequestInfoIsNull;
        public final String strategySpanName;
        public final boolean expectStrategyResult;

        DetermineSpanNameScenario(
            boolean riposteRequestInfoIsNull, String strategySpanName, boolean expectStrategyResult
        ) {
            this.riposteRequestInfoIsNull = riposteRequestInfoIsNull;
            this.strategySpanName = strategySpanName;
            this.expectStrategyResult = expectStrategyResult;
        }
    }

    private static class DummyServerSpanNamingAndTaggingStrategy extends ServerSpanNamingAndTaggingStrategy<Span> {

        private final String initialSpanName;

        private DummyServerSpanNamingAndTaggingStrategy(String initialSpanName) {
            this.initialSpanName = initialSpanName;
        }

        @Override
        protected @Nullable String doGetInitialSpanName(@NotNull RequestInfo<?> request) {
            return initialSpanName;
        }

        @Override
        protected void doChangeSpanName(@NotNull Span span, @NotNull String newName) { }

        @Override
        protected void doHandleRequestTagging(@NotNull Span span, @NotNull RequestInfo<?> request) { }

        @Override
        protected void doHandleResponseTaggingAndFinalSpanName(
            @NotNull Span span, @Nullable RequestInfo<?> request, @Nullable ResponseInfo<?> response,
            @Nullable Throwable error
        ) { }
    }

    @DataProvider
    public static List<List<DetermineSpanNameScenario>> determineOverallRequestSpanNameScenarioDataProvider() {
        return Arrays.stream(DetermineSpanNameScenario.values()).map(Collections::singletonList).collect(
            Collectors.toList());
    }

    @UseDataProvider("determineOverallRequestSpanNameScenarioDataProvider")
    @Test
    public void determineOverallRequestSpanName_works_as_expected(DetermineSpanNameScenario scenario) {
        // given
        ServerSpanNamingAndTaggingStrategy<Span> namingStrategy =
            new DummyServerSpanNamingAndTaggingStrategy(scenario.strategySpanName);
        doReturn(scenario.fallbackSpanName).when(implSpy).determineFallbackOverallRequestSpanName(any());

        RequestInfo<?> requestInfoMock = mock(RequestInfo.class);
        if (scenario.riposteRequestInfoIsNull) {
            requestInfoMock = null;
        }

        HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/uri");

        String expectedResult = (scenario.expectStrategyResult)
                                ? scenario.strategySpanName
                                : scenario.fallbackSpanName;

        // when
        String result = implSpy.determineOverallRequestSpanName(httpRequest, requestInfoMock, namingStrategy);

        // then
        Assertions.assertThat(result).isEqualTo(expectedResult);

        if (scenario.expectStrategyResult) {
            verify(implSpy, never()).determineFallbackOverallRequestSpanName(any());
        }
        else {
            verify(implSpy).determineFallbackOverallRequestSpanName(httpRequest);
        }
    }

    @DataProvider(value = {
        "foo            |   foo",
        "null           |   UNKNOWN_HTTP_METHOD",
        "               |   UNKNOWN_HTTP_METHOD",
        "[whitespace]   |   UNKNOWN_HTTP_METHOD",
    }, splitBy = "\\|")
    @Test
    public void determineFallbackOverallRequestSpanName_works_as_expected(String httpMethodStr, String expectedResult) {
        // given
        if ("[whitespace]".equals(httpMethodStr)) {
            httpMethodStr = "  \r\n\t  ";
        }

        HttpMethod httpMethodMock = (httpMethodStr == null) ? null : mock(HttpMethod.class);
        HttpRequest nettyHttpRequestMock = mock(HttpRequest.class);

        doReturn(httpMethodMock).when(nettyHttpRequestMock).method();
        if (httpMethodMock != null) {
            doReturn(httpMethodStr).when(httpMethodMock).name();
        }

        // when
        String result = implSpy.determineFallbackOverallRequestSpanName(nettyHttpRequestMock);

        // then
        Assertions.assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void determineFallbackOverallRequestSpanName_returns_UNKNOWN_HTTP_METHOD_if_unexpected_exception_occurs() {
        // given
        HttpRequest nettyHttpRequestMock = mock(HttpRequest.class);
        doThrow(new RuntimeException("intentional exception")).when(nettyHttpRequestMock).method();

        // when
        String result = implSpy.determineFallbackOverallRequestSpanName(nettyHttpRequestMock);

        // then
        Assertions.assertThat(result).isEqualTo("UNKNOWN_HTTP_METHOD");
        verify(nettyHttpRequestMock).method();
    }
}