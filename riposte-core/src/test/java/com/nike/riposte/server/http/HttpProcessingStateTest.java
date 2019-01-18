package com.nike.riposte.server.http;

import com.nike.riposte.server.config.distributedtracing.DefaultRiposteServerSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.ServerSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.InitialSpanNameArgs;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.RequestTaggingArgs;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.ResponseTaggingArgs;
import com.nike.wingtips.Span;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;
import com.nike.wingtips.tags.HttpTagAndSpanNamingStrategy;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link HttpProcessingState}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class HttpProcessingStateTest {

    private HttpProcessingState stateSpy;

    private HttpTagAndSpanNamingStrategy<RequestInfo<?>, ResponseInfo<?>> wingtipsStrategy;
    private HttpTagAndSpanNamingAdapter<RequestInfo<?>, ResponseInfo<?>> wingtipsAdapterMock;
    private AtomicReference<String> initialSpanNameFromStrategy;
    private AtomicBoolean strategyInitialSpanNameMethodCalled;
    private AtomicBoolean strategyRequestTaggingMethodCalled;
    private AtomicBoolean strategyResponseTaggingAndFinalSpanNameMethodCalled;
    private AtomicReference<InitialSpanNameArgs<RequestInfo<?>>> strategyInitialSpanNameArgs;
    private AtomicReference<RequestTaggingArgs<RequestInfo<?>>> strategyRequestTaggingArgs;
    private AtomicReference<ResponseTaggingArgs<RequestInfo<?>, ResponseInfo<?>>> strategyResponseTaggingArgs;

    private DistributedTracingConfig<Span> distributedTracingConfigMock;
    private ServerSpanNamingAndTaggingStrategy<Span> serverTaggingStrategy;

    private Span spanMock;
    private RequestInfo<?> requestMock;
    private ResponseInfo<?> responseMock;
    private Throwable errorMock;

    @Before
    public void beforeMethod() {
        stateSpy = spy(new HttpProcessingState());

        initialSpanNameFromStrategy = new AtomicReference<>("span-name-from-strategy-" + UUID.randomUUID().toString());
        strategyInitialSpanNameMethodCalled = new AtomicBoolean(false);
        strategyRequestTaggingMethodCalled = new AtomicBoolean(false);
        strategyResponseTaggingAndFinalSpanNameMethodCalled = new AtomicBoolean(false);
        strategyInitialSpanNameArgs = new AtomicReference<>(null);
        strategyRequestTaggingArgs = new AtomicReference<>(null);
        strategyResponseTaggingArgs = new AtomicReference<>(null);
        wingtipsStrategy = new ArgCapturingHttpTagAndSpanNamingStrategy<>(
            initialSpanNameFromStrategy, strategyInitialSpanNameMethodCalled, strategyRequestTaggingMethodCalled,
            strategyResponseTaggingAndFinalSpanNameMethodCalled, strategyInitialSpanNameArgs,
            strategyRequestTaggingArgs, strategyResponseTaggingArgs
        );
        wingtipsAdapterMock = mock(HttpTagAndSpanNamingAdapter.class);

        serverTaggingStrategy = new DefaultRiposteServerSpanNamingAndTaggingStrategy(wingtipsStrategy, wingtipsAdapterMock);

        requestMock = mock(RequestInfo.class);
        responseMock = mock(ResponseInfo.class);
        errorMock = mock(Throwable.class);
        spanMock = mock(Span.class);

        distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        doReturn(serverTaggingStrategy).when(distributedTracingConfigMock).getServerSpanNamingAndTaggingStrategy();
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
        stateSpy.setDistributedTracingConfig(distributedTracingConfigMock);

        Span overallRequestSpanMock = mock(Span.class);
        doReturn(overallRequestSpanMock).when(stateSpy).getOverallRequestSpan();

        stateSpy.setRequestInfo(requestMock);
        stateSpy.setResponseInfo(responseMock, errorMock);

        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone();

        // then
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isTrue();

        strategyResponseTaggingArgs.get().verifyArgs(
            overallRequestSpanMock, requestMock, responseMock, errorMock, wingtipsAdapterMock
        );

        // and when
        // Verify it only works once.
        strategyResponseTaggingArgs.set(null);
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone();

        // then
        assertThat(strategyResponseTaggingArgs.get()).isNull();
    }

    @Test
    public void handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone_does_nothing_if_overall_request_span_is_null() {
        // given
        doReturn(null).when(stateSpy).getOverallRequestSpan();
        
        stateSpy.setDistributedTracingConfig(distributedTracingConfigMock);

        stateSpy.setRequestInfo(requestMock);
        stateSpy.setResponseInfo(responseMock, errorMock);

        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone();

        // then
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isTrue();

        assertThat(strategyResponseTaggingArgs.get()).isNull();
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
        doThrow(new RuntimeException("intentional exception")).when(distributedTracingConfigMock)
                                                              .getServerSpanNamingAndTaggingStrategy();
        stateSpy.setDistributedTracingConfig(distributedTracingConfigMock);

        Span overallRequestSpanMock = mock(Span.class);
        doReturn(overallRequestSpanMock).when(stateSpy).getOverallRequestSpan();

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