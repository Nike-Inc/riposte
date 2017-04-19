package com.nike.riposte.util.asynchelperwrapper;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link FunctionWithTracingAndMdcSupport}.
 *
 * @author Nic Munroe
 */
@Ignore
@RunWith(DataProviderRunner.class)
public class FunctionWithTracingAndMdcSupportTest {

    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;
    private Function functionMock;
    List<Deque<Span>> currentSpanStackWhenFunctionWasCalled;
    List<Map<String, String>> currentMdcInfoWhenFunctionWasCalled;
    boolean throwExceptionDuringCall;
    Object inObj;
    Object outObj;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();

        functionMock = mock(Function.class);

        inObj = new Object();
        outObj = new Object();
        throwExceptionDuringCall = false;
        currentSpanStackWhenFunctionWasCalled = new ArrayList<>();
        currentMdcInfoWhenFunctionWasCalled = new ArrayList<>();
        doAnswer(invocation -> {
            currentSpanStackWhenFunctionWasCalled.add(Tracer.getInstance().getCurrentSpanStackCopy());
            currentMdcInfoWhenFunctionWasCalled.add(MDC.getCopyOfContextMap());
            if (throwExceptionDuringCall)
                throw new RuntimeException("kaboom");
            return outObj;
        }).when(functionMock).apply(inObj);

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
    public void ctx_constructor_sets_fields_as_expected() {
        // given
        Deque<Span> spanStackMock = mock(Deque.class);
        Map<String, String> mdcInfoMock = mock(Map.class);
        state.setDistributedTraceStack(spanStackMock);
        state.setLoggerMdcContextMap(mdcInfoMock);

        // when
        FunctionWithTracingAndMdcSupport instance = new FunctionWithTracingAndMdcSupport(functionMock, ctxMock);

        // then
        assertThat(instance.origFunction).isSameAs(functionMock);
        assertThat(instance.distributedTraceStackForExecution).isEqualTo(spanStackMock);
        assertThat(instance.mdcContextMapForExecution).isEqualTo(mdcInfoMock);
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true",
        "false  |   false",
    }, splitBy = "\\|")
    @Test
    public void pair_constructor_sets_fields_as_expected(boolean nullSpanStack, boolean nullMdcInfo) {
        // given
        Deque<Span> spanStackMock = (nullSpanStack) ? null : mock(Deque.class);
        Map<String, String> mdcInfoMock = (nullMdcInfo) ? null : mock(Map.class);

        // when
        FunctionWithTracingAndMdcSupport instance = new FunctionWithTracingAndMdcSupport(
            functionMock, Pair.of(spanStackMock, mdcInfoMock)
        );

        // then
        assertThat(instance.origFunction).isSameAs(functionMock);
        assertThat(instance.distributedTraceStackForExecution).isEqualTo(spanStackMock);
        assertThat(instance.mdcContextMapForExecution).isEqualTo(mdcInfoMock);
    }

    @Test
    public void pair_constructor_sets_fields_as_expected_when_pair_is_null() {
        // when
        FunctionWithTracingAndMdcSupport instance = new FunctionWithTracingAndMdcSupport(functionMock, (Pair)null);

        // then
        assertThat(instance.origFunction).isSameAs(functionMock);
        assertThat(instance.distributedTraceStackForExecution).isNull();
        assertThat(instance.mdcContextMapForExecution).isNull();
    }

    @Test
    public void kitchen_sink_constructor_sets_fields_as_expected() {
        // given
        Deque<Span> spanStackMock = mock(Deque.class);
        Map<String, String> mdcInfoMock = mock(Map.class);

        // when
        FunctionWithTracingAndMdcSupport instance = new FunctionWithTracingAndMdcSupport(
            functionMock, spanStackMock, mdcInfoMock
        );

        // then
        assertThat(instance.origFunction).isSameAs(functionMock);
        assertThat(instance.distributedTraceStackForExecution).isEqualTo(spanStackMock);
        assertThat(instance.mdcContextMapForExecution).isEqualTo(mdcInfoMock);
    }

    @Test
    public void constructors_throw_exception_if_passed_null_function() {
        // given
        Deque<Span> spanStackMock = mock(Deque.class);
        Map<String, String> mdcInfoMock = mock(Map.class);

        // expect
        assertThat(catchThrowable(() -> new FunctionWithTracingAndMdcSupport(null, ctxMock)))
            .isInstanceOf(IllegalArgumentException.class);

        // and expect
        assertThat(catchThrowable(() -> new FunctionWithTracingAndMdcSupport(null, Pair.of(spanStackMock, mdcInfoMock))))
            .isInstanceOf(IllegalArgumentException.class);

        // and expect
        assertThat(catchThrowable(() -> new FunctionWithTracingAndMdcSupport(null, spanStackMock, mdcInfoMock)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void apply_handles_tracing_and_mdc_info_as_expected(boolean throwException) {
        // given
        throwExceptionDuringCall = throwException;
        Tracer.getInstance().startRequestWithRootSpan("foo");
        Deque<Span> spanStack = Tracer.getInstance().getCurrentSpanStackCopy();
        Map<String, String> mdcInfo = MDC.getCopyOfContextMap();
        FunctionWithTracingAndMdcSupport instance = new FunctionWithTracingAndMdcSupport(
            functionMock, spanStack, mdcInfo
        );
        resetTracingAndMdc();
        assertThat(Tracer.getInstance().getCurrentSpanStackCopy()).isNull();
        assertThat(MDC.getCopyOfContextMap()).isEmpty();

        // when
        Throwable ex = null;
        Object result = null;
        try {
            result = instance.apply(inObj);
        }
        catch(Throwable t) {
            ex = t;
        }

        // then
        verify(functionMock).apply(inObj);
        if (throwException) {
            assertThat(ex).isNotNull();
            assertThat(result).isNull();
        }
        else {
            assertThat(ex).isNull();
            assertThat(result).isSameAs(outObj);
        }

        assertThat(currentSpanStackWhenFunctionWasCalled.get(0)).isEqualTo(spanStack);
        assertThat(currentMdcInfoWhenFunctionWasCalled.get(0)).isEqualTo(mdcInfo);

        assertThat(Tracer.getInstance().getCurrentSpanStackCopy()).isNull();
        assertThat(MDC.getCopyOfContextMap()).isEmpty();
    }

}