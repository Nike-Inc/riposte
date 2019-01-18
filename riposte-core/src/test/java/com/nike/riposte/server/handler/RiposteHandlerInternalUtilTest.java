package com.nike.riposte.server.handler;

import com.nike.riposte.server.error.exception.InvalidHttpRequestException;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.util.TracingState;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.MDC;

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
}