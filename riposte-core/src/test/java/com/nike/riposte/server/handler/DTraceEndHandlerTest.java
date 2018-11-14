package com.nike.riposte.server.handler;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessage;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.util.AsyncNettyHelper;
import com.nike.riposte.util.asynchelperwrapper.ChannelFutureListenerWithTracingAndMdc;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;
import org.slf4j.MDC;

import java.util.Deque;
import java.util.Map;
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;
import io.netty.util.concurrent.GenericFutureListener;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link com.nike.riposte.server.handler.DTraceEndHandler}
 */
public class DTraceEndHandlerTest {

    private DTraceEndHandler handlerSpy;
    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;
    private ResponseInfo responseInfoMock;
    private ChannelFuture lastChunkChannelFutureMock;
    private Span currentSpanWhenCompleteCurrentSpanWasCalled;
    private Span currentSpanAfterCompleteCurrentSpanWasCalled;
    private DistributedTracingConfig<Span> distributedTracingConfigMock;

    private void resetTracingAndMdc() {
        MDC.clear();
        Tracer.getInstance().completeRequestSpan();
    }

    @Before
    public void beforeMethod() {
        handlerSpy = spy(new DTraceEndHandler());
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();
        resetTracingAndMdc();

        distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        state.setDistributedTracingConfig(distributedTracingConfigMock);

        responseInfoMock = mock(ResponseInfo.class);
        doReturn(true).when(responseInfoMock).isResponseSendingLastChunkSent();
        state.setResponseInfo(responseInfoMock, null);

        lastChunkChannelFutureMock = mock(ChannelFuture.class);
        state.setResponseWriterFinalChunkChannelFuture(lastChunkChannelFutureMock);

        doAnswer(invocation -> {
            currentSpanWhenCompleteCurrentSpanWasCalled = Tracer.getInstance().getCurrentSpan();
            invocation.callRealMethod();
            currentSpanAfterCompleteCurrentSpanWasCalled = Tracer.getInstance().getCurrentSpan();
            return null;
        }).when(handlerSpy).completeCurrentSpan();
    }

    @After
    public void afterMethod() {
        resetTracingAndMdc();
    }

    private Pair<Deque<Span>, Map<String, String>> setupStateWithNewSpan(String spanName) {
        Deque<Span> origSpanInfo = Tracer.getInstance().unregisterFromThread();
        Map<String, String> origMdcInfo = MDC.getCopyOfContextMap();

        Tracer.getInstance().startRequestWithRootSpan(spanName);

        Pair<Deque<Span>, Map<String, String>> infoForStatePair = AsyncNettyHelper.linkTracingAndMdcToCurrentThread(origSpanInfo, origMdcInfo);
        state.setDistributedTraceStack(infoForStatePair.getLeft());
        state.setLoggerMdcContextMap(infoForStatePair.getRight());

        return infoForStatePair;
    }

    private GenericFutureListener extractChannelFutureListenerAddedToLastChunkFuture() {
        ArgumentCaptor<GenericFutureListener> listenerArgumentCaptor = ArgumentCaptor.forClass(GenericFutureListener.class);
        verify(lastChunkChannelFutureMock).addListener(listenerArgumentCaptor.capture());
        return listenerArgumentCaptor.getValue();
    }

    @Test
    public void doChannelRead_calls_endDtrace_and_returns_CONTINUE_if_msg_is_LastOutboundMessage() throws Exception {
        // given
        LastOutboundMessage msg = mock(LastOutboundMessage.class);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy).endDtrace(ctxMock);
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void doChannelRead_does_nothing_and_returns_CONTINUE_if_msg_is_not_LastOutboundMessage() throws Exception {
        // given
        Object msg = new Object();

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy, times(0)).endDtrace(ctxMock);
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void doExceptionCaught_calls_endDtrace_and_returns_CONTINUE() throws Exception {
        // when
        PipelineContinuationBehavior result = handlerSpy.doExceptionCaught(ctxMock, null);

        // then
        verify(handlerSpy).endDtrace(ctxMock);
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void endDtrace_completes_the_trace_using_ChannelFutureListener_if_state_is_not_null_and_isResponseSendingLastChunkSent_returns_true()
        throws Exception {
        // given
        assertThat(state.isTraceCompletedOrScheduled(), is(false));
        assertThat(state.isResponseSendingLastChunkSent(), is(true));
        assertThat(state.getDistributedTraceStack(), nullValue());
        Pair<Deque<Span>, Map<String, String>> expectedDtraceInfo = setupStateWithNewSpan("blahTrace");
        assertThat(state.getDistributedTraceStack(), notNullValue());
        assertThat(state.getDistributedTraceStack(), is(expectedDtraceInfo.getLeft()));
        assertThat(state.getDistributedTraceStack().size(), is(1));
        assertThat(state.isTracingResponseTaggingAndFinalSpanNameCompleted(), is(false));
        Span expectedSpan = expectedDtraceInfo.getLeft().peek();

        // when
        handlerSpy.endDtrace(ctxMock);

        // then
        // completeCurrentSpan() not immediately called, but scheduled
        verify(handlerSpy, never()).completeCurrentSpan();
        assertThat(state.isTraceCompletedOrScheduled(), is(true));

        // Response tagging was done.
        assertThat(state.isTracingResponseTaggingAndFinalSpanNameCompleted(), is(true));

        // Extract the listener that was attached to the last chunk future.
        GenericFutureListener lastChunkListener = extractChannelFutureListenerAddedToLastChunkFuture();
        assertThat(lastChunkListener, notNullValue());
        assertThat(lastChunkListener, instanceOf(ChannelFutureListenerWithTracingAndMdc.class));
        assertThat(Whitebox.getInternalState(lastChunkListener, "distributedTraceStackForExecution"), is(expectedDtraceInfo.getLeft()));
        assertThat(Whitebox.getInternalState(lastChunkListener, "mdcContextMapForExecution"), is(expectedDtraceInfo.getRight()));
        Consumer<ChannelFuture> embeddedListenerConsumer =
            (Consumer<ChannelFuture>) Whitebox.getInternalState(lastChunkListener, "postCompleteOperation");

        // Execute the embedded listener so we can validate what it does. Note that we can't verify using mockito spy verify(),
        //      because the method call goes through the internal handler, not the spy impl. But we can still verify by
        //      setting up the Tracer state to what we expect, execute the embedded listener, and verify subsequent Tracer state.
        AsyncNettyHelper.linkTracingAndMdcToCurrentThread(expectedDtraceInfo);
        assertThat(Tracer.getInstance().getCurrentSpan(), is(expectedSpan));
        embeddedListenerConsumer.accept(null);
        assertThat(Tracer.getInstance().getCurrentSpan(), nullValue());
    }

    @Test
    public void endDtrace_completes_the_trace_immediately_if_state_is_not_null_but_isResponseSendingLastChunkSent_returns_false() {
        // given
        assertThat(state.isTraceCompletedOrScheduled(), is(false));
        state.setResponseWriterFinalChunkChannelFuture(null);
        assertThat(state.isResponseSendingLastChunkSent(), is(false));
        assertThat(state.getDistributedTraceStack(), nullValue());
        Pair<Deque<Span>, Map<String, String>> expectedDtraceInfo = setupStateWithNewSpan("blahTrace");
        assertThat(state.getDistributedTraceStack(), notNullValue());
        assertThat(state.getDistributedTraceStack(), is(expectedDtraceInfo.getLeft()));
        assertThat(state.getDistributedTraceStack().size(), is(1));
        assertThat(state.isTracingResponseTaggingAndFinalSpanNameCompleted(), is(false));
        Span expectedSpan = expectedDtraceInfo.getLeft().peek();

        // when
        handlerSpy.endDtrace(ctxMock);

        // then
        verify(handlerSpy).completeCurrentSpan();
        assertThat(currentSpanWhenCompleteCurrentSpanWasCalled, is(expectedSpan));
        assertThat(currentSpanAfterCompleteCurrentSpanWasCalled, nullValue());
        assertThat(state.isTraceCompletedOrScheduled(), is(true));
        assertThat(state.isTracingResponseTaggingAndFinalSpanNameCompleted(), is(true));
    }

    @Test
    public void endDtrace_attempts_to_complete_the_trace_even_if_state_is_null() {
        // given
        doReturn(null).when(stateAttributeMock).get();

        // when
        handlerSpy.endDtrace(ctxMock);

        // then
        verify(handlerSpy).completeCurrentSpan();
        assertThat(currentSpanWhenCompleteCurrentSpanWasCalled, nullValue());
        assertThat(currentSpanAfterCompleteCurrentSpanWasCalled, nullValue());
    }

    @Test
    public void endDtrace_does_nothing_if_state_isTraceCompletedOrScheduled_returns_true() {
        // given
        state.setTraceCompletedOrScheduled(true);

        // when
        handlerSpy.endDtrace(ctxMock);

        // then
        verify(handlerSpy, never()).completeCurrentSpan();
        verifyZeroInteractions(lastChunkChannelFutureMock);
    }
}