package com.nike.riposte.server.http;

import com.nike.riposte.server.config.distributedtracing.DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.ProxyRouterSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.InitialSpanNameArgs;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.RequestTaggingArgs;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.ResponseTaggingArgs;
import com.nike.wingtips.Span;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;
import com.nike.wingtips.tags.HttpTagAndSpanNamingStrategy;

import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link ProxyRouterProcessingState}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class ProxyRouterProcessingStateTest {

    private ProxyRouterProcessingState stateSpy;

    private HttpTagAndSpanNamingStrategy<HttpRequest, HttpResponse> wingtipsStrategy;
    private HttpTagAndSpanNamingAdapter<HttpRequest, HttpResponse> wingtipsAdapterMock;
    private AtomicReference<String> initialSpanNameFromStrategy;
    private AtomicBoolean strategyInitialSpanNameMethodCalled;
    private AtomicBoolean strategyRequestTaggingMethodCalled;
    private AtomicBoolean strategyResponseTaggingAndFinalSpanNameMethodCalled;
    private AtomicReference<InitialSpanNameArgs<HttpRequest>> strategyInitialSpanNameArgs;
    private AtomicReference<RequestTaggingArgs<HttpRequest>> strategyRequestTaggingArgs;
    private AtomicReference<ResponseTaggingArgs<HttpRequest, HttpResponse>> strategyResponseTaggingArgs;

    private DistributedTracingConfig<Span> distributedTracingConfigMock;
    private ProxyRouterSpanNamingAndTaggingStrategy<Span> proxyTaggingStrategy;

    private Span spanMock;
    private HttpRequest requestMock;
    private HttpResponse responseMock;
    private Throwable errorMock;

    @Before
    public void beforeMethod() {
        stateSpy = spy(new ProxyRouterProcessingState());

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

        proxyTaggingStrategy = new DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy(wingtipsStrategy, wingtipsAdapterMock);

        requestMock = mock(HttpRequest.class);
        responseMock = mock(HttpResponse.class);
        errorMock = mock(Throwable.class);
        spanMock = mock(Span.class);

        distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        doReturn(proxyTaggingStrategy).when(distributedTracingConfigMock).getProxyRouterSpanNamingAndTaggingStrategy();
    }

    @Test
    public void handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone_works_as_expected_happy_path() {
        // given
        stateSpy.setDistributedTracingConfig(distributedTracingConfigMock);

        Span downstreamReqSpanMock = mock(Span.class);

        stateSpy.setProxyHttpRequest(requestMock);
        stateSpy.setProxyHttpResponse(responseMock);
        stateSpy.setProxyError(errorMock);

        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone(downstreamReqSpanMock);

        // then
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isTrue();

        strategyResponseTaggingArgs.get().verifyArgs(
            downstreamReqSpanMock, requestMock, responseMock, errorMock, wingtipsAdapterMock
        );

        // and when
        // Verify it only works once.
        strategyResponseTaggingArgs.set(null);
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone(downstreamReqSpanMock);

        // then
        assertThat(strategyResponseTaggingArgs.get()).isNull();
    }

    @Test
    public void handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone_does_nothing_if_spanAroundProxyCall_is_null() {
        // given
        stateSpy.setDistributedTracingConfig(distributedTracingConfigMock);

        stateSpy.setProxyHttpRequest(requestMock);
        stateSpy.setProxyHttpResponse(responseMock);

        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone(null);

        // then
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        assertThat(strategyResponseTaggingArgs.get()).isNull();
    }

    @Test
    public void handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone_does_nothing_if_DistributedTracingConfig_is_null() {
        // given
        stateSpy.setDistributedTracingConfig(null);
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone(mock(Span.class));

        // then
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();
    }

    @Test
    public void handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone_does_not_propagate_unexpected_exception() {
        // given
        doThrow(new RuntimeException("intentional exception")).when(distributedTracingConfigMock)
                                                              .getProxyRouterSpanNamingAndTaggingStrategy();
        stateSpy.setDistributedTracingConfig(distributedTracingConfigMock);

        stateSpy.setProxyHttpRequest(requestMock);
        stateSpy.setProxyHttpResponse(responseMock);

        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        Throwable ex = catchThrowable(() -> stateSpy.handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone(
            mock(Span.class)
        ));

        // then
        assertThat(ex).isNull();
        verify(distributedTracingConfigMock).getProxyRouterSpanNamingAndTaggingStrategy();
        assertThat(stateSpy.isTracingResponseTaggingAndFinalSpanNameCompleted()).isTrue();
    }
}