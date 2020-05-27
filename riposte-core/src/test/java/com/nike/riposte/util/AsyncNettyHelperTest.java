package com.nike.riposte.util;

import com.nike.fastbreak.CircuitBreaker;
import com.nike.fastbreak.CircuitBreakerImpl;
import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.testutils.TestUtil;
import com.nike.riposte.util.asynchelperwrapper.BiConsumerWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.BiFunctionWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.CallableWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.ConsumerWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.FunctionWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.RunnableWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.SupplierWithTracingAndMdcSupport;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.Tracer.SpanFieldForLoggerMdc;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.nike.riposte.testutils.Whitebox;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests the functionality of {@link AsyncNettyHelper}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class AsyncNettyHelperTest {

    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private Attribute<ProxyRouterProcessingState> proxyRouterStateAttrMock;
    private HttpProcessingState state;
    private ProxyRouterProcessingState proxyRouterStateMock;
    private RequestInfo requestInfoMock;
    private Executor executor;

    private Runnable runnableMock;
    private Callable callableMock;
    private Supplier supplierMock;
    private Function functionMock;
    private BiFunction biFunctionMock;
    private Consumer consumerMock;
    private BiConsumer biConsumerMock;

    List<Deque<Span>> currentSpanStackWhenRequestResourcesReleased;
    List<Map<String, String>> currentMdcInfoWhenRequestResourcesReleased;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        proxyRouterStateAttrMock = mock(Attribute.class);
        state = new HttpProcessingState();
        proxyRouterStateMock = mock(ProxyRouterProcessingState.class);
        requestInfoMock = mock(RequestInfo.class);
        executor = Executors.newCachedThreadPool();
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();
        doReturn(proxyRouterStateAttrMock).when(channelMock).attr(ChannelAttributes.PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(proxyRouterStateMock).when(proxyRouterStateAttrMock).get();
        state.setRequestInfo(requestInfoMock);

        runnableMock = mock(Runnable.class);
        callableMock = mock(Callable.class);
        supplierMock = mock(Supplier.class);
        functionMock = mock(Function.class);
        biFunctionMock = mock(BiFunction.class);
        consumerMock = mock(Consumer.class);
        biConsumerMock = mock(BiConsumer.class);

        currentSpanStackWhenRequestResourcesReleased = new ArrayList<>();
        currentMdcInfoWhenRequestResourcesReleased = new ArrayList<>();
        doAnswer(invocation -> {
            currentSpanStackWhenRequestResourcesReleased.add(Tracer.getInstance().getCurrentSpanStackCopy());
            currentMdcInfoWhenRequestResourcesReleased.add(MDC.getCopyOfContextMap());
            return null;
        }).when(requestInfoMock).releaseAllResources();

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

    private Pair<Deque<Span>, Map<String, String>> generateTracingAndMdcInfo() {
        resetTracingAndMdc();
        Tracer.getInstance().startRequestWithRootSpan("someSpan");
        Pair<Deque<Span>, Map<String, String>> result = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(), new HashMap<>(MDC.getCopyOfContextMap())
        );
        resetTracingAndMdc();
        return result;
    }

    private Pair<Deque<Span>, Map<String, String>> setupStateWithTracingAndMdcInfo() {
        Pair<Deque<Span>, Map<String, String>> result = generateTracingAndMdcInfo();
        state.setDistributedTraceStack(result.getLeft());
        state.setLoggerMdcContextMap(result.getRight());
        return result;
    }

    @Test
    public void code_coverage_hoops() {
        // jump!
        new AsyncNettyHelper();
    }

    private void verifyRunnableWithTracingAndMdcSupport(Runnable result, Runnable expectedCoreRunnable,
                                                        Deque<Span> expectedSpanStack,
                                                        Map<String, String> expectedMdcInfo) {
        assertThat(result).isInstanceOf(RunnableWithTracingAndMdcSupport.class);
        assertThat(Whitebox.getInternalState(result, "origRunnable")).isSameAs(expectedCoreRunnable);
        assertThat(Whitebox.getInternalState(result, "distributedTraceStackForExecution")).isSameAs(expectedSpanStack);
        assertThat(Whitebox.getInternalState(result, "mdcContextMapForExecution")).isSameAs(expectedMdcInfo);
    }

    @Test
    public void runnableWithTracingAndMdc_ctx_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = setupStateWithTracingAndMdcInfo();

        // when
        Runnable result = AsyncNettyHelper.runnableWithTracingAndMdc(runnableMock, ctxMock);

        // then
        verifyRunnableWithTracingAndMdcSupport(result, runnableMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void runnableWithTracingAndMdc_pair_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        Runnable result = AsyncNettyHelper.runnableWithTracingAndMdc(runnableMock, setupInfo);

        // then
        verifyRunnableWithTracingAndMdcSupport(result, runnableMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void runnableWithTracingAndMdc_separate_args_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        Runnable result = AsyncNettyHelper.runnableWithTracingAndMdc(runnableMock,
                                                                     setupInfo.getLeft(), setupInfo.getRight());

        // then
        verifyRunnableWithTracingAndMdcSupport(result, runnableMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    private void verifyCallableWithTracingAndMdcSupport(Callable result, Callable expectedCoreInstance,
                                                        Deque<Span> expectedSpanStack,
                                                        Map<String, String> expectedMdcInfo) {
        assertThat(result).isInstanceOf(CallableWithTracingAndMdcSupport.class);
        assertThat(Whitebox.getInternalState(result, "origCallable")).isSameAs(expectedCoreInstance);
        assertThat(Whitebox.getInternalState(result, "distributedTraceStackForExecution")).isSameAs(expectedSpanStack);
        assertThat(Whitebox.getInternalState(result, "mdcContextMapForExecution")).isSameAs(expectedMdcInfo);
    }

    @Test
    public void callableWithTracingAndMdc_ctx_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = setupStateWithTracingAndMdcInfo();

        // when
        Callable result = AsyncNettyHelper.callableWithTracingAndMdc(callableMock, ctxMock);

        // then
        verifyCallableWithTracingAndMdcSupport(result, callableMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void callableWithTracingAndMdc_pair_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        Callable result = AsyncNettyHelper.callableWithTracingAndMdc(callableMock, setupInfo);

        // then
        verifyCallableWithTracingAndMdcSupport(result, callableMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void callableWithTracingAndMdc_separate_args_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        Callable result = AsyncNettyHelper.callableWithTracingAndMdc(callableMock,
                                                                     setupInfo.getLeft(), setupInfo.getRight());

        // then
        verifyCallableWithTracingAndMdcSupport(result, callableMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    private void verifySupplierWithTracingAndMdcSupport(Supplier result, Supplier expectedCoreInstance,
                                                        Deque<Span> expectedSpanStack,
                                                        Map<String, String> expectedMdcInfo) {
        assertThat(result).isInstanceOf(SupplierWithTracingAndMdcSupport.class);
        assertThat(Whitebox.getInternalState(result, "origSupplier")).isSameAs(expectedCoreInstance);
        assertThat(Whitebox.getInternalState(result, "distributedTraceStackForExecution")).isSameAs(expectedSpanStack);
        assertThat(Whitebox.getInternalState(result, "mdcContextMapForExecution")).isSameAs(expectedMdcInfo);
    }

    @Test
    public void supplierWithTracingAndMdc_ctx_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = setupStateWithTracingAndMdcInfo();

        // when
        Supplier result = AsyncNettyHelper.supplierWithTracingAndMdc(supplierMock, ctxMock);

        // then
        verifySupplierWithTracingAndMdcSupport(result, supplierMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void supplierWithTracingAndMdc_pair_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        Supplier result = AsyncNettyHelper.supplierWithTracingAndMdc(supplierMock, setupInfo);

        // then
        verifySupplierWithTracingAndMdcSupport(result, supplierMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void supplierWithTracingAndMdc_separate_args_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        Supplier result = AsyncNettyHelper.supplierWithTracingAndMdc(supplierMock,
                                                                     setupInfo.getLeft(), setupInfo.getRight());

        // then
        verifySupplierWithTracingAndMdcSupport(result, supplierMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    private void verifyFunctionWithTracingAndMdcSupport(Function result, Function expectedCoreInstance,
                                                        Deque<Span> expectedSpanStack,
                                                        Map<String, String> expectedMdcInfo) {
        assertThat(result).isInstanceOf(FunctionWithTracingAndMdcSupport.class);
        assertThat(Whitebox.getInternalState(result, "origFunction")).isSameAs(expectedCoreInstance);
        assertThat(Whitebox.getInternalState(result, "distributedTraceStackForExecution")).isSameAs(expectedSpanStack);
        assertThat(Whitebox.getInternalState(result, "mdcContextMapForExecution")).isSameAs(expectedMdcInfo);
    }

    @Test
    public void functionWithTracingAndMdc_ctx_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = setupStateWithTracingAndMdcInfo();

        // when
        Function result = AsyncNettyHelper.functionWithTracingAndMdc(functionMock, ctxMock);

        // then
        verifyFunctionWithTracingAndMdcSupport(result, functionMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void functionWithTracingAndMdc_pair_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        Function result = AsyncNettyHelper.functionWithTracingAndMdc(functionMock, setupInfo);

        // then
        verifyFunctionWithTracingAndMdcSupport(result, functionMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void functionWithTracingAndMdc_separate_args_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        Function result = AsyncNettyHelper.functionWithTracingAndMdc(functionMock,
                                                                     setupInfo.getLeft(), setupInfo.getRight());

        // then
        verifyFunctionWithTracingAndMdcSupport(result, functionMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    private void verifyBiFunctionWithTracingAndMdcSupport(BiFunction result, BiFunction expectedCoreInstance,
                                                        Deque<Span> expectedSpanStack,
                                                        Map<String, String> expectedMdcInfo) {
        assertThat(result).isInstanceOf(BiFunctionWithTracingAndMdcSupport.class);
        assertThat(Whitebox.getInternalState(result, "origBiFunction")).isSameAs(expectedCoreInstance);
        assertThat(Whitebox.getInternalState(result, "distributedTraceStackForExecution")).isSameAs(expectedSpanStack);
        assertThat(Whitebox.getInternalState(result, "mdcContextMapForExecution")).isSameAs(expectedMdcInfo);
    }

    @Test
    public void biFunctionWithTracingAndMdc_ctx_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = setupStateWithTracingAndMdcInfo();

        // when
        BiFunction result = AsyncNettyHelper.biFunctionWithTracingAndMdc(biFunctionMock, ctxMock);

        // then
        verifyBiFunctionWithTracingAndMdcSupport(result, biFunctionMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void biFunctionWithTracingAndMdc_pair_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        BiFunction result = AsyncNettyHelper.biFunctionWithTracingAndMdc(biFunctionMock, setupInfo);

        // then
        verifyBiFunctionWithTracingAndMdcSupport(result, biFunctionMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void biFunctionWithTracingAndMdc_separate_args_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        BiFunction result = AsyncNettyHelper.biFunctionWithTracingAndMdc(biFunctionMock,
                                                                     setupInfo.getLeft(), setupInfo.getRight());

        // then
        verifyBiFunctionWithTracingAndMdcSupport(result, biFunctionMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    private void verifyConsumerWithTracingAndMdcSupport(Consumer result, Consumer expectedCoreInstance,
                                                        Deque<Span> expectedSpanStack,
                                                        Map<String, String> expectedMdcInfo) {
        assertThat(result).isInstanceOf(ConsumerWithTracingAndMdcSupport.class);
        assertThat(Whitebox.getInternalState(result, "origConsumer")).isSameAs(expectedCoreInstance);
        assertThat(Whitebox.getInternalState(result, "distributedTraceStackForExecution")).isSameAs(expectedSpanStack);
        assertThat(Whitebox.getInternalState(result, "mdcContextMapForExecution")).isSameAs(expectedMdcInfo);
    }

    @Test
    public void consumerWithTracingAndMdc_ctx_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = setupStateWithTracingAndMdcInfo();

        // when
        Consumer result = AsyncNettyHelper.consumerWithTracingAndMdc(consumerMock, ctxMock);

        // then
        verifyConsumerWithTracingAndMdcSupport(result, consumerMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void consumerWithTracingAndMdc_pair_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        Consumer result = AsyncNettyHelper.consumerWithTracingAndMdc(consumerMock, setupInfo);

        // then
        verifyConsumerWithTracingAndMdcSupport(result, consumerMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void consumerWithTracingAndMdc_separate_args_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        Consumer result = AsyncNettyHelper.consumerWithTracingAndMdc(consumerMock,
                                                                     setupInfo.getLeft(), setupInfo.getRight());

        // then
        verifyConsumerWithTracingAndMdcSupport(result, consumerMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    private void verifyBiConsumerWithTracingAndMdcSupport(BiConsumer result, BiConsumer expectedCoreInstance,
                                                        Deque<Span> expectedSpanStack,
                                                        Map<String, String> expectedMdcInfo) {
        assertThat(result).isInstanceOf(BiConsumerWithTracingAndMdcSupport.class);
        assertThat(Whitebox.getInternalState(result, "origBiConsumer")).isSameAs(expectedCoreInstance);
        assertThat(Whitebox.getInternalState(result, "distributedTraceStackForExecution")).isSameAs(expectedSpanStack);
        assertThat(Whitebox.getInternalState(result, "mdcContextMapForExecution")).isSameAs(expectedMdcInfo);
    }

    @Test
    public void biConsumerWithTracingAndMdc_ctx_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = setupStateWithTracingAndMdcInfo();

        // when
        BiConsumer result = AsyncNettyHelper.biConsumerWithTracingAndMdc(biConsumerMock, ctxMock);

        // then
        verifyBiConsumerWithTracingAndMdcSupport(result, biConsumerMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void biConsumerWithTracingAndMdc_pair_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        BiConsumer result = AsyncNettyHelper.biConsumerWithTracingAndMdc(biConsumerMock, setupInfo);

        // then
        verifyBiConsumerWithTracingAndMdcSupport(result, biConsumerMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void biConsumerWithTracingAndMdc_separate_args_works_as_expected() {
        // given
        Pair<Deque<Span>, Map<String, String>> setupInfo = generateTracingAndMdcInfo();

        // when
        BiConsumer result = AsyncNettyHelper.biConsumerWithTracingAndMdc(biConsumerMock,
                                                                     setupInfo.getLeft(), setupInfo.getRight());

        // then
        verifyBiConsumerWithTracingAndMdcSupport(result, biConsumerMock, setupInfo.getLeft(), setupInfo.getRight());
    }

    @Test
    public void extractTracingAndMdcInfoFromChannelHandlerContext_does_what_it_says_it_does() {
        // given
        Pair<Deque<Span>, Map<String, String>> expected = setupStateWithTracingAndMdcInfo();

        // when
        Pair<Deque<Span>, Map<String, String>> result =
            AsyncNettyHelper.extractTracingAndMdcInfoFromChannelHandlerContext(ctxMock);

        // then
        assertThat(result).isEqualTo(expected);
    }

    @Test
    public void extractTracingAndMdcInfoFromChannelHandlerContext_returns_null_if_passed_null() {
        // expect
        assertThat(AsyncNettyHelper.extractTracingAndMdcInfoFromChannelHandlerContext(null)).isNull();
    }

    @Test
    public void extractTracingAndMdcInfoFromChannelHandlerContext_returns_null_if_state_is_null() {
        // given
        doReturn(null).when(stateAttributeMock).get();

        // expect
        assertThat(AsyncNettyHelper.extractTracingAndMdcInfoFromChannelHandlerContext(ctxMock)).isNull();
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void linkTracingAndMdcToCurrentThread_ctx_works_as_expected(boolean useNullCtx) {
        // given
        Pair<Deque<Span>, Map<String, String>> stateInfo = setupStateWithTracingAndMdcInfo();
        ChannelHandlerContext ctxToUse = (useNullCtx) ? null : ctxMock;
        resetTracingAndMdc();
        Tracer.getInstance().startRequestWithRootSpan("foo-" + UUID.randomUUID().toString());
        Pair<Deque<Span>, Map<String, String>> expectedPreCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // when
        Pair<Deque<Span>, Map<String, String>> preCallInfo =
            AsyncNettyHelper.linkTracingAndMdcToCurrentThread(ctxToUse);
        Pair<Deque<Span>, Map<String, String>> postCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // then
        assertThat(preCallInfo).isEqualTo(expectedPreCallInfo);
        if (useNullCtx)
            assertThat(postCallInfo).isEqualTo(Pair.of(null, Collections.emptyMap()));
        else
            assertThat(postCallInfo).isEqualTo(stateInfo);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void linkTracingAndMdcToCurrentThread_pair_works_as_expected(boolean useNullPair) {
        // given
        Pair<Deque<Span>, Map<String, String>> infoForLinking = (useNullPair) ? null
                                                                               : setupStateWithTracingAndMdcInfo();
        resetTracingAndMdc();
        Tracer.getInstance().startRequestWithRootSpan("foo-" + UUID.randomUUID().toString());
        Pair<Deque<Span>, Map<String, String>> expectedPreCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // when
        Pair<Deque<Span>, Map<String, String>> preCallInfo =
            AsyncNettyHelper.linkTracingAndMdcToCurrentThread(infoForLinking);
        Pair<Deque<Span>, Map<String, String>> postCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // then
        assertThat(preCallInfo).isEqualTo(expectedPreCallInfo);
        if (useNullPair)
            assertThat(postCallInfo).isEqualTo(Pair.of(null, Collections.emptyMap()));
        else
            assertThat(postCallInfo).isEqualTo(infoForLinking);
    }

    @Test
    public void linkTracingAndMdcToCurrentThread_pair_works_as_expected_with_non_null_pair_and_null_innards() {
        // given
        Pair<Deque<Span>, Map<String, String>> infoForLinking = Pair.of(null, null);
        resetTracingAndMdc();
        Tracer.getInstance().startRequestWithRootSpan("foo-" + UUID.randomUUID().toString());
        Pair<Deque<Span>, Map<String, String>> expectedPreCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // when
        Pair<Deque<Span>, Map<String, String>> preCallInfo =
            AsyncNettyHelper.linkTracingAndMdcToCurrentThread(infoForLinking);
        Pair<Deque<Span>, Map<String, String>> postCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // then
        assertThat(preCallInfo).isEqualTo(expectedPreCallInfo);
        assertThat(postCallInfo).isEqualTo(Pair.of(null, Collections.emptyMap()));
    }

    @DataProvider(value = {
        "true   |   true",
        "false  |   true",
        "true   |   false",
        "false  |   false",
    }, splitBy = "\\|")
    @Test
    public void linkTracingAndMdcToCurrentThread_separate_args_works_as_expected(boolean useNullSpanStack,
                                                                                 boolean useNullMdcInfo) {
        // given
        Pair<Deque<Span>, Map<String, String>> info = setupStateWithTracingAndMdcInfo();
        info.getRight().put("fooMdcKey", UUID.randomUUID().toString());
        Deque<Span> spanStackForLinking = (useNullSpanStack) ? null : info.getLeft();
        Map<String, String> mdcInfoForLinking = (useNullMdcInfo) ? null : info.getRight();
        resetTracingAndMdc();
        Tracer.getInstance().startRequestWithRootSpan("foo-" + UUID.randomUUID().toString());
        Pair<Deque<Span>, Map<String, String>> expectedPreCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        Map<String, String> expectedMdcInfo;
        // The expected MDC info will vary depending on combinations.
        if (useNullMdcInfo) {
            // MDC may still be populated after the call if the span stack is not empty
            if (useNullSpanStack)
                expectedMdcInfo = Collections.emptyMap();
            else {
                // MDC will have been populated with tracing info.
                expectedMdcInfo = new HashMap<>();
                Span expectedSpan = spanStackForLinking.peek();
                expectedMdcInfo.put(SpanFieldForLoggerMdc.TRACE_ID.mdcKey, expectedSpan.getTraceId());
            }
        }
        else {
            // Not null MDC. Start with the MDC info for linking.
            expectedMdcInfo = new HashMap<>(mdcInfoForLinking);
            if (useNullSpanStack) {
                // In the case of a null span stack, the trace info would be removed from the MDC.
                expectedMdcInfo.remove(SpanFieldForLoggerMdc.TRACE_ID.mdcKey);
            }
        }

        // when
        Pair<Deque<Span>, Map<String, String>> preCallInfo =
            AsyncNettyHelper.linkTracingAndMdcToCurrentThread(spanStackForLinking, mdcInfoForLinking);
        Pair<Deque<Span>, Map<String, String>> postCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // then
        assertThat(preCallInfo).isEqualTo(expectedPreCallInfo);
        assertThat(postCallInfo.getLeft()).isEqualTo(spanStackForLinking);
        assertThat(postCallInfo.getRight()).isEqualTo(expectedMdcInfo);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void unlinkTracingAndMdcFromCurrentThread_pair_works_as_expected(boolean useNullPair) {
        // given
        Pair<Deque<Span>, Map<String, String>> infoForLinking = (useNullPair) ? null
                                                                               : setupStateWithTracingAndMdcInfo();
        // Setup the current thread with something that is not ultimately what we expect so that our assertions are
        //      verifying that the unlinkTracingAndMdcFromCurrentThread method actually did something.
        resetTracingAndMdc();
        Tracer.getInstance().startRequestWithRootSpan("foo-" + UUID.randomUUID().toString());

        // when
        AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread(infoForLinking);
        Pair<Deque<Span>, Map<String, String>> postCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // then
        if (useNullPair)
            assertThat(postCallInfo).isEqualTo(Pair.of(null, Collections.emptyMap()));
        else
            assertThat(postCallInfo).isEqualTo(infoForLinking);
    }

    @Test
    public void unlinkTracingAndMdcFromCurrentThread_pair_works_as_expected_with_non_null_pair_and_null_innards() {
        // given
        Pair<Deque<Span>, Map<String, String>> infoForLinking = Pair.of(null, null);
        // Setup the current thread with something that is not ultimately what we expect so that our assertions are
        //      verifying that the unlinkTracingAndMdcFromCurrentThread method actually did something.
        resetTracingAndMdc();
        Tracer.getInstance().startRequestWithRootSpan("foo-" + UUID.randomUUID().toString());

        // when
        AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread(infoForLinking);
        Pair<Deque<Span>, Map<String, String>> postCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // then
        assertThat(postCallInfo).isEqualTo(Pair.of(null, Collections.emptyMap()));
    }

    @DataProvider(value = {
        "true   |   true",
        "false  |   true",
        "true   |   false",
        "false  |   false",
    }, splitBy = "\\|")
    @Test
    public void unlinkTracingAndMdcFromCurrentThread_separate_args_works_as_expected(boolean useNullSpanStack,
                                                                                     boolean useNullMdcInfo) {
        // given
        Pair<Deque<Span>, Map<String, String>> info = setupStateWithTracingAndMdcInfo();
        info.getRight().put("fooMdcKey", UUID.randomUUID().toString());
        Deque<Span> spanStackForLinking = (useNullSpanStack) ? null : info.getLeft();
        Map<String, String> mdcInfoForLinking = (useNullMdcInfo) ? null : info.getRight();
        // Setup the current thread with something that is not ultimately what we expect so that our assertions are
        //      verifying that the unlinkTracingAndMdcFromCurrentThread method actually did something.
        resetTracingAndMdc();
        Tracer.getInstance().startRequestWithRootSpan("foo-" + UUID.randomUUID().toString());

        Map<String, String> expectedMdcInfo;
        // The expected MDC info will vary depending on combinations.
        if (useNullMdcInfo) {
            // MDC may still be populated after the call if the span stack is not empty
            if (useNullSpanStack)
                expectedMdcInfo = Collections.emptyMap();
            else {
                // MDC will have been populated with tracing info.
                expectedMdcInfo = new HashMap<>();
                Span expectedSpan = spanStackForLinking.peek();
                expectedMdcInfo.put(SpanFieldForLoggerMdc.TRACE_ID.mdcKey, expectedSpan.getTraceId());
            }
        }
        else {
            // Not null MDC. Since unlinkTracingAndMdcFromCurrentThread doesn't call registerWithThread when
            //      the span stack is null we don't need to worry about trace ID and span JSON being removed from MDC.
            //      Therefore it should match mdcInfoForLinking exactly.
            expectedMdcInfo = new HashMap<>(mdcInfoForLinking);
        }

        // when
        AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread(spanStackForLinking, mdcInfoForLinking);
        Pair<Deque<Span>, Map<String, String>> postCallInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // then
        assertThat(postCallInfo.getLeft()).isEqualTo(spanStackForLinking);
        assertThat(postCallInfo.getRight()).isEqualTo(expectedMdcInfo);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void executeOnlyIfChannelIsActive_executes_the_runnable_depending_on_whether_the_channel_is_active(
        boolean channelActive
    ) {
        // given
        doReturn(channelActive).when(channelMock).isActive();

        // when
        AsyncNettyHelper.executeOnlyIfChannelIsActive(ctxMock, "foo", runnableMock);

        // then
        if (channelActive)
            verify(runnableMock).run();
        else
            verifyNoInteractions(runnableMock);
    }

    @DataProvider(value = {
        "true   |   true    |   true    |   true",
        "false  |   true    |   true    |   true",
        "false  |   true    |   false   |   true",
        "false  |   false   |   true    |   true",
        "false  |   false   |   false   |   true",
        "false  |   false   |   false   |   false",
    }, splitBy = "\\|")
    @Test
    public void executeOnlyIfChannelIsActive_releases_request_resources_and_completes_trace_as_appropriate_when_channel_is_not_active(
        boolean stateIsNull, boolean requestInfoIsNull, boolean isTraceCompletedOrScheduledSetup, boolean proxyRouterStateIsNull
    ) {
        // given
        Pair<Deque<Span>, Map<String, String>> stateInfo = setupStateWithTracingAndMdcInfo();
        doReturn(false).when(channelMock).isActive();

        if (stateIsNull)
            doReturn(null).when(stateAttributeMock).get();

        if (requestInfoIsNull)
            state.setRequestInfo(null);

        if (proxyRouterStateIsNull)
            doReturn(null).when(proxyRouterStateAttrMock).get();

        state.setTraceCompletedOrScheduled(isTraceCompletedOrScheduledSetup);

        resetTracingAndMdc();
        Tracer.getInstance().startRequestWithRootSpan("foo-" + UUID.randomUUID().toString());
        Pair<Deque<Span>, Map<String, String>> preCallTracingInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        state.setDistributedTracingConfig(mock(DistributedTracingConfig.class));
        assertThat(state.isTracingResponseTaggingAndFinalSpanNameCompleted()).isFalse();

        // when
        AsyncNettyHelper.executeOnlyIfChannelIsActive(ctxMock, "foo", runnableMock);

        // then
        if (stateIsNull || requestInfoIsNull)
            verifyNoInteractions(requestInfoMock);
        else {
            verify(requestInfoMock).releaseAllResources();
            assertThat(currentSpanStackWhenRequestResourcesReleased.get(0)).isEqualTo(stateInfo.getLeft());
            assertThat(currentMdcInfoWhenRequestResourcesReleased.get(0)).isEqualTo(stateInfo.getRight());
        }

        if (proxyRouterStateIsNull)
            verifyNoInteractions(proxyRouterStateMock);
        else {
            verify(proxyRouterStateMock).cancelRequestStreaming(any(), any());
            verify(proxyRouterStateMock).cancelDownstreamRequest(any());
        }

        if (!stateIsNull && !isTraceCompletedOrScheduledSetup) {
            assertThat(state.isTraceCompletedOrScheduled()).isTrue();
            assertThat(state.isTracingResponseTaggingAndFinalSpanNameCompleted()).isTrue();
        }

        Pair<Deque<Span>, Map<String, String>> postCallTracingInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );
        assertThat(postCallTracingInfo).isEqualTo(preCallTracingInfo);
    }

    @Test
    public void executeOnlyIfChannelIsActive_sets_original_tracing_and_mdc_info_back_even_if_channel_not_active_and_exception_occurs() {
        // given
        setupStateWithTracingAndMdcInfo();
        doReturn(false).when(channelMock).isActive();
        RuntimeException exToThrow = new RuntimeException("kaboom");
        doThrow(exToThrow).when(requestInfoMock).releaseAllResources();

        resetTracingAndMdc();
        Tracer.getInstance().startRequestWithRootSpan("foo-" + UUID.randomUUID().toString());
        Pair<Deque<Span>, Map<String, String>> preCallTracingInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );

        // when
        Throwable ex = catchThrowable(() -> AsyncNettyHelper.executeOnlyIfChannelIsActive(ctxMock, "foo", runnableMock));

        // then
        verify(requestInfoMock).releaseAllResources();
        assertThat(ex).isSameAs(exToThrow);

        Pair<Deque<Span>, Map<String, String>> postCallTracingInfo = Pair.of(
            Tracer.getInstance().getCurrentSpanStackCopy(),
            MDC.getCopyOfContextMap()
        );
        assertThat(postCallTracingInfo).isEqualTo(preCallTracingInfo);
    }

    @Test
    public void executeAsyncCall_shouldReturnCompletableFuture() throws Exception {
        // given
        String expectedResult = UUID.randomUUID().toString();
        ctxMock = TestUtil.mockChannelHandlerContextWithTraceInfo().mockContext;
        Span parentSpan = Tracer.getInstance().getCurrentSpan();
        AtomicReference<Span> runningSpan = new AtomicReference<>();

        // when
        CompletableFuture<String> completableFuture = AsyncNettyHelper.supplyAsync(
                () -> {
                    runningSpan.set(Tracer.getInstance().getCurrentSpan());
                    return expectedResult;
                },
                executor, ctxMock);

        // then
        assertThat(completableFuture.isCompletedExceptionally()).isFalse();
        assertThat(completableFuture.get()).isEqualTo(expectedResult);
        // verify new span is not created, but existing span does successfully hop threads
        assertThat(runningSpan.get()).isEqualTo(parentSpan);
    }

    @Test
    public void executeAsyncCall_shouldReturnCompletableFutureUsingCircuitBreaker() throws Exception {
        // given
        String expectedResult = UUID.randomUUID().toString();
        ctxMock = TestUtil.mockChannelHandlerContextWithTraceInfo().mockContext;
        CircuitBreaker<String> circuitBreaker = spy(new CircuitBreakerImpl<>());
        Span parentSpan = Tracer.getInstance().getCurrentSpan();
        AtomicReference<Span> runningSpan = new AtomicReference<>();

        // when
        CompletableFuture<String> circuitBreakerCompletableFuture = AsyncNettyHelper.supplyAsync(
                () -> {
                    runningSpan.set(Tracer.getInstance().getCurrentSpan());
                    return expectedResult;
                },
                circuitBreaker, executor, ctxMock);

        // then
        verify(circuitBreaker).executeAsyncCall(any());
        assertThat(circuitBreakerCompletableFuture.isCompletedExceptionally()).isFalse();
        assertThat(circuitBreakerCompletableFuture.get()).isEqualTo(expectedResult);
        // verify new span is not created, but existing span does successfully hop threads
        assertThat(runningSpan.get()).isEqualTo(parentSpan);
    }

    @Test
    public void executeAsyncCall_shouldReturnCompletableFutureUsingSpanName() throws Exception {
        // given
        String expectedResult = UUID.randomUUID().toString();
        String expectedSpanName = "nonCircuitBreakerWithSpan";
        ctxMock = TestUtil.mockChannelHandlerContextWithTraceInfo().mockContext;
        Span parentSpan = Tracer.getInstance().getCurrentSpan();
        AtomicReference<Span> runningSpan = new AtomicReference<>();

        // when
        CompletableFuture<String> completableFuture = AsyncNettyHelper.supplyAsync(
                expectedSpanName,
                () -> {
                    runningSpan.set(Tracer.getInstance().getCurrentSpan());
                    return expectedResult;
                },
                executor, ctxMock);

        // then
        assertThat(completableFuture.isCompletedExceptionally()).isFalse();
        assertThat(completableFuture.get()).isEqualTo(expectedResult);
        // verify span is as expected
        assertThat(runningSpan.get().getParentSpanId()).isEqualTo(parentSpan.getSpanId());
        assertThat(runningSpan.get().getSpanName()).isEqualTo(expectedSpanName);
        assertThat(runningSpan.get().getTraceId()).isEqualTo(parentSpan.getTraceId());
    }

    @Test
    public void executeAsyncCall_shouldReturnCompletableFutureUsingCircuitBreakerWithSpanName() throws Exception {
        // given
        String expectedResult = UUID.randomUUID().toString();
        String expectedSpanName = "circuitBreakerWithSpan";
        ctxMock = TestUtil.mockChannelHandlerContextWithTraceInfo().mockContext;
        Span parentSpan = Tracer.getInstance().getCurrentSpan();
        CircuitBreaker<String> circuitBreaker = spy(new CircuitBreakerImpl<>());
        AtomicReference<Span> runningSpan = new AtomicReference<>();

        // when
        CompletableFuture<String> circuitBreakerFuture = AsyncNettyHelper.supplyAsync(
                expectedSpanName,
                () -> {
                    runningSpan.set(Tracer.getInstance().getCurrentSpan());
                    return expectedResult;
                },
                circuitBreaker, executor, ctxMock);

        // then
        assertThat(circuitBreakerFuture.isCompletedExceptionally()).isFalse();
        assertThat(circuitBreakerFuture.get()).isEqualTo(expectedResult);
        verify(circuitBreaker).executeAsyncCall(any());
        // verify span is as expected
        assertThat(runningSpan.get().getParentSpanId()).isEqualTo(parentSpan.getSpanId());
        assertThat(runningSpan.get().getSpanName()).isEqualTo(expectedSpanName);
        assertThat(runningSpan.get().getTraceId()).isEqualTo(parentSpan.getTraceId());
    }
}