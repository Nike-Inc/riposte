package com.nike.riposte.client.asynchttp.ning;

import com.nike.fastbreak.CircuitBreaker;
import com.nike.fastbreak.CircuitBreaker.ManualModeTask;
import com.nike.fastbreak.CircuitBreakerDelegate;
import com.nike.fastbreak.CircuitBreakerForHttpStatusCode;
import com.nike.fastbreak.exception.CircuitBreakerOpenException;
import com.nike.internal.util.Pair;
import com.nike.riposte.client.asynchttp.ning.AsyncHttpClientHelper.MultiIpAwareNameResolver;
import com.nike.riposte.client.asynchttp.ning.testutils.ArgCapturingHttpTagAndSpanNamingStrategy;
import com.nike.riposte.client.asynchttp.ning.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.InitialSpanNameArgs;
import com.nike.riposte.client.asynchttp.ning.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.RequestTaggingArgs;
import com.nike.riposte.client.asynchttp.ning.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.ResponseTaggingArgs;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.config.distributedtracing.SpanNamingAndTaggingStrategy;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.wingtips.Span;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.Tracer.SpanFieldForLoggerMdc;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;
import com.nike.wingtips.tags.HttpTagAndSpanNamingStrategy;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Request;
import com.ning.http.client.Response;
import com.ning.http.client.SignatureCalculator;
import com.ning.http.client.uri.Uri;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.Attribute;

import static com.nike.riposte.client.asynchttp.ning.AsyncHttpClientHelper.DEFAULT_POOLED_DOWNSTREAM_CONNECTION_TTL_MILLIS;
import static com.nike.riposte.client.asynchttp.ning.AsyncHttpClientHelper.DEFAULT_REQUEST_TIMEOUT_MILLIS;
import static com.nike.wingtips.http.HttpRequestTracingUtils.convertSampleableBooleanToExpectedB3Value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link AsyncHttpClientHelper}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class AsyncHttpClientHelperTest {

    private AsyncHttpClientHelper helperSpy;
    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;
    private EventLoop eventLoopMock;
    private AsyncCompletionHandlerWithTracingAndMdcSupport handlerWithTracingAndMdcDummyExample;
    private SignatureCalculator signatureCalculator;

    private SpanNamingAndTaggingStrategy<RequestBuilderWrapper, Response, Span> tagAndNamingStrategy;
    private HttpTagAndSpanNamingStrategy<RequestBuilderWrapper, Response> wingtipsTagAndNamingStrategy;
    private HttpTagAndSpanNamingAdapter<RequestBuilderWrapper, Response> wingtipsTagAndNamingAdapterMock;
    private AtomicReference<String> initialSpanNameFromStrategy;
    private AtomicBoolean strategyInitialSpanNameMethodCalled;
    private AtomicBoolean strategyRequestTaggingMethodCalled;
    private AtomicBoolean strategyResponseTaggingAndFinalSpanNameMethodCalled;
    private AtomicReference<InitialSpanNameArgs<RequestBuilderWrapper>> strategyInitialSpanNameArgs;
    private AtomicReference<RequestTaggingArgs<RequestBuilderWrapper>> strategyRequestTaggingArgs;
    private AtomicReference<ResponseTaggingArgs<RequestBuilderWrapper, Response>> strategyResponseTaggingArgs;
    
    @Before
    public void beforeMethod() {
        initialSpanNameFromStrategy = new AtomicReference<>("span-name-from-strategy-" + UUID.randomUUID().toString());
        strategyInitialSpanNameMethodCalled = new AtomicBoolean(false);
        strategyRequestTaggingMethodCalled = new AtomicBoolean(false);
        strategyResponseTaggingAndFinalSpanNameMethodCalled = new AtomicBoolean(false);
        strategyInitialSpanNameArgs = new AtomicReference<>(null);
        strategyRequestTaggingArgs = new AtomicReference<>(null);
        strategyResponseTaggingArgs = new AtomicReference<>(null);
        wingtipsTagAndNamingStrategy = new ArgCapturingHttpTagAndSpanNamingStrategy<>(
            initialSpanNameFromStrategy, strategyInitialSpanNameMethodCalled, strategyRequestTaggingMethodCalled,
            strategyResponseTaggingAndFinalSpanNameMethodCalled, strategyInitialSpanNameArgs,
            strategyRequestTaggingArgs, strategyResponseTaggingArgs
        );
        wingtipsTagAndNamingAdapterMock = mock(HttpTagAndSpanNamingAdapter.class);
        tagAndNamingStrategy = new DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy(
            wingtipsTagAndNamingStrategy, wingtipsTagAndNamingAdapterMock
        );

        helperSpy = spy(new AsyncHttpClientHelper().setSpanNamingAndTaggingStrategy(tagAndNamingStrategy));
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        eventLoopMock = mock(EventLoop.class);
        signatureCalculator = mock(SignatureCalculator.class);
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();
        doReturn(eventLoopMock).when(channelMock).eventLoop();

        handlerWithTracingAndMdcDummyExample = new AsyncCompletionHandlerWithTracingAndMdcSupport<>(
            null, null, false, mock(RequestBuilderWrapper.class), null, null, null,
            DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy.getDefaultInstance()
        );

        resetTracingAndMdc();
    }

    @After
    public void afterMethod() {
        resetTracingAndMdc();
    }

    private void resetTracingAndMdc() {
        MDC.clear();
        Tracer.getInstance().completeRequestSpan();
    }

    private void verifyDefaultUnderlyingClientConfig(AsyncHttpClientHelper instance) {
        AsyncHttpClientConfig config = instance.asyncHttpClient.getConfig();
        assertThat(config.isAllowPoolingConnections()).isTrue();
        assertThat(config.getMaxRequestRetry()).isEqualTo(0);
        assertThat(config.getRequestTimeout()).isEqualTo(DEFAULT_REQUEST_TIMEOUT_MILLIS);
        assertThat(config.getConnectionTTL()).isEqualTo(DEFAULT_POOLED_DOWNSTREAM_CONNECTION_TTL_MILLIS);
        assertThat(Whitebox.getInternalState(instance.asyncHttpClient, "signatureCalculator")).isNull();
    }

    @Test
    public void default_constructor_creates_instance_with_default_values() {
        // when
        AsyncHttpClientHelper instance = new AsyncHttpClientHelper();

        // then
        assertThat(instance.performSubSpanAroundDownstreamCalls).isTrue();
        verifyDefaultUnderlyingClientConfig(instance);
    }

    @Test
    public void fluent_setters_work_as_expected() {
        // when
        AsyncHttpClientHelper instance = new AsyncHttpClientHelper(false);
        assertThat(instance.performSubSpanAroundDownstreamCalls).isFalse();
        AsyncHttpClientHelper result = instance.setPerformSubSpanAroundDownstreamCalls(true)
                                               .setDefaultSignatureCalculator(signatureCalculator)
                                               .setSpanNamingAndTaggingStrategy(tagAndNamingStrategy);

        // then
        assertThat(result).isSameAs(instance);
        assertThat(instance.performSubSpanAroundDownstreamCalls).isTrue();
        assertThat(Whitebox.getInternalState(instance.asyncHttpClient, "signatureCalculator")).isEqualTo(signatureCalculator);
        assertThat(instance.spanNamingAndTaggingStrategy).isSameAs(tagAndNamingStrategy);
    }

    @Test
    public void setSpanNamingAndTaggingStrategy_throws_IllegalArgumentException_if_passed_null() {
        // given
        AsyncHttpClientHelper instance = new AsyncHttpClientHelper(false);

        // when
        Throwable ex = catchThrowable(() -> instance.setSpanNamingAndTaggingStrategy(null));

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("spanNamingAndTaggingStrategy cannot be null");
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void constructor_with_subspan_opt_works_as_expected(boolean performSubspan) {
        // when
        AsyncHttpClientHelper instance = new AsyncHttpClientHelper(performSubspan);

        // then
        assertThat(instance.performSubSpanAroundDownstreamCalls).isEqualTo(performSubspan);
        verifyDefaultUnderlyingClientConfig(instance);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void kitchen_sink_constructor_sets_up_underlying_client_with_expected_config(boolean performSubspan) {
        // given
        int customRequestTimeoutVal = 4242;
        AsyncHttpClientConfig config =
            new AsyncHttpClientConfig.Builder().setRequestTimeout(customRequestTimeoutVal).build();
        AsyncHttpClientConfig.Builder builderMock = mock(AsyncHttpClientConfig.Builder.class);
        doReturn(config).when(builderMock).build();

        // when
        AsyncHttpClientHelper instance = new AsyncHttpClientHelper(builderMock, performSubspan);

        // then
        assertThat(instance.performSubSpanAroundDownstreamCalls).isEqualTo(performSubspan);
        assertThat(instance.asyncHttpClient.getConfig()).isSameAs(config);
        assertThat(instance.asyncHttpClient.getConfig().getRequestTimeout()).isEqualTo(customRequestTimeoutVal);
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true",
        "false  |   false"
    }, splitBy = "\\|")
    @Test
    public void constructor_clears_out_tracing_and_mdc_info_before_building_underlying_client_and_resets_afterward(
        boolean emptyBeforeCall, boolean explode
    ) {
        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().build();
        AsyncHttpClientConfig.Builder builderMock = mock(AsyncHttpClientConfig.Builder.class);
        List<Span> traceAtTimeOfBuildCall = new ArrayList<>();
        List<Map<String, String>> mdcAtTimeOfBuildCall = new ArrayList<>();
        RuntimeException explodeEx = new RuntimeException("kaboom");
        doAnswer(invocation -> {
            traceAtTimeOfBuildCall.add(Tracer.getInstance().getCurrentSpan());
            mdcAtTimeOfBuildCall.add(MDC.getCopyOfContextMap());
            if (explode)
                throw explodeEx;
            return config;
        }).when(builderMock).build();

        Span spanBeforeCall = (emptyBeforeCall) ? null : Tracer.getInstance().startRequestWithRootSpan("foo");
        Map<String, String> mdcBeforeCall = MDC.getCopyOfContextMap();
        assertThat(Tracer.getInstance().getCurrentSpan()).isEqualTo(spanBeforeCall);
        if (emptyBeforeCall)
            assertThat(mdcBeforeCall).isNull();
        else
            assertThat(mdcBeforeCall).isNotEmpty();

        // when
        Throwable ex = catchThrowable(() -> new AsyncHttpClientHelper(builderMock, true));

        // then
        verify(builderMock).build();
        assertThat(traceAtTimeOfBuildCall).hasSize(1);
        assertThat(traceAtTimeOfBuildCall.get(0)).isNull();
        assertThat(mdcAtTimeOfBuildCall).hasSize(1);
        assertThat(mdcAtTimeOfBuildCall.get(0)).isNull();

        assertThat(Tracer.getInstance().getCurrentSpan()).isEqualTo(spanBeforeCall);
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(mdcBeforeCall);

        if (explode)
            assertThat(ex).isSameAs(explodeEx);
    }

    private void verifyRequestBuilderWrapperGeneratedAsExpected(
        RequestBuilderWrapper rbw, String url, String method, Optional<CircuitBreaker<Response>> customCb,
        boolean disableCb
    ) {
        assertThat(rbw.url).isEqualTo(url);
        assertThat(rbw.httpMethod).isEqualTo(method);
        assertThat(rbw.customCircuitBreaker).isEqualTo(customCb);
        assertThat(rbw.disableCircuitBreaker).isEqualTo(disableCb);
        Request req = rbw.requestBuilder.build();
        assertThat(req.getMethod()).isEqualTo(method);
        assertThat(req.getUri()).isEqualTo(Uri.create(url));
        assertThat(req.getUrl()).isEqualTo(url);
        assertThat(req.getNameResolver()).isEqualTo(MultiIpAwareNameResolver.INSTANCE);
    }

    @Test
    public void getRequestBuilder_delegates_to_helper_with_default_circuit_breaker_args() {
        // given
        String url = UUID.randomUUID().toString();
        HttpMethod method = HttpMethod.valueOf(UUID.randomUUID().toString());
        RequestBuilderWrapper rbwMock = mock(RequestBuilderWrapper.class);
        doReturn(rbwMock).when(helperSpy)
                         .getRequestBuilder(anyString(), any(HttpMethod.class), any(Optional.class), anyBoolean());

        // when
        RequestBuilderWrapper rbw = helperSpy.getRequestBuilder(url, method);

        // then
        verify(helperSpy).getRequestBuilder(url, method, Optional.empty(), false);
        assertThat(rbw).isSameAs(rbwMock);
    }

    @DataProvider(value = {
        "CONNECT",
        "DELETE",
        "GET",
        "HEAD",
        "POST",
        "OPTIONS",
        "PUT",
        "PATCH",
        "TRACE",
        "FOO_METHOD_DOES_NOT_EXIST"
    }, splitBy = "\\|")
    @Test
    public void getRequestBuilder_with_circuit_breaker_args_sets_values_as_expected(String methodName) {
        CircuitBreaker<Response> cbMock = mock(CircuitBreaker.class);
        List<Pair<Optional<CircuitBreaker<Response>>, Boolean>> variations = Arrays.asList(
            Pair.of(Optional.empty(), true),
            Pair.of(Optional.empty(), false),
            Pair.of(Optional.of(cbMock), true),
            Pair.of(Optional.of(cbMock), false)
        );

        variations.forEach(variation -> {
            // given
            String url = "http://localhost/some/path";
            HttpMethod method = HttpMethod.valueOf(methodName);
            Optional<CircuitBreaker<Response>> cbOpt = variation.getLeft();
            boolean disableCb = variation.getRight();

            // when
            RequestBuilderWrapper rbw = helperSpy.getRequestBuilder(url, method, cbOpt, disableCb);

            // then
            verifyRequestBuilderWrapperGeneratedAsExpected(rbw, url, methodName, cbOpt, disableCb);
        });
    }

    @Test
    public void basic_executeAsyncHttpRequest_extracts_mdc_and_tracing_info_from_current_thread_and_delegates_to_kitchen_sink_execute_method() {
        // given
        RequestBuilderWrapper rbw = mock(RequestBuilderWrapper.class);
        AsyncResponseHandler responseHandler = mock(AsyncResponseHandler.class);
        CompletableFuture cfMock = mock(CompletableFuture.class);
        doReturn(cfMock).when(helperSpy).executeAsyncHttpRequest(
            any(RequestBuilderWrapper.class), any(AsyncResponseHandler.class), any(Deque.class), any(Map.class)
        );
        Tracer.getInstance().startRequestWithRootSpan("foo");
        Deque<Span> expectedSpanStack = Tracer.getInstance().getCurrentSpanStackCopy();
        Map<String, String> expectedMdc = MDC.getCopyOfContextMap();

        // when
        CompletableFuture result = helperSpy.executeAsyncHttpRequest(rbw, responseHandler);

        // then
        verify(helperSpy).executeAsyncHttpRequest(rbw, responseHandler, expectedSpanStack, expectedMdc);
        assertThat(result).isSameAs(cfMock);
    }

    @Test
    public void executeAsyncHttpRequest_with_ctx_extracts_mdc_and_tracing_info_from_ctx_and_delegates_to_kitchen_sink_execute_method() {
        // given
        RequestBuilderWrapper rbwMock = mock(RequestBuilderWrapper.class);
        AsyncResponseHandler responseHandlerMock = mock(AsyncResponseHandler.class);
        CompletableFuture cfMock = mock(CompletableFuture.class);
        doReturn(cfMock).when(helperSpy).executeAsyncHttpRequest(
            any(RequestBuilderWrapper.class), any(AsyncResponseHandler.class), any(Deque.class), any(Map.class)
        );

        Map<String, String> mdcMock = mock(Map.class);
        Deque<Span> spanStackMock = mock(Deque.class);
        state.setLoggerMdcContextMap(mdcMock);
        state.setDistributedTraceStack(spanStackMock);

        // when
        CompletableFuture result = helperSpy.executeAsyncHttpRequest(rbwMock, responseHandlerMock, ctxMock);

        // then
        verify(helperSpy).executeAsyncHttpRequest(rbwMock, responseHandlerMock, spanStackMock, mdcMock);
        assertThat(result).isSameAs(cfMock);
        verify(rbwMock).setCtx(ctxMock);
    }

    @Test
    public void executeAsyncHttpRequest_with_ctx_throws_IllegalStateException_if_state_is_null() {
        // given
        RequestBuilderWrapper rbwMock = mock(RequestBuilderWrapper.class);
        AsyncResponseHandler responseHandlerMock = mock(AsyncResponseHandler.class);
        CompletableFuture cfMock = mock(CompletableFuture.class);
        doReturn(cfMock).when(helperSpy).executeAsyncHttpRequest(
            any(RequestBuilderWrapper.class), any(AsyncResponseHandler.class), any(Deque.class), any(Map.class)
        );

        doReturn(null).when(stateAttributeMock).get();

        // when
        Throwable ex = catchThrowable(() -> helperSpy.executeAsyncHttpRequest(rbwMock, responseHandlerMock, ctxMock));

        // then
        assertThat(ex).isInstanceOf(IllegalStateException.class);
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true",
        "false  |   false"
    }, splitBy = "\\|")
    @Test
    public void executeAsyncHttpRequest_sets_up_and_executes_call_as_expected(
        boolean performSubspan, boolean currentTracingInfoNull
    ) {
        // given
        Whitebox.setInternalState(helperSpy, "performSubSpanAroundDownstreamCalls", performSubspan);

        CircuitBreaker<Response> circuitBreakerMock = mock(CircuitBreaker.class);
        doReturn(Optional.of(circuitBreakerMock)).when(helperSpy).getCircuitBreaker(any(RequestBuilderWrapper.class));
        ManualModeTask<Response> cbManualTaskMock = mock(ManualModeTask.class);
        doReturn(cbManualTaskMock).when(circuitBreakerMock).newManualModeTask();

        String url = "http://localhost/some/path";
        String method = "GET";
        AsyncHttpClient.BoundRequestBuilder reqMock = mock(AsyncHttpClient.BoundRequestBuilder.class);
        RequestBuilderWrapper rbw = new RequestBuilderWrapper(url, method, reqMock, Optional.empty(), false);
        AsyncResponseHandler responseHandlerMock = mock(AsyncResponseHandler.class);

        Span initialSpan = (currentTracingInfoNull) ? null : Tracer.getInstance().startRequestWithRootSpan("foo");
        Deque<Span> initialSpanStack = (currentTracingInfoNull)
                                       ? null
                                       : Tracer.getInstance().getCurrentSpanStackCopy();
        Map<String, String> initialMdc = (currentTracingInfoNull) ? null : MDC.getCopyOfContextMap();
        resetTracingAndMdc();

        // when
        CompletableFuture resultFuture = helperSpy.executeAsyncHttpRequest(
            rbw, responseHandlerMock, initialSpanStack, initialMdc
        );

        // then
        // Verify that the circuit breaker came from the getCircuitBreaker helper method and that its
        //      throwExceptionIfCircuitBreakerIsOpen() method was called.
        verify(helperSpy).getCircuitBreaker(rbw);
        verify(cbManualTaskMock).throwExceptionIfCircuitBreakerIsOpen();

        // Verify that the inner request's execute method was called with a
        //      AsyncCompletionHandlerWithTracingAndMdcSupport for the handler.
        ArgumentCaptor<AsyncHandler> executedHandlerCaptor = ArgumentCaptor.forClass(AsyncHandler.class);
        verify(reqMock).execute(executedHandlerCaptor.capture());
        AsyncHandler executedHandler = executedHandlerCaptor.getValue();
        assertThat(executedHandler).isInstanceOf(AsyncCompletionHandlerWithTracingAndMdcSupport.class);

        // Verify that the AsyncCompletionHandlerWithTracingAndMdcSupport was created with the expected args
        AsyncCompletionHandlerWithTracingAndMdcSupport achwtams =
            (AsyncCompletionHandlerWithTracingAndMdcSupport) executedHandler;
        assertThat(achwtams.completableFutureResponse).isSameAs(resultFuture);
        assertThat(achwtams.responseHandlerFunction).isSameAs(responseHandlerMock);
        assertThat(achwtams.performSubSpanAroundDownstreamCalls).isEqualTo(performSubspan);
        assertThat(achwtams.circuitBreakerManualTask).isEqualTo(Optional.of(cbManualTaskMock));
        if (performSubspan) {
            int initialSpanStackSize = (initialSpanStack == null) ? 0 : initialSpanStack.size();
            assertThat(achwtams.distributedTraceStackToUse).hasSize(initialSpanStackSize + 1);
            Span subspan = (Span) achwtams.distributedTraceStackToUse.peek();
            assertThat(subspan.getSpanName())
                .isEqualTo(initialSpanNameFromStrategy.get());
            if (initialSpan != null) {
                assertThat(subspan.getTraceId()).isEqualTo(initialSpan.getTraceId());
                assertThat(subspan.getParentSpanId()).isEqualTo(initialSpan.getSpanId());
            }
            assertThat(achwtams.mdcContextToUse.get(SpanFieldForLoggerMdc.TRACE_ID.mdcKey)).isEqualTo(subspan.getTraceId());
        }
        else {
            assertThat(achwtams.distributedTraceStackToUse).isSameAs(initialSpanStack);
            assertThat(achwtams.mdcContextToUse).isSameAs(initialMdc);
        }

        // Verify that the trace headers were added (or not depending on state).
        Span spanForDownstreamCall = achwtams.getSpanForCall();
        if (initialSpan == null && !performSubspan) {
            assertThat(spanForDownstreamCall).isNull();
            verifyZeroInteractions(reqMock);
        }
        else {
            assertThat(spanForDownstreamCall).isNotNull();
            verify(reqMock).setHeader(TraceHeaders.TRACE_SAMPLED,
                                      convertSampleableBooleanToExpectedB3Value(spanForDownstreamCall.isSampleable()));
            verify(reqMock).setHeader(TraceHeaders.TRACE_ID, spanForDownstreamCall.getTraceId());
            verify(reqMock).setHeader(TraceHeaders.SPAN_ID, spanForDownstreamCall.getSpanId());
            if (spanForDownstreamCall.getParentSpanId() == null) {
                verify(reqMock, never()).setHeader(eq(TraceHeaders.PARENT_SPAN_ID), anyString());
            }
            else {
                verify(reqMock).setHeader(TraceHeaders.PARENT_SPAN_ID, spanForDownstreamCall.getParentSpanId());
            }
            verify(reqMock, never()).setHeader(eq(TraceHeaders.SPAN_NAME), anyString());
        }

        // Verify that any subspan had request tagging performed.
        if (performSubspan) {
            strategyRequestTaggingArgs.get().verifyArgs(spanForDownstreamCall, rbw, wingtipsTagAndNamingAdapterMock);
        }
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void executeAsyncHttpRequest_completes_future_if_exception_happens_during_setup(
        boolean throwCircuitBreakerOpenException) {
        // given
        RuntimeException exToThrow = (throwCircuitBreakerOpenException)
                                     ? new CircuitBreakerOpenException("foo", "kaboom")
                                     : new RuntimeException("kaboom");
        doThrow(exToThrow).when(helperSpy).getCircuitBreaker(any(RequestBuilderWrapper.class));
        Logger loggerMock = mock(Logger.class);
        Whitebox.setInternalState(helperSpy, "logger", loggerMock);

        // when
        CompletableFuture result = helperSpy
            .executeAsyncHttpRequest(mock(RequestBuilderWrapper.class), mock(AsyncResponseHandler.class), null, null);

        // then
        assertThat(result).isCompletedExceptionally();
        Throwable ex = catchThrowable(result::get);
        assertThat(ex)
            .isInstanceOf(ExecutionException.class)
            .hasCause(exToThrow);
        if (throwCircuitBreakerOpenException)
            verifyZeroInteractions(loggerMock);
        else
            verify(loggerMock).error(anyString(), anyString(), anyString(), eq(exToThrow));
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void getCircuitBreaker_returns_CircuitBreakerDelegate_wrapping_default_CircuitBreakerForHttpStatusCode_using_host_as_the_key(
        boolean useNettyEventLoop
    ) {
        // given
        String host = UUID.randomUUID().toString();
        String url = "http://" + host + "/some/path";
        String method = "GET";
        AsyncHttpClient.BoundRequestBuilder reqMock = mock(AsyncHttpClient.BoundRequestBuilder.class);
        Optional<CircuitBreaker<Response>> customCb = Optional.empty();
        RequestBuilderWrapper rbw = new RequestBuilderWrapper(url, method, reqMock, customCb, false);
        if (useNettyEventLoop)
            rbw.setCtx(ctxMock);

        // when
        Optional<CircuitBreaker<Response>> result = helperSpy.getCircuitBreaker(rbw);

        // then
        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(CircuitBreakerDelegate.class);
        CircuitBreakerDelegate<Response, Integer> wrapper = (CircuitBreakerDelegate) result.get();
        CircuitBreaker<Integer> delegate = (CircuitBreaker<Integer>) Whitebox.getInternalState(wrapper, "delegate");
        Function<Response, Integer> eventConverter =
            (Function<Response, Integer>) Whitebox.getInternalState(wrapper, "eventConverter");

        assertThat(delegate)
            .isSameAs(CircuitBreakerForHttpStatusCode.getDefaultHttpStatusCodeCircuitBreakerForKey(host));

        Response responseMock = mock(Response.class);
        doReturn(42).when(responseMock).getStatusCode();
        assertThat(eventConverter.apply(responseMock)).isEqualTo(42);
        assertThat(eventConverter.apply(null)).isNull();

        if (useNettyEventLoop) {
            assertThat(Whitebox.getInternalState(delegate, "scheduler")).isEqualTo(eventLoopMock);
            assertThat(Whitebox.getInternalState(delegate, "stateChangeNotificationExecutor")).isEqualTo(eventLoopMock);
        }
        else {
            assertThat(Whitebox.getInternalState(delegate, "scheduler")).isNotEqualTo(eventLoopMock);
            assertThat(Whitebox.getInternalState(delegate, "stateChangeNotificationExecutor"))
                .isNotEqualTo(eventLoopMock);
        }
    }

    @Test
    public void getCircuitBreaker_returns_custom_circuit_breaker_if_disableCircuitBreaker_is_false_and_customCircuitBreaker_exists() {
        // given
        Optional<CircuitBreaker<Response>> customCb = Optional.of(mock(CircuitBreaker.class));
        RequestBuilderWrapper rbw = new RequestBuilderWrapper(
            "foo", "bar", mock(AsyncHttpClient.BoundRequestBuilder.class), customCb, false);

        // when
        Optional<CircuitBreaker<Response>> result = helperSpy.getCircuitBreaker(rbw);

        // then
        assertThat(result).isSameAs(customCb);
    }

    @Test
    public void getCircuitBreaker_returns_empty_if_disableCircuitBreaker_is_true() {
        // given
        RequestBuilderWrapper rbw = new RequestBuilderWrapper(
            "foo", "bar", mock(AsyncHttpClient.BoundRequestBuilder.class), Optional.of(mock(CircuitBreaker.class)),
            true);

        // when
        Optional<CircuitBreaker<Response>> result = helperSpy.getCircuitBreaker(rbw);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    public void multiIpAwareNameResolver_resolve_works_as_expected() throws UnknownHostException {
        // given
        MultiIpAwareNameResolver resolver = spy(new MultiIpAwareNameResolver());
        InetAddress[] ipAddresses = new InetAddress[]{
            mock(InetAddress.class), mock(InetAddress.class), mock(InetAddress.class)
        };
        String host = UUID.randomUUID().toString();
        doReturn(ipAddresses).when(resolver).getAllAddressesForHost(host);

        for (int i = 0; i < (ipAddresses.length * 10); i++) {
            InetAddress expectedAddress = ipAddresses[i % ipAddresses.length];

            // when
            InetAddress actualAddress = resolver.resolve(host);

            // then
            assertThat(actualAddress).isSameAs(expectedAddress);
        }
    }

    @Test
    public void multiIpAwareNameResolver_resets_HOST_ROUND_ROBIN_COUNTER_MAP_counter_if_it_overflows()
        throws UnknownHostException {
        // given
        MultiIpAwareNameResolver resolver = spy(new MultiIpAwareNameResolver());
        InetAddress[] ipAddresses = new InetAddress[]{
            mock(InetAddress.class), mock(InetAddress.class), mock(InetAddress.class)
        };
        String host = UUID.randomUUID().toString();
        doReturn(ipAddresses).when(resolver).getAllAddressesForHost(host);

        // Initialize HOST_ROUND_ROBIN_COUNTER_MAP for this host.
        resolver.resolve(host);

        // Artificially set HOST_ROUND_ROBIN_COUNTER_MAP to have max int value for this host.
        AtomicInteger counterForHost = MultiIpAwareNameResolver.HOST_ROUND_ROBIN_COUNTER_MAP.get(host);
        counterForHost.set(Integer.MAX_VALUE);

        assertThat(counterForHost.get()).isEqualTo(Integer.MAX_VALUE);

        // Resolving again should overflow the counter
        resolver.resolve(host);

        assertThat(counterForHost.get()).isEqualTo(Integer.MIN_VALUE);

        // when
        resolver.resolve(host);

        // then
        // The resolve call with negative HOST_ROUND_ROBIN_COUNTER_MAP counter value should have caused the counter to
        //      be reset to zero when the overflow was detected.
        assertThat(counterForHost.get()).isEqualTo(0);
    }
}