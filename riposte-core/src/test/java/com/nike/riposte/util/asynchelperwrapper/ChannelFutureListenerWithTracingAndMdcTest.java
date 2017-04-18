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
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link ChannelFutureListenerWithTracingAndMdc}.
 *
 * @author Nic Munroe
 */
@Ignore
@RunWith(DataProviderRunner.class)
public class ChannelFutureListenerWithTracingAndMdcTest {

    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;
    private Consumer<ChannelFuture> consumerMock;
    List<Deque<Span>> currentSpanStackWhenChannelFutureWasCalled;
    List<Map<String, String>> currentMdcInfoWhenChannelFutureWasCalled;
    boolean throwExceptionDuringCall;
    ChannelFuture inObj;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();

        consumerMock = mock(Consumer.class);

        inObj = mock(ChannelFuture.class);
        throwExceptionDuringCall = false;
        currentSpanStackWhenChannelFutureWasCalled = new ArrayList<>();
        currentMdcInfoWhenChannelFutureWasCalled = new ArrayList<>();
        doAnswer(invocation -> {
            currentSpanStackWhenChannelFutureWasCalled.add(Tracer.getInstance().getCurrentSpanStackCopy());
            currentMdcInfoWhenChannelFutureWasCalled.add(MDC.getCopyOfContextMap());
            if (throwExceptionDuringCall)
                throw new RuntimeException("kaboom");
            return null;
        }).when(consumerMock).accept(inObj);

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
        ChannelFutureListenerWithTracingAndMdc instance = new ChannelFutureListenerWithTracingAndMdc(consumerMock, ctxMock);

        // then
        assertThat(instance.postCompleteOperation).isSameAs(consumerMock);
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
        ChannelFutureListenerWithTracingAndMdc instance = new ChannelFutureListenerWithTracingAndMdc(
            consumerMock, Pair.of(spanStackMock, mdcInfoMock)
        );

        // then
        assertThat(instance.postCompleteOperation).isSameAs(consumerMock);
        assertThat(instance.distributedTraceStackForExecution).isEqualTo(spanStackMock);
        assertThat(instance.mdcContextMapForExecution).isEqualTo(mdcInfoMock);
    }

    @Test
    public void pair_constructor_sets_fields_as_expected_when_pair_is_null() {
        // when
        ChannelFutureListenerWithTracingAndMdc instance = new ChannelFutureListenerWithTracingAndMdc(consumerMock, (Pair)null);

        // then
        assertThat(instance.postCompleteOperation).isSameAs(consumerMock);
        assertThat(instance.distributedTraceStackForExecution).isNull();
        assertThat(instance.mdcContextMapForExecution).isNull();
    }

    @Test
    public void kitchen_sink_constructor_sets_fields_as_expected() {
        // given
        Deque<Span> spanStackMock = mock(Deque.class);
        Map<String, String> mdcInfoMock = mock(Map.class);

        // when
        ChannelFutureListenerWithTracingAndMdc instance = new ChannelFutureListenerWithTracingAndMdc(
            consumerMock, spanStackMock, mdcInfoMock
        );

        // then
        assertThat(instance.postCompleteOperation).isSameAs(consumerMock);
        assertThat(instance.distributedTraceStackForExecution).isEqualTo(spanStackMock);
        assertThat(instance.mdcContextMapForExecution).isEqualTo(mdcInfoMock);
    }

    @Test
    public void constructors_throw_exception_if_passed_null_consumer() {
        // given
        Deque<Span> spanStackMock = mock(Deque.class);
        Map<String, String> mdcInfoMock = mock(Map.class);

        // expect
        assertThat(catchThrowable(() -> new ChannelFutureListenerWithTracingAndMdc(null, ctxMock)))
            .isInstanceOf(IllegalArgumentException.class);

        // and expect
        assertThat(catchThrowable(() -> new ChannelFutureListenerWithTracingAndMdc(null, Pair.of(spanStackMock, mdcInfoMock))))
            .isInstanceOf(IllegalArgumentException.class);

        // and expect
        assertThat(catchThrowable(() -> new ChannelFutureListenerWithTracingAndMdc(null, spanStackMock, mdcInfoMock)))
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
        ChannelFutureListenerWithTracingAndMdc instance = new ChannelFutureListenerWithTracingAndMdc(
            consumerMock, spanStack, mdcInfo
        );
        resetTracingAndMdc();
        assertThat(Tracer.getInstance().getCurrentSpanStackCopy()).isNull();
        assertThat(MDC.getCopyOfContextMap()).isEmpty();

        // when
        Throwable ex = catchThrowable(() -> instance.operationComplete(inObj));

        // then
        verify(consumerMock).accept(inObj);
        if (throwException) {
            assertThat(ex).isNotNull();
        }
        else {
            assertThat(ex).isNull();
        }

        assertThat(currentSpanStackWhenChannelFutureWasCalled.get(0)).isEqualTo(spanStack);
        assertThat(currentMdcInfoWhenChannelFutureWasCalled.get(0)).isEqualTo(mdcInfo);

        assertThat(Tracer.getInstance().getCurrentSpanStackCopy()).isNull();
        assertThat(MDC.getCopyOfContextMap()).isEmpty();
    }

}