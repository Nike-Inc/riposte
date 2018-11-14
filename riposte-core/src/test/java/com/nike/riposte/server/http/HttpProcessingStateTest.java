package com.nike.riposte.server.http;

import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.ServerSpanNamingAndTaggingStrategy;
import com.nike.wingtips.Span;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link HttpProcessingState}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class HttpProcessingStateTest {

    private HttpProcessingState stateSpy;

    @Before
    public void beforeMethod() {
        stateSpy = spy(new HttpProcessingState());
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void getOverallRequestSpan_works_as_expected(boolean tracingStackIsNull) {
        // given
        Span firstSpanMock = mock(Span.class);
        Span secondSpanMock = mock(Span.class);
        Deque<Span> tracingStack =
            (tracingStackIsNull)
            ? null
            : new ArrayDeque<>();

        if (!tracingStackIsNull) {
            tracingStack.push(firstSpanMock);
            tracingStack.push(secondSpanMock);
        }

        stateSpy.setDistributedTraceStack(tracingStack);

        // when
        Span result = stateSpy.getOverallRequestSpan();

        // then
        if (tracingStackIsNull) {
            assertThat(result).isNull();
        }
        else {
            assertThat(result).isSameAs(firstSpanMock);
        }
    }

    @Test
    public void handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone_works_as_expected_happy_path() {
        // given
        DistributedTracingConfig<Span> distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        ServerSpanNamingAndTaggingStrategy<Span> taggingStrategyMock = mock(ServerSpanNamingAndTaggingStrategy.class);
        doReturn(taggingStrategyMock).when(distributedTracingConfigMock).getServerSpanNamingAndTaggingStrategy();
        stateSpy.setDistributedTracingConfig(distributedTracingConfigMock);

        Span overallRequestSpanMock = mock(Span.class);
        doReturn(overallRequestSpanMock).when(stateSpy).getOverallRequestSpan();

        RequestInfo<?> requestMock = mock(RequestInfo.class);
        ResponseInfo<?> responseMock = mock(ResponseInfo.class);
        Throwable errorMock = mock(Throwable.class);
        stateSpy.setRequestInfo(requestMock);
        stateSpy.setResponseInfo(responseMock, errorMock);

        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone();

        // then
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isTrue();

        verify(taggingStrategyMock).handleResponseTaggingAndFinalSpanName(
            overallRequestSpanMock, requestMock, responseMock, errorMock
        );
        verifyNoMoreInteractions(taggingStrategyMock);

        // and when
        // Verify it only works once.
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone();

        // then
        verifyNoMoreInteractions(taggingStrategyMock);
    }

    @Test
    public void handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone_does_nothing_if_overall_request_span_is_null() {
        // given
        doReturn(null).when(stateSpy).getOverallRequestSpan();
        
        DistributedTracingConfig<Span> distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        ServerSpanNamingAndTaggingStrategy<Span> taggingStrategyMock = mock(ServerSpanNamingAndTaggingStrategy.class);
        doReturn(taggingStrategyMock).when(distributedTracingConfigMock).getServerSpanNamingAndTaggingStrategy();
        stateSpy.setDistributedTracingConfig(distributedTracingConfigMock);

        RequestInfo<?> requestMock = mock(RequestInfo.class);
        ResponseInfo<?> responseMock = mock(ResponseInfo.class);
        Throwable errorMock = mock(Throwable.class);
        stateSpy.setRequestInfo(requestMock);
        stateSpy.setResponseInfo(responseMock, errorMock);

        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone();

        // then
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isTrue();

        verifyZeroInteractions(taggingStrategyMock);
    }

    @Test
    public void handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone_does_nothing_if_DistributedTracingConfig_is_null() {
        // given
        stateSpy.setDistributedTracingConfig(null);
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone();

        // then
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();
    }

    @Test
    public void handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone_does_not_propagate_unexpected_exception() {
        // given
        DistributedTracingConfig<Span> distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        doThrow(new RuntimeException("intentional exception")).when(distributedTracingConfigMock)
                                                              .getServerSpanNamingAndTaggingStrategy();
        stateSpy.setDistributedTracingConfig(distributedTracingConfigMock);

        Span overallRequestSpanMock = mock(Span.class);
        doReturn(overallRequestSpanMock).when(stateSpy).getOverallRequestSpan();

        RequestInfo<?> requestMock = mock(RequestInfo.class);
        ResponseInfo<?> responseMock = mock(ResponseInfo.class);
        Throwable errorMock = mock(Throwable.class);
        stateSpy.setRequestInfo(requestMock);
        stateSpy.setResponseInfo(responseMock, errorMock);

        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        Throwable ex = catchThrowable(() -> stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone());

        // then
        assertThat(ex).isNull();
        verify(distributedTracingConfigMock).getServerSpanNamingAndTaggingStrategy();
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isTrue();
    }

}