package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessageSendFullResponseInfo;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.NonblockingEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.http.impl.RequestInfoImpl;
import com.nike.riposte.util.asynchelperwrapper.BiConsumerWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.RunnableWithTracingAndMdcSupport;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;
import io.netty.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link NonblockingEndpointExecutionHandler}
 *
 * @author Nic Munroe
 */
public class NonblockingEndpointExecutionHandlerTest {

    private NonblockingEndpointExecutionHandler handlerSpy;
    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private EventLoop eventLoopMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private RequestInfo requestInfo;
    private NonblockingEndpoint<?, ?> endpointMock;
    private Executor longRunningTaskExecutorMock;
    private long defaultCompletableFutureTimeoutMillis = 4242;
    private CompletableFuture<ResponseInfo<?>> responseFuture;
    private CompletableFuture<Void> stateWorkChainFutureSpy;
    private CompletableFuture futureThatWillBeAttachedToSpy;
    private LastHttpContent msg = mock(LastHttpContent.class);

    @Before
    public void beforeMethod() {
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        stateAttrMock = mock(Attribute.class);
        requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        endpointMock = mock(StandardEndpoint.class);
        longRunningTaskExecutorMock = mock(Executor.class);
        responseFuture = new CompletableFuture<>();
        stateWorkChainFutureSpy = spy(CompletableFuture.completedFuture(null));
        eventLoopMock = mock(EventLoop.class);

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(endpointMock).when(stateMock).getEndpointForExecution();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        doReturn(responseFuture).when(endpointMock).execute(any(RequestInfo.class), any(Executor.class), any(ChannelHandlerContext.class));
        doReturn(eventLoopMock).when(channelMock).eventLoop();
        doReturn(true).when(channelMock).isActive();
        doAnswer(invocation -> {
            CompletableFuture actualFutureForAttaching = (CompletableFuture) invocation.callRealMethod();
            futureThatWillBeAttachedToSpy = spy(actualFutureForAttaching);
            return futureThatWillBeAttachedToSpy;
        }).when(stateWorkChainFutureSpy).thenCompose(any(Function.class));
        doReturn(stateWorkChainFutureSpy).when(stateMock).getPreEndpointExecutionWorkChain();

        handlerSpy = spy(new NonblockingEndpointExecutionHandler(longRunningTaskExecutorMock, defaultCompletableFutureTimeoutMillis));
    }

    @Test
    public void constructor_sets_variables_based_on_args_passed_in() {
        // when
        NonblockingEndpointExecutionHandler
            theHandler = new NonblockingEndpointExecutionHandler(longRunningTaskExecutorMock, defaultCompletableFutureTimeoutMillis);

        // then
        Executor actualExecutor = (Executor) Whitebox.getInternalState(theHandler, "longRunningTaskExecutor");
        long actualTimeoutValue = (long) Whitebox.getInternalState(theHandler, "defaultCompletableFutureTimeoutMillis");
        assertThat(actualExecutor).isEqualTo(longRunningTaskExecutorMock);
        assertThat(actualTimeoutValue).isEqualTo(defaultCompletableFutureTimeoutMillis);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_explodes_if_null_executor_passed_in() {
        // expect
        new NonblockingEndpointExecutionHandler(null, defaultCompletableFutureTimeoutMillis);
    }

    @Test
    public void doChannelRead_executes_endpoint_and_attaches_completion_logic_and_schedules_timeout_for_result_and_returns_DO_NOT_FIRE_CONTINUE_EVENT_if_endpoint_is_NonblockingEndpoint() throws Exception {
        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(endpointMock).execute(requestInfo, longRunningTaskExecutorMock, ctxMock);
        // The 2nd whenComplete is for cancelling the timeout check if the response finishes before the timeout
        verify(futureThatWillBeAttachedToSpy, times(2)).whenComplete(any(BiConsumerWithTracingAndMdcSupport.class));
        verify(eventLoopMock).schedule(any(RunnableWithTracingAndMdcSupport.class), any(Long.class), eq(TimeUnit.MILLISECONDS));
        assertThat(result).isEqualTo(PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT);
    }

    @Test
    public void doChannelRead_does_nothing_and_returns_CONTINUE_if_endpoint_is_not_NonblockingEndpoint() throws Exception {
        // given
        Endpoint<?> endpointToUse = mock(Endpoint.class);
        doReturn(endpointToUse).when(stateMock).getEndpointForExecution();

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verifyNoMoreInteractions(endpointMock);
        assertThat(futureThatWillBeAttachedToSpy).isNull();
        verifyNoMoreInteractions(eventLoopMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_and_returns_CONTINUE_if_endpoint_is_null() throws Exception {
        // given
        doReturn(null).when(stateMock).getEndpointForExecution();

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verifyNoMoreInteractions(endpointMock);
        assertThat(futureThatWillBeAttachedToSpy).isNull();
        verifyNoMoreInteractions(eventLoopMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_and_returns_CONTINUE_if_msg_is_not_HttpObject() throws Exception {
        // given
        Object badMsg = new Object();

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, badMsg);

        // then
        verifyZeroInteractions(endpointMock, eventLoopMock);
        assertThat(futureThatWillBeAttachedToSpy).isNull();
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_schedules_timeout_using_global_default_if_endpoint_does_not_specify_one() throws Exception {
        // given
        doReturn(null).when(endpointMock).completableFutureTimeoutOverrideMillis();

        // when
        handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(eventLoopMock).schedule(any(RunnableWithTracingAndMdcSupport.class), eq(defaultCompletableFutureTimeoutMillis), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    public void doChannelRead_schedules_timeout_using_endpoint_value_if_endpoint_specifies_one() throws Exception {
        // given
        Long endpointValue = defaultCompletableFutureTimeoutMillis + 1;
        doReturn(endpointValue).when(endpointMock).completableFutureTimeoutOverrideMillis();

        // when
        handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(eventLoopMock).schedule(any(RunnableWithTracingAndMdcSupport.class), eq(endpointValue), eq(TimeUnit.MILLISECONDS));
    }

    private BiConsumer<ResponseInfo<?>, Throwable> extractContinuationLogic() throws Exception {
        handlerSpy.doChannelRead(ctxMock, msg);
        ArgumentCaptor<BiConsumer> completionLogicArgumentCaptor = ArgumentCaptor.forClass(BiConsumer.class);
        // The 2nd whenComplete is for cancelling the timeout check if the response finishes before the timeout
        verify(futureThatWillBeAttachedToSpy, times(2)).whenComplete(completionLogicArgumentCaptor.capture());
        // The first whenComplete is the continuation logic, so that's what we'll grab
        return completionLogicArgumentCaptor.getAllValues().get(0);
    }

    @Test
    public void doChannelRead_completion_logic_calls_asyncCallback_on_success() throws Exception {
        // given
        BiConsumer<ResponseInfo<?>, Throwable> continuationLogic = extractContinuationLogic();
        ResponseInfo<?> responseInfo = ResponseInfo.newBuilder().build();

        // when
        continuationLogic.accept(responseInfo, null);

        // then
        verify(handlerSpy).asyncCallback(ctxMock, responseInfo);
        verify(handlerSpy, times(0)).asyncErrorCallback(any(ChannelHandlerContext.class), any(Throwable.class));
    }

    @Test
    public void doChannelRead_completion_logic_calls_asyncErrorCallback_on_failure() throws Exception {
        // given
        BiConsumer<ResponseInfo<?>, Throwable> continuationLogic = extractContinuationLogic();
        Throwable cause = new Exception("intentional test exception");

        // when
        continuationLogic.accept(null, cause);

        // then
        verify(handlerSpy).asyncErrorCallback(ctxMock, cause);
        verify(handlerSpy, times(0)).asyncCallback(any(ChannelHandlerContext.class), any(ResponseInfo.class));
    }

    @Test
    public void doChannelRead_cancels_timeout_check_if_response_finishes_before_timeout_check_occurs() throws Exception {
        // given
        ScheduledFuture timeoutCheckMock = mock(ScheduledFuture.class);
        doReturn(timeoutCheckMock).when(eventLoopMock).schedule(any(Runnable.class), any(Long.class), any(TimeUnit.class));
        handlerSpy.doChannelRead(ctxMock, msg);
        ArgumentCaptor<BiConsumer> timeoutCheckCancellationLogicArgumentCaptor = ArgumentCaptor.forClass(BiConsumer.class);
        // The 2nd whenComplete is for cancelling the timeout check if the response finishes before the timeout
        verify(futureThatWillBeAttachedToSpy, times(2)).whenComplete(timeoutCheckCancellationLogicArgumentCaptor.capture());
        BiConsumer<ResponseInfo<?>, Throwable> timeoutCheckCancellationLogic = timeoutCheckCancellationLogicArgumentCaptor.getAllValues().get(1);

        // when: the timeout check scheduled future is not yet complete when the response finishes
        doReturn(false).when(timeoutCheckMock).isDone();
        timeoutCheckCancellationLogic.accept(mock(ResponseInfo.class), null);

        // then: timeout check scheduled future should be cancelled
        verify(timeoutCheckMock).cancel(false);
    }

    @Test
    public void doChannelRead_does_nothing_to_timeout_check_if_timeout_check_is_already_completed_when_response_completes() throws Exception {
        // given
        ScheduledFuture timeoutCheckMock = mock(ScheduledFuture.class);
        doReturn(timeoutCheckMock).when(eventLoopMock).schedule(any(Runnable.class), any(Long.class), any(TimeUnit.class));
        handlerSpy.doChannelRead(ctxMock, msg);
        ArgumentCaptor<BiConsumer> timeoutCheckCancellationLogicArgumentCaptor = ArgumentCaptor.forClass(BiConsumer.class);
        // The 2nd whenComplete is for cancelling the timeout check if the response finishes before the timeout
        verify(futureThatWillBeAttachedToSpy, times(2)).whenComplete(timeoutCheckCancellationLogicArgumentCaptor.capture());
        BiConsumer<ResponseInfo<?>, Throwable> timeoutCheckCancellationLogic = timeoutCheckCancellationLogicArgumentCaptor.getAllValues().get(1);

        // when: the timeout check scheduled future is already done
        doReturn(true).when(timeoutCheckMock).isDone();
        timeoutCheckCancellationLogic.accept(mock(ResponseInfo.class), null);

        // then: nothing should be done
        verify(timeoutCheckMock).isDone();
        verify(timeoutCheckMock, times(0)).cancel(any(Boolean.class));
        verifyNoMoreInteractions(timeoutCheckMock);
    }

    private Runnable extractTimeoutRunnable() throws Exception {
        handlerSpy.doChannelRead(ctxMock, msg);
        ArgumentCaptor<Runnable> timeoutRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(eventLoopMock).schedule(timeoutRunnableCaptor.capture(), any(Long.class), any(TimeUnit.class));
        return timeoutRunnableCaptor.getValue();
    }

    @Test
    public void doChannelRead_timeout_runnable_gets_timeout_cause_from_endpoint_and_completes_the_future_with_it() throws Exception {
        // given
        Runnable timeoutRunnable = extractTimeoutRunnable();
        Throwable timeoutCause = new Exception("intentional test exception");
        doReturn(timeoutCause).when(endpointMock).getCustomTimeoutExceptionCause(any(RequestInfo.class), any(ChannelHandlerContext.class));
        doReturn(false).when(futureThatWillBeAttachedToSpy).isDone();

        // when
        timeoutRunnable.run();

        // then
        verify(futureThatWillBeAttachedToSpy).completeExceptionally(timeoutCause);
    }

    @Test
    public void doChannelRead_timeout_runnable_creates_timeout_cause_if_endpoint_does_not_have_one() throws Exception {
        // given
        Runnable timeoutRunnable = extractTimeoutRunnable();
        doReturn(null).when(endpointMock).getCustomTimeoutExceptionCause(any(RequestInfo.class), any(ChannelHandlerContext.class));
        doReturn(false).when(futureThatWillBeAttachedToSpy).isDone();

        // when
        timeoutRunnable.run();

        // then
        ArgumentCaptor<Throwable> throwableArgumentCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(futureThatWillBeAttachedToSpy).completeExceptionally(throwableArgumentCaptor.capture());
        assertThat(throwableArgumentCaptor.getValue()).isNotNull();
    }

    @Test
    public void doChannelRead_timeout_runnable_does_nothing_if_future_is_already_completed() throws Exception {
        // given
        Runnable timeoutRunnable = extractTimeoutRunnable();
        doReturn(true).when(futureThatWillBeAttachedToSpy).isDone();

        // when
        timeoutRunnable.run();

        // then
        verify(futureThatWillBeAttachedToSpy, times(0)).completeExceptionally(any(Throwable.class));
    }

    @Test
    public void asyncCallback_sets_responseInfo_on_state_and_fires_channelRead_event_on_ctx() {
        // given
        ResponseInfo<?> responseInfo = ResponseInfo.newBuilder().build();

        // when
        handlerSpy.asyncCallback(ctxMock, responseInfo);

        // then
        verify(stateMock).setResponseInfo(responseInfo);
        verify(ctxMock).fireChannelRead(LastOutboundMessageSendFullResponseInfo.INSTANCE);
    }

    @Test
    public void asyncCallback_sets_responseInfo_on_state_but_does_not_fire_channelRead_event_on_ctx_if_channel_is_inactive() {
        // given
        ResponseInfo<?> responseInfo = ResponseInfo.newBuilder().build();
        doReturn(false).when(channelMock).isActive();

        // when
        handlerSpy.asyncCallback(ctxMock, responseInfo);

        // then
        verify(stateMock).setResponseInfo(responseInfo);
        verify(ctxMock, times(0)).fireChannelRead(LastOutboundMessageSendFullResponseInfo.INSTANCE);
    }

    @Test
    public void asyncErrorCallback_fires_exceptionCaught_event_on_ctx() {
        // given
        Throwable cause = new Exception("intentional test exception");

        // when
        handlerSpy.asyncErrorCallback(ctxMock, cause);

        // then
        verify(ctxMock).fireExceptionCaught(cause);
    }

    @Test
    public void asyncErrorCallback_does_not_fire_exceptionCaught_event_on_ctx_if_channel_is_inactive() {
        // given
        Throwable cause = new Exception("intentional test exception");
        doReturn(false).when(channelMock).isActive();

        // when
        handlerSpy.asyncErrorCallback(ctxMock, cause);

        // then
        verify(ctxMock, times(0)).fireExceptionCaught(any(Throwable.class));
    }

}