package com.nike.riposte.client.asynchttp;

import com.nike.fastbreak.CircuitBreaker;
import com.nike.fastbreak.CircuitBreaker.ManualModeTask;
import com.nike.internal.util.Pair;
import com.nike.riposte.client.asynchttp.testutils.ArgCapturingHttpTagAndSpanNamingStrategy;
import com.nike.riposte.client.asynchttp.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.InitialSpanNameArgs;
import com.nike.riposte.client.asynchttp.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.RequestTaggingArgs;
import com.nike.riposte.client.asynchttp.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.ResponseTaggingArgs;
import com.nike.riposte.server.config.distributedtracing.SpanNamingAndTaggingStrategy;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.Tracer.SpanFieldForLoggerMdc;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;
import com.nike.wingtips.tags.HttpTagAndSpanNamingStrategy;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.nike.riposte.testutils.Whitebox;
import org.slf4j.MDC;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.nike.riposte.client.asynchttp.AsyncCompletionHandlerWithTracingAndMdcSupportTest.ExistingSpanStackState.EMPTY;
import static java.lang.Boolean.TRUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link AsyncCompletionHandlerWithTracingAndMdcSupport}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class AsyncCompletionHandlerWithTracingAndMdcSupportTest {

    private CompletableFuture<String> completableFutureResponse;
    private AsyncResponseHandler<String> responseHandlerFunctionMock;
    private Response responseMock;
    private String responseHandlerResult;
    private RequestBuilderWrapper requestBuilderWrapper;
    private String downstreamMethod;
    private String downstreamUrl;
    private ManualModeTask<Response> circuitBreakerManualTaskMock;
    private Deque<Span> initialSpanStack;
    private Map<String, String> initialMdcInfo;
    private AsyncCompletionHandlerWithTracingAndMdcSupport<String> handlerSpy;

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
    public void beforeMethod() throws Throwable {
        resetTracingAndMdc();

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

        completableFutureResponse = new CompletableFuture<>();
        responseHandlerFunctionMock = mock(AsyncResponseHandler.class);
        downstreamMethod = "method-" + UUID.randomUUID().toString();
        downstreamUrl = "url-" + UUID.randomUUID().toString();
        circuitBreakerManualTaskMock = mock(ManualModeTask.class);

        requestBuilderWrapper = new RequestBuilderWrapper(
            downstreamUrl, downstreamMethod, mock(BoundRequestBuilder.class), Optional.empty(), true);

        responseMock = mock(Response.class);
        responseHandlerResult = "result-" + UUID.randomUUID().toString();
        doReturn(responseHandlerResult).when(responseHandlerFunctionMock).handleResponse(responseMock);

        Tracer.getInstance().startRequestWithRootSpan("overallReqSpan");
        initialSpanStack = Tracer.getInstance().getCurrentSpanStackCopy();
        initialMdcInfo = MDC.getCopyOfContextMap();

        handlerSpy = spy(new AsyncCompletionHandlerWithTracingAndMdcSupport<>(
            completableFutureResponse, responseHandlerFunctionMock, true, requestBuilderWrapper,
            Optional.of(circuitBreakerManualTaskMock), initialSpanStack, initialMdcInfo,
            tagAndNamingStrategy
        ));

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
    public void constructor_sets_values_exactly_as_given_when_subtracing_is_off() {
        // given
        CompletableFuture cfResponse = mock(CompletableFuture.class);
        AsyncResponseHandler responseHandlerFunc = mock(AsyncResponseHandler.class);
        RequestBuilderWrapper rbwMock = mock(RequestBuilderWrapper.class);
        Optional<CircuitBreaker<Response>> circuitBreaker = Optional.of(mock(CircuitBreaker.class));
        Deque<Span> spanStack = mock(Deque.class);
        Map<String, String> mdcInfo = mock(Map.class);

        Deque<Span> spanStackBeforeCall = Tracer.getInstance().getCurrentSpanStackCopy();
        Map<String, String> mdcInfoBeforeCall = MDC.getCopyOfContextMap();

        // when
        AsyncCompletionHandlerWithTracingAndMdcSupport instance = new AsyncCompletionHandlerWithTracingAndMdcSupport(
            cfResponse, responseHandlerFunc, false, rbwMock, circuitBreaker, spanStack, mdcInfo,
            tagAndNamingStrategy
        );

        // then
        assertThat(instance.completableFutureResponse).isSameAs(cfResponse);
        assertThat(instance.responseHandlerFunction).isSameAs(responseHandlerFunc);
        assertThat(instance.performSubSpanAroundDownstreamCalls).isEqualTo(false);
        assertThat(instance.circuitBreakerManualTask).isSameAs(circuitBreaker);
        assertThat(instance.distributedTraceStackToUse).isSameAs(spanStack);
        assertThat(instance.mdcContextToUse).isSameAs(mdcInfo);

        assertThat(Tracer.getInstance().getCurrentSpanStackCopy()).isEqualTo(spanStackBeforeCall);
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(mdcInfoBeforeCall);
    }

    protected enum ExistingSpanStackState {
        NULL, EMPTY, HAS_EXISTING_SPAN
    }

    @DataProvider(value = {
        "NULL",
        "EMPTY",
        "HAS_EXISTING_SPAN"
    }, splitBy = "\\|")
    @Test
    public void constructor_sets_values_with_subspan_when_subtracing_is_on(
        ExistingSpanStackState existingSpanStackState) {
        // given
        CompletableFuture cfResponse = mock(CompletableFuture.class);
        AsyncResponseHandler responseHandlerFunc = mock(AsyncResponseHandler.class);
        RequestBuilderWrapper rbwMock = mock(RequestBuilderWrapper.class);
        Optional<CircuitBreaker<Response>> circuitBreaker = Optional.of(mock(CircuitBreaker.class));
        Span initialSpan = null;
        switch (existingSpanStackState) {
            case NULL:
            case EMPTY: //intentional fall-through
                resetTracingAndMdc();
                break;
            case HAS_EXISTING_SPAN:
                initialSpan = Tracer.getInstance().startRequestWithRootSpan("overallReqSpan");
                break;
            default:
                throw new IllegalArgumentException("Unhandled state: " + existingSpanStackState.name());
        }

        Deque<Span> spanStack =
            (existingSpanStackState == EMPTY) ? new LinkedList<>() : Tracer.getInstance().getCurrentSpanStackCopy();
        Map<String, String> mdcInfo = (existingSpanStackState == EMPTY) ? new HashMap<>() : MDC.getCopyOfContextMap();

        resetTracingAndMdc();

        Deque<Span> spanStackBeforeCall = Tracer.getInstance().getCurrentSpanStackCopy();
        Map<String, String> mdcInfoBeforeCall = MDC.getCopyOfContextMap();

        // when
        AsyncCompletionHandlerWithTracingAndMdcSupport instance = new AsyncCompletionHandlerWithTracingAndMdcSupport(
            cfResponse, responseHandlerFunc, true, rbwMock, circuitBreaker, spanStack, mdcInfo,
            tagAndNamingStrategy
        );

        // then
        assertThat(instance.completableFutureResponse).isSameAs(cfResponse);
        assertThat(instance.responseHandlerFunction).isSameAs(responseHandlerFunc);
        assertThat(instance.performSubSpanAroundDownstreamCalls).isEqualTo(true);
        assertThat(instance.circuitBreakerManualTask).isSameAs(circuitBreaker);

        int initialSpanStackSize = (spanStack == null) ? 0 : spanStack.size();
        assertThat(instance.distributedTraceStackToUse).hasSize(initialSpanStackSize + 1);
        Span subspan = (Span) instance.distributedTraceStackToUse.peek();
        assertThat(instance.mdcContextToUse.get(SpanFieldForLoggerMdc.TRACE_ID.mdcKey)).isEqualTo(subspan.getTraceId());

        if (existingSpanStackState == ExistingSpanStackState.NULL || existingSpanStackState == EMPTY) {
            assertThat(instance.distributedTraceStackToUse).hasSize(1);
        }
        else {
            assertThat(instance.distributedTraceStackToUse.peekLast()).isEqualTo(initialSpan);
            assertThat(subspan).isNotEqualTo(initialSpan);
            assertThat(subspan.getTraceId()).isEqualTo(initialSpan.getTraceId());
            assertThat(subspan.getParentSpanId()).isEqualTo(initialSpan.getSpanId());
            assertThat(subspan.getSpanName()).isEqualTo(instance.getSubspanSpanName(
                rbwMock, tagAndNamingStrategy
            ));
        }

        assertThat(Tracer.getInstance().getCurrentSpanStackCopy()).isEqualTo(spanStackBeforeCall);
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(mdcInfoBeforeCall);
    }

    @DataProvider(value = {
        "NULL",
        "EMPTY",
        "HAS_EXISTING_SPAN"
    }, splitBy = "\\|")
    @Test
    public void getTraceForCall_works_as_expected(ExistingSpanStackState existingSpanStackState) {
        // given
        Deque<Span> spanStack;
        Span expectedResult;
        switch (existingSpanStackState) {
            case NULL:
                spanStack = null;
                expectedResult = null;
                break;
            case EMPTY:
                spanStack = new LinkedList<>();
                expectedResult = null;
                break;
            case HAS_EXISTING_SPAN:
                spanStack = handlerSpy.distributedTraceStackToUse;
                assertThat(spanStack).isNotEmpty();
                expectedResult = spanStack.peek();
                break;
            default:
                throw new IllegalArgumentException("Unhandled state: " + existingSpanStackState.name());
        }
        Whitebox.setInternalState(handlerSpy, "distributedTraceStackToUse", spanStack);

        // when
        Span spanForCall = handlerSpy.getSpanForCall();

        // then
        assertThat(spanForCall).isEqualTo(expectedResult);
    }

    @DataProvider(value = {
        "spanNameFromStrategy   |   PATCH           |   spanNameFromStrategy",
        "null                   |   PATCH           |   async_downstream_call-PATCH",
        "                       |   PATCH           |   async_downstream_call-PATCH",
        "[whitespace]           |   PATCH           |   async_downstream_call-PATCH",
        "null                   |   null            |   async_downstream_call-UNKNOWN_HTTP_METHOD",
    }, splitBy = "\\|")
    @Test
    public void getSubspanSpanName_works_as_expected(
        String strategyResult, String httpMethod, String expectedResult
    ) {
        // given
        if ("[whitespace]".equals(strategyResult)) {
            strategyResult = "  \n\r\t  ";
        }

        initialSpanNameFromStrategy.set(strategyResult);
        requestBuilderWrapper.setHttpMethod(httpMethod);

        // when
        String result = handlerSpy.getSubspanSpanName(
            requestBuilderWrapper, tagAndNamingStrategy
        );

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void onCompleted_completes_completableFutureResponse_with_result_of_responseHandlerFunction(
        boolean throwException
    ) throws Throwable {
        // given
        Exception ex = new Exception("kaboom");
        if (throwException)
            doThrow(ex).when(responseHandlerFunctionMock).handleResponse(responseMock);

        // when
        Response ignoredResult = handlerSpy.onCompleted(responseMock);

        // then
        verify(responseHandlerFunctionMock).handleResponse(responseMock);
        if (throwException) {
            assertThat(completableFutureResponse).isCompletedExceptionally();
            assertThat(completableFutureResponse).hasFailedWithThrowableThat().isEqualTo(ex);
        }
        else {
            assertThat(completableFutureResponse).isCompleted();
            assertThat(completableFutureResponse.get()).isEqualTo(responseHandlerResult);
        }

        assertThat(ignoredResult).isEqualTo(responseMock);
        verifyZeroInteractions(responseMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void onCompleted_interacts_with_circuit_breaker_appropriately(boolean throwException) throws Throwable {
        // given
        RuntimeException ex = new RuntimeException("kaboom");
        if (throwException)
            doThrow(ex).when(circuitBreakerManualTaskMock).handleEvent(responseMock);

        // when
        handlerSpy.onCompleted(responseMock);

        // then
        verify(circuitBreakerManualTaskMock).handleEvent(responseMock);
        assertThat(completableFutureResponse).isCompleted();
        assertThat(completableFutureResponse.get()).isEqualTo(responseHandlerResult);
    }

    @Test
    public void onCompleted_handles_circuit_breaker_but_does_nothing_else_if_completableFutureResponse_is_already_completed()
        throws Throwable {
        // given
        CompletableFuture<String> cfMock = mock(CompletableFuture.class);
        Whitebox.setInternalState(handlerSpy, "completableFutureResponse", cfMock);
        doReturn(true).when(cfMock).isDone();

        // when
        Response ignoredResult = handlerSpy.onCompleted(responseMock);

        // then
        verify(circuitBreakerManualTaskMock).handleEvent(responseMock);

        verify(cfMock).isDone();
        verifyNoMoreInteractions(cfMock);

        assertThat(ignoredResult).isEqualTo(responseMock);
        verifyZeroInteractions(responseMock);
    }

    private Pair<Deque<Span>, Map<String, String>> generateTraceInfo(Boolean setupForSubspan) {
        if (setupForSubspan == null)
            return Pair.of(null, null);

        try {
            resetTracingAndMdc();
            Tracer.getInstance().startRequestWithRootSpan("overallReqSpan");

            if (setupForSubspan)
                Tracer.getInstance().startSubSpan("subSpan", Span.SpanPurpose.LOCAL_ONLY);

            return Pair.of(Tracer.getInstance().getCurrentSpanStackCopy(), MDC.getCopyOfContextMap());
        }
        finally {
            resetTracingAndMdc();
        }
    }

    private static class ObjectHolder<T> {

        public T obj;
        public boolean objSet = false;

        public void setObj(T obj) {
            this.obj = obj;
            this.objSet = true;
        }
    }

    private Pair<ObjectHolder<Span>, ObjectHolder<Span>> setupBeforeAndAfterSpanCaptureForOnCompleted()
        throws Throwable {
        ObjectHolder<Span> before = new ObjectHolder<>();
        ObjectHolder<Span> after = new ObjectHolder<>();

        doAnswer(invocation -> {
            before.setObj(Tracer.getInstance().getCurrentSpan());
            return null;
        }).when(circuitBreakerManualTaskMock).handleEvent(responseMock);

        doAnswer(invocation -> {
            after.setObj(Tracer.getInstance().getCurrentSpan());
            return responseHandlerResult;
        }).when(responseHandlerFunctionMock).handleResponse(responseMock);

        return Pair.of(before, after);
    }

    @DataProvider(value = {
        "null",
        "false",
        "true"
    })
    @Test
    public void onCompleted_deals_with_trace_info_as_expected(Boolean setupForSubspan) throws Throwable {
        // given
        Pair<Deque<Span>, Map<String, String>> traceInfo = generateTraceInfo(setupForSubspan);
        Whitebox.setInternalState(handlerSpy, "distributedTraceStackToUse", traceInfo.getLeft());
        Whitebox.setInternalState(handlerSpy, "mdcContextToUse", traceInfo.getRight());
        Whitebox.setInternalState(handlerSpy, "performSubSpanAroundDownstreamCalls", TRUE.equals(setupForSubspan));
        Span expectedSpanBeforeCompletion = (traceInfo.getLeft() == null) ? null : traceInfo.getLeft().peek();
        Span expectedSpanAfterCompletion = (setupForSubspan == null)
                                           ? null
                                           // If setupForSubspan is true, then there will be two items and peekLast will
                                           //       get the "parent". Otherwise there will only be one item, and
                                           //       peekLast will still get the correct thing (in this case the only
                                           //       one there).
                                           : traceInfo.getLeft().peekLast();
        Pair<ObjectHolder<Span>, ObjectHolder<Span>> actualBeforeAndAfterSpanHolders =
            setupBeforeAndAfterSpanCaptureForOnCompleted();

        // when
        handlerSpy.onCompleted(responseMock);

        // then
        verify(circuitBreakerManualTaskMock).handleEvent(responseMock);
        verify(responseHandlerFunctionMock).handleResponse(responseMock);

        assertThat(actualBeforeAndAfterSpanHolders.getLeft().objSet).isTrue();
        assertThat(actualBeforeAndAfterSpanHolders.getRight().objSet).isTrue();

        assertThat(actualBeforeAndAfterSpanHolders.getLeft().obj).isEqualTo(expectedSpanBeforeCompletion);
        assertThat(actualBeforeAndAfterSpanHolders.getRight().obj).isEqualTo(expectedSpanAfterCompletion);

        if (TRUE.equals(setupForSubspan)) {
            assertThat(strategyResponseTaggingAndFinalSpanNameMethodCalled.get()).isTrue();
            strategyResponseTaggingArgs.get().verifyArgs(
                expectedSpanBeforeCompletion, handlerSpy.rbwCopyWithHttpMethodAndUrlOnly, responseMock,
                null, wingtipsTagAndNamingAdapterMock
            );
        }
        else {
            assertThat(strategyResponseTaggingAndFinalSpanNameMethodCalled.get()).isFalse();
        }
    }

    @DataProvider(value = {
        "null",
        "false",
        "true"
    })
    @Test
    public void onCompleted_does_nothing_to_trace_info_if_performSubSpanAroundDownstreamCalls_is_false(
        Boolean setupForSubspan) throws Throwable {
        // given
        Pair<Deque<Span>, Map<String, String>> traceInfo = generateTraceInfo(setupForSubspan);
        Whitebox.setInternalState(handlerSpy, "distributedTraceStackToUse", traceInfo.getLeft());
        Whitebox.setInternalState(handlerSpy, "mdcContextToUse", traceInfo.getRight());
        Whitebox.setInternalState(handlerSpy, "performSubSpanAroundDownstreamCalls", false);
        Pair<ObjectHolder<Span>, ObjectHolder<Span>> actualBeforeAndAfterSpanHolders =
            setupBeforeAndAfterSpanCaptureForOnCompleted();

        // when
        handlerSpy.onCompleted(responseMock);

        // then
        verify(circuitBreakerManualTaskMock).handleEvent(responseMock);
        verify(responseHandlerFunctionMock).handleResponse(responseMock);

        assertThat(actualBeforeAndAfterSpanHolders.getLeft().objSet).isTrue();
        assertThat(actualBeforeAndAfterSpanHolders.getRight().objSet).isTrue();

        assertThat(actualBeforeAndAfterSpanHolders.getLeft().obj)
            .isEqualTo(actualBeforeAndAfterSpanHolders.getRight().obj);
    }

    @Test
    public void onThrowable_completes_completableFutureResponse_exceptionally_with_provided_error() {
        // given
        Exception ex = new Exception("kaboom");

        // when
        handlerSpy.onThrowable(ex);

        // then
        assertThat(completableFutureResponse).isCompletedExceptionally();
        assertThat(completableFutureResponse).hasFailedWithThrowableThat().isEqualTo(ex);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void onThrowable_interacts_with_circuit_breaker_appropriately(boolean throwException) throws Throwable {
        // given
        Exception ex = new Exception("kaboom");
        RuntimeException circuitBreakerEx = new RuntimeException("circuit breaker kaboom");
        if (throwException)
            doThrow(circuitBreakerEx).when(circuitBreakerManualTaskMock).handleException(ex);

        // when
        handlerSpy.onThrowable(ex);

        // then
        verify(circuitBreakerManualTaskMock).handleException(ex);
        assertThat(completableFutureResponse).isCompletedExceptionally();
        assertThat(completableFutureResponse).hasFailedWithThrowableThat().isEqualTo(ex);
    }

    @Test
    public void onThrowable_handles_circuit_breaker_but_does_nothing_else_if_completableFutureResponse_is_already_completed()
        throws Throwable {
        // given
        Exception ex = new Exception("kaboom");
        CompletableFuture<String> cfMock = mock(CompletableFuture.class);
        Whitebox.setInternalState(handlerSpy, "completableFutureResponse", cfMock);
        doReturn(true).when(cfMock).isDone();

        // when
        handlerSpy.onThrowable(ex);

        // then
        verify(circuitBreakerManualTaskMock).handleException(ex);

        verify(cfMock).isDone();
        verifyNoMoreInteractions(cfMock);
    }

    private Pair<ObjectHolder<Span>, ObjectHolder<Span>> setupBeforeAndAfterSpanCaptureForOnThrowable(
        CompletableFuture<String> cfMock) throws Throwable {
        ObjectHolder<Span> before = new ObjectHolder<>();
        ObjectHolder<Span> after = new ObjectHolder<>();

        doAnswer(invocation -> {
            before.setObj(Tracer.getInstance().getCurrentSpan());
            return invocation.callRealMethod();
        }).when(circuitBreakerManualTaskMock).handleException(any(Throwable.class));

        doAnswer(invocation -> {
            after.setObj(Tracer.getInstance().getCurrentSpan());
            return invocation.callRealMethod();
        }).when(cfMock).completeExceptionally(any(Throwable.class));

        return Pair.of(before, after);
    }

    @DataProvider(value = {
        "null",
        "false",
        "true"
    })
    @Test
    public void onThrowable_deals_with_trace_info_as_expected(Boolean setupForSubspan) throws Throwable {
        // given
        Exception ex = new Exception("kaboom");
        CompletableFuture<String> cfMock = mock(CompletableFuture.class);
        Whitebox.setInternalState(handlerSpy, "completableFutureResponse", cfMock);
        doReturn(false).when(cfMock).isDone();

        Pair<Deque<Span>, Map<String, String>> traceInfo = generateTraceInfo(setupForSubspan);
        Whitebox.setInternalState(handlerSpy, "distributedTraceStackToUse", traceInfo.getLeft());
        Whitebox.setInternalState(handlerSpy, "mdcContextToUse", traceInfo.getRight());
        Whitebox.setInternalState(handlerSpy, "performSubSpanAroundDownstreamCalls", TRUE.equals(setupForSubspan));
        Span expectedSpanBeforeCompletion = (traceInfo.getLeft() == null) ? null : traceInfo.getLeft().peek();
        Span expectedSpanAfterCompletion = (setupForSubspan == null)
                                           ? null
                                           // If setupForSubspan is true, then there will be two items and peekLast will
                                           //       get the "parent". Otherwise there will only be one item, and
                                           //       peekLast will still get the correct thing (in this case the only
                                           //       one there).
                                           : traceInfo.getLeft().peekLast();
        Pair<ObjectHolder<Span>, ObjectHolder<Span>> actualBeforeAndAfterSpanHolders =
            setupBeforeAndAfterSpanCaptureForOnThrowable(cfMock);

        // when
        handlerSpy.onThrowable(ex);

        // then
        verify(circuitBreakerManualTaskMock).handleException(ex);
        verify(cfMock).completeExceptionally(ex);

        assertThat(actualBeforeAndAfterSpanHolders.getLeft().objSet).isTrue();
        assertThat(actualBeforeAndAfterSpanHolders.getRight().objSet).isTrue();

        assertThat(actualBeforeAndAfterSpanHolders.getLeft().obj).isEqualTo(expectedSpanBeforeCompletion);
        assertThat(actualBeforeAndAfterSpanHolders.getRight().obj).isEqualTo(expectedSpanAfterCompletion);

        if (TRUE.equals(setupForSubspan)) {
            assertThat(strategyResponseTaggingAndFinalSpanNameMethodCalled.get()).isTrue();
            strategyResponseTaggingArgs.get().verifyArgs(
                expectedSpanBeforeCompletion, handlerSpy.rbwCopyWithHttpMethodAndUrlOnly, null,
                ex, wingtipsTagAndNamingAdapterMock
            );
        }
        else {
            assertThat(strategyResponseTaggingAndFinalSpanNameMethodCalled.get()).isFalse();
        }
    }

    @DataProvider(value = {
        "null",
        "false",
        "true"
    })
    @Test
    public void onThrowable_does_nothing_to_trace_info_if_performSubSpanAroundDownstreamCalls_is_false(
        Boolean setupForSubspan) throws Throwable {
        // given
        Exception ex = new Exception("kaboom");
        CompletableFuture<String> cfMock = mock(CompletableFuture.class);
        Whitebox.setInternalState(handlerSpy, "completableFutureResponse", cfMock);
        doReturn(false).when(cfMock).isDone();

        Pair<Deque<Span>, Map<String, String>> traceInfo = generateTraceInfo(setupForSubspan);
        Whitebox.setInternalState(handlerSpy, "distributedTraceStackToUse", traceInfo.getLeft());
        Whitebox.setInternalState(handlerSpy, "mdcContextToUse", traceInfo.getRight());
        Whitebox.setInternalState(handlerSpy, "performSubSpanAroundDownstreamCalls", false);
        Pair<ObjectHolder<Span>, ObjectHolder<Span>> actualBeforeAndAfterSpanHolders =
            setupBeforeAndAfterSpanCaptureForOnThrowable(cfMock);

        // when
        handlerSpy.onThrowable(ex);

        // then
        verify(circuitBreakerManualTaskMock).handleException(ex);
        verify(cfMock).completeExceptionally(ex);

        assertThat(actualBeforeAndAfterSpanHolders.getLeft().objSet).isTrue();
        assertThat(actualBeforeAndAfterSpanHolders.getRight().objSet).isTrue();

        assertThat(actualBeforeAndAfterSpanHolders.getLeft().obj)
            .isEqualTo(actualBeforeAndAfterSpanHolders.getRight().obj);
    }
}