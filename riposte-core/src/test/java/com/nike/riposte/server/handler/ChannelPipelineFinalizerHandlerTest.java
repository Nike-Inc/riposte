package com.nike.riposte.server.handler;

import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessage;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.metrics.ServerMetricsEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.Attribute;
import io.netty.util.concurrent.GenericFutureListener;

import static com.nike.riposte.server.channelpipeline.HttpChannelInitializer.IDLE_CHANNEL_TIMEOUT_HANDLER_NAME;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link com.nike.riposte.server.handler.ChannelPipelineFinalizerHandler}
 */
@RunWith(DataProviderRunner.class)
public class ChannelPipelineFinalizerHandlerTest {

    private ChannelPipelineFinalizerHandler handler;
    private ExceptionHandlingHandler exceptionHandlingHandlerMock;
    private ResponseSender responseSenderMock;
    private MetricsListener metricsListenerMock;
    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private ChannelPipeline pipelineMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private Attribute<ProxyRouterProcessingState> proxyRouterProcessingStateAttributeMock;
    private HttpProcessingState state;
    private RequestInfo<?> requestInfoMock;
    private ResponseInfo<?> responseInfoMock;
    private final long workerChannelIdleTimeoutMillis = 4242;

    @Before
    public void beforeMethod() {
        exceptionHandlingHandlerMock = mock(ExceptionHandlingHandler.class);
        responseSenderMock = mock(ResponseSender.class);
        metricsListenerMock = mock(MetricsListener.class);
        handler = new ChannelPipelineFinalizerHandler(exceptionHandlingHandlerMock, responseSenderMock, metricsListenerMock, workerChannelIdleTimeoutMillis);
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        pipelineMock = mock(ChannelPipeline.class);
        stateAttributeMock = mock(Attribute.class);
        proxyRouterProcessingStateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        responseInfoMock = mock(ResponseInfo.class);
        requestInfoMock = mock(RequestInfo.class);
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(pipelineMock).when(ctxMock).pipeline();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();
        doReturn(proxyRouterProcessingStateAttributeMock).when(channelMock).attr(ChannelAttributes.PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(requestInfoMock).when(exceptionHandlingHandlerMock).getRequestInfo(any(HttpProcessingState.class), any(Object.class));
        doReturn(true).when(responseInfoMock).isResponseSendingStarted();
        doReturn(true).when(responseInfoMock).isResponseSendingLastChunkSent();
        state.setResponseInfo(responseInfoMock);
        state.setRequestInfo(requestInfoMock);
    }

    @Test
    public void constructor_works_with_valid_args() {
        // given
        ChannelPipelineFinalizerHandler handler = new ChannelPipelineFinalizerHandler(mock(ExceptionHandlingHandler.class), mock(ResponseSender.class), null,
                                                                                      workerChannelIdleTimeoutMillis);

        // expect
        assertThat(handler, notNullValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_exceptionHandlingHandler_is_null() {
        // expect
        new ChannelPipelineFinalizerHandler(null, mock(ResponseSender.class), null, workerChannelIdleTimeoutMillis);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_responseSender_is_null() {
        // expect
        new ChannelPipelineFinalizerHandler(mock(ExceptionHandlingHandler.class), null, null, workerChannelIdleTimeoutMillis);
    }

    @Test
    public void doChannelRead_gets_state_from_getStateAndCreateIfNeeded_method_and_then_calls_finalizeChannelPipeline_and_then_returns_DO_NOT_FIRE_CONTINUE_EVENT_if_msg_is_LastOutboundMessage() throws Exception {
        // given
        ChannelPipelineFinalizerHandler handlerSpy = spy(handler);
        LastOutboundMessage msg = mock(LastOutboundMessage.class);
        state.setResponseWriterFinalChunkChannelFuture(mock(ChannelFuture.class));

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy).finalizeChannelPipeline(eq(ctxMock), eq(msg), eq(state), any(Throwable.class));
        assertThat(result, is(PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT));
    }

    @Test
    public void doChannelRead_do_nothing_and_return_DO_NOT_FIRE_CONTINUE_EVENT_if_msg_is_not_LastOutboundMessage() throws Exception {
        // given
        ChannelPipelineFinalizerHandler handlerSpy = spy(handler);
        Object msg = new Object();

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy, times(0)).finalizeChannelPipeline(eq(ctxMock), eq(msg), eq(state), any(Throwable.class));
        assertThat(result, is(PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT));
    }

    @Test
    public void doExceptionCaught_gets_state_from_getStateAndCreateIfNeeded_method_and_then_calls_finalizeChannelPipeline_and_then_returns_DO_NOT_FIRE_CONTINUE_EVENT() throws Exception {
        // given
        ChannelPipelineFinalizerHandler handlerSpy = spy(handler);
        Exception cause = new Exception("intentional test exception");
        state.setResponseWriterFinalChunkChannelFuture(mock(ChannelFuture.class));

        // when
        PipelineContinuationBehavior result = handlerSpy.doExceptionCaught(ctxMock, cause);

        // then
        verify(handlerSpy).getStateAndCreateIfNeeded(ctxMock, cause);
        verify(handlerSpy).finalizeChannelPipeline(ctxMock, null, state, cause);
        assertThat(result, is(PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT));
    }

    @Test
    public void getStateAndCreateIfNeeded_uses_state_from_ctx_if_available() {
        // expect
        assertThat(handler.getStateAndCreateIfNeeded(ctxMock, null), is(state));
    }

    @Test
    public void getStateAndCreateIfNeeded_creates_new_state_if_ctx_state_is_null() {
        // given
        doReturn(null).when(stateAttributeMock).get();

        // when
        HttpProcessingState result = handler.getStateAndCreateIfNeeded(ctxMock, null);

        // then
        assertThat(result, notNullValue());
        assertThat(result, not(state));
        verify(stateAttributeMock).set(result);
    }

    @Test
    public void finalizeChannelPipeline_should_send_event_to_metricsListener_for_successful_response_and_flush_context() throws Exception {
        // given
        ChannelFuture responseWriterChannelFuture = mock(ChannelFuture.class);
        state.setResponseWriterFinalChunkChannelFuture(responseWriterChannelFuture);
        HttpProcessingState stateSpy = spy(state);
        doReturn(stateSpy).when(stateAttributeMock).get();
        ChannelFuture responseWriteFutureResult = mock(ChannelFuture.class);
        doReturn(true).when(responseWriteFutureResult).isSuccess();

        // when
        handler.finalizeChannelPipeline(ctxMock, null, stateSpy, null);

        // then
        ArgumentCaptor<GenericFutureListener> channelFutureListenerArgumentCaptor = ArgumentCaptor.forClass(GenericFutureListener.class);
        verify(responseWriterChannelFuture).addListener(channelFutureListenerArgumentCaptor.capture());
        GenericFutureListener futureListener = channelFutureListenerArgumentCaptor.getValue();
        assertThat(futureListener, notNullValue());
        futureListener.operationComplete(responseWriteFutureResult);

        verify(metricsListenerMock).onEvent(eq(ServerMetricsEvent.RESPONSE_SENT), any(HttpProcessingState.class));
        verify(ctxMock).flush();
    }

    @Test
    public void finalizeChannelPipeline_should_send_event_to_metricsListener_for_failure_response_and_flush_context() throws Exception {
        // given
        ChannelFuture responseWriterChannelFuture = mock(ChannelFuture.class);
        state.setResponseWriterFinalChunkChannelFuture(responseWriterChannelFuture);
        HttpProcessingState stateSpy = spy(state);
        doReturn(stateSpy).when(stateAttributeMock).get();
        ChannelFuture responseWriteFutureResult = mock(ChannelFuture.class);
        doReturn(false).when(responseWriteFutureResult).isSuccess();

        // when
        handler.finalizeChannelPipeline(ctxMock, null, stateSpy, null);

        // then
        ArgumentCaptor<GenericFutureListener> channelFutureListenerArgumentCaptor = ArgumentCaptor.forClass(GenericFutureListener.class);
        verify(responseWriterChannelFuture).addListener(channelFutureListenerArgumentCaptor.capture());
        GenericFutureListener futureListener = channelFutureListenerArgumentCaptor.getValue();
        assertThat(futureListener, notNullValue());
        futureListener.operationComplete(responseWriteFutureResult);

        verify(metricsListenerMock).onEvent(ServerMetricsEvent.RESPONSE_WRITE_FAILED, null);
        verify(ctxMock).flush();
    }

    @Test
    public void finalizeChannelPipeline_should_send_error_response_if_state_indicates_no_response_already_sent() throws JsonProcessingException {
        // given
        state.setResponseWriterFinalChunkChannelFuture(null);
        HttpProcessingState stateSpy = spy(state);
        doReturn(stateSpy).when(stateAttributeMock).get();
        doReturn(false).when(responseInfoMock).isResponseSendingStarted();
        Object msg = new Object();
        Throwable cause = new Exception("intentional test exception");
        RequestInfo<?> requestInfoMock = mock(RequestInfo.class);
        ResponseInfo<ErrorResponseBody> errorResponseMock = mock(ResponseInfo.class);

        doReturn(requestInfoMock).when(exceptionHandlingHandlerMock).getRequestInfo(stateSpy, msg);
        doReturn(errorResponseMock).when(exceptionHandlingHandlerMock).processUnhandledError(eq(stateSpy), eq(msg), any(Throwable.class));

        // when
        handler.finalizeChannelPipeline(ctxMock, msg, stateSpy, cause);

        // then
        verify(responseSenderMock).sendErrorResponse(ctxMock, requestInfoMock, errorResponseMock);
        verify(metricsListenerMock).onEvent(ServerMetricsEvent.RESPONSE_SENT, stateSpy);
        verify(ctxMock).flush();
    }

    @Test
    public void finalizeChannelPipeline_should_add_idle_channel_timeout_handler_first_in_pipeline_if_workerChannelIdleTimeoutMillis_is_greater_than_0()
        throws JsonProcessingException {
        // given
        LastOutboundMessage msg = mock(LastOutboundMessage.class);

        // when
        handler.finalizeChannelPipeline(ctxMock, msg, state, null);

        // then
        ArgumentCaptor<ChannelHandler> idleHandlerArgCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(pipelineMock).addFirst(eq(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME), idleHandlerArgCaptor.capture());
        ChannelHandler handlerRegistered = idleHandlerArgCaptor.getValue();
        assertThat(handlerRegistered, instanceOf(IdleChannelTimeoutHandler.class));
        IdleChannelTimeoutHandler idleHandler = (IdleChannelTimeoutHandler)handlerRegistered;
        long idleValue = (long) Whitebox.getInternalState(idleHandler, "idleTimeoutMillis");
        assertThat(idleValue, is(workerChannelIdleTimeoutMillis));
    }

    @DataProvider(value = {
        "0",
        "-42"
    }, splitBy = "\\|")
    @Test
    public void finalizeChannelPipeline_does_not_add_idle_channel_timeout_handler_to_pipeline_if_workerChannelIdleTimeoutMillis_is_not_greater_than_0(long timeoutVal)
        throws JsonProcessingException {
        // given
        Whitebox.setInternalState(handler, "workerChannelIdleTimeoutMillis", timeoutVal);
        LastOutboundMessage msg = mock(LastOutboundMessage.class);

        // when
        handler.finalizeChannelPipeline(ctxMock, msg, state, null);

        // then
        verify(pipelineMock, never()).addFirst(anyString(), anyObject());
    }
}