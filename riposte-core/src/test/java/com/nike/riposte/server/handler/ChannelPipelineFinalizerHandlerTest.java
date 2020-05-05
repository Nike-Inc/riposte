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
import com.nike.riposte.server.logging.AccessLogger;
import com.nike.riposte.server.metrics.ServerMetricsEvent;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import com.nike.riposte.testutils.Whitebox;
import org.slf4j.Logger;

import java.util.Deque;
import java.util.LinkedList;
import java.util.UUID;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.Attribute;
import io.netty.util.concurrent.GenericFutureListener;

import static com.nike.riposte.server.channelpipeline.HttpChannelInitializer.IDLE_CHANNEL_TIMEOUT_HANDLER_NAME;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests the functionality of {@link com.nike.riposte.server.handler.ChannelPipelineFinalizerHandler}
 */
@RunWith(DataProviderRunner.class)
public class ChannelPipelineFinalizerHandlerTest {

    private ChannelPipelineFinalizerHandler handler;
    private ExceptionHandlingHandler exceptionHandlingHandlerMock;
    private ResponseSender responseSenderMock;
    private MetricsListener metricsListenerMock;
    private AccessLogger accessLoggerMock;
    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private ChannelPipeline pipelineMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private Attribute<ProxyRouterProcessingState> proxyRouterProcessingStateAttributeMock;
    private HttpProcessingState state;
    private ProxyRouterProcessingState proxyRouterStateMock;
    private RequestInfo<?> requestInfoMock;
    private ResponseInfo<?> responseInfoMock;
    private final long workerChannelIdleTimeoutMillis = 4242;

    @Before
    public void beforeMethod() {
        exceptionHandlingHandlerMock = mock(ExceptionHandlingHandler.class);
        responseSenderMock = mock(ResponseSender.class);
        metricsListenerMock = mock(MetricsListener.class);
        accessLoggerMock = mock(AccessLogger.class);
        handler = new ChannelPipelineFinalizerHandler(exceptionHandlingHandlerMock, responseSenderMock, metricsListenerMock,
                                                      accessLoggerMock, workerChannelIdleTimeoutMillis);
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        pipelineMock = mock(ChannelPipeline.class);
        stateAttributeMock = mock(Attribute.class);
        proxyRouterProcessingStateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        proxyRouterStateMock = mock(ProxyRouterProcessingState.class);
        responseInfoMock = mock(ResponseInfo.class);
        requestInfoMock = mock(RequestInfo.class);
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(pipelineMock).when(ctxMock).pipeline();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();
        doReturn(proxyRouterStateMock).when(proxyRouterProcessingStateAttributeMock).get();
        doReturn(proxyRouterProcessingStateAttributeMock).when(channelMock).attr(ChannelAttributes.PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(requestInfoMock).when(exceptionHandlingHandlerMock).getRequestInfo(any(), any());
        doReturn(true).when(responseInfoMock).isResponseSendingStarted();
        doReturn(true).when(responseInfoMock).isResponseSendingLastChunkSent();
        state.setResponseInfo(responseInfoMock, null);
        state.setRequestInfo(requestInfoMock);

        if (Tracer.getInstance().getCurrentSpan() != null)
            Tracer.getInstance().completeRequestSpan();
    }

    @Test
    public void constructor_works_with_valid_args() {
        // given
        ChannelPipelineFinalizerHandler handler = new ChannelPipelineFinalizerHandler(
            mock(ExceptionHandlingHandler.class), mock(ResponseSender.class), null, null,
            workerChannelIdleTimeoutMillis
        );

        // expect
        assertThat(handler, notNullValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_exceptionHandlingHandler_is_null() {
        // expect
        new ChannelPipelineFinalizerHandler(null, mock(ResponseSender.class), null, null,
                                            workerChannelIdleTimeoutMillis);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_responseSender_is_null() {
        // expect
        new ChannelPipelineFinalizerHandler(mock(ExceptionHandlingHandler.class), null, null, null,
                                            workerChannelIdleTimeoutMillis);
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
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isFalse();

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
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isTrue();
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
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isFalse();

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
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isTrue();
    }

    @DataProvider(value = {
        "true   |   false",
        "false  |   true"
    }, splitBy = "\\|")
    @Test
    public void finalizeChannelPipeline_should_not_call_metricsListener_if_metricsListener_is_null_or_metrics_already_scheduled(
        boolean metricsListenerIsNull, boolean metricsAlreadyScheduled
    ) throws Exception {
        // given
        if (metricsListenerIsNull)
            Whitebox.setInternalState(handler, "metricsListener", null);

        state.setRequestMetricsRecordedOrScheduled(metricsAlreadyScheduled);

        // when
        handler.finalizeChannelPipeline(ctxMock, null, state, null);

        // then
        verifyNoInteractions(metricsListenerMock);
        Assertions.assertThat(state.isRequestMetricsRecordedOrScheduled()).isEqualTo(metricsAlreadyScheduled);
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
        verify(metricsListenerMock).onEvent(ServerMetricsEvent.RESPONSE_WRITE_FAILED, stateSpy);
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
        "0      |   false",
        "-42    |   false",
        "42     |   true"
    }, splitBy = "\\|")
    @Test
    public void finalizeChannelPipeline_does_not_add_idle_channel_timeout_handler_to_pipeline_if_workerChannelIdleTimeoutMillis_is_not_greater_than_0_or_idle_timeout_handler_already_exists(
        long timeoutVal, boolean idleTimeoutHandlerAlreadyExists
    ) throws JsonProcessingException {
        // given
        Whitebox.setInternalState(handler, "workerChannelIdleTimeoutMillis", timeoutVal);
        LastOutboundMessage msg = mock(LastOutboundMessage.class);
        if (idleTimeoutHandlerAlreadyExists)
            doReturn(mock(ChannelHandler.class)).when(pipelineMock).get(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);

        // when
        handler.finalizeChannelPipeline(ctxMock, msg, state, null);

        // then
        verify(pipelineMock, never()).addFirst(anyString(), any());
    }

    @DataProvider(value = {
        "false  |   true    |   false   |   true",
        "true   |   true    |   false   |   false",
        "false  |   false   |   false   |   false",
        "false  |   true    |   true    |   false",
    }, splitBy = "\\|")
    @Test
    public void finalizeChannelPipeline_should_close_channel_or_not_depending_on_error_and_state_of_request(
        boolean errorIsNull, boolean responseSendingStarted, boolean responseSendingCompleted, boolean channelCloseIsExpected
    ) throws JsonProcessingException {
        // given
        Throwable error = (errorIsNull) ? null : new RuntimeException("kaboom");
        doReturn(responseSendingStarted).when(responseInfoMock).isResponseSendingStarted();
        doReturn(responseSendingCompleted).when(responseInfoMock).isResponseSendingLastChunkSent();
        if (responseSendingCompleted) {
            state.setResponseWriterFinalChunkChannelFuture(mock(ChannelFuture.class));
        }

        // when
        handler.finalizeChannelPipeline(ctxMock, null, state, error);

        // then
        if (channelCloseIsExpected)
            verify(channelMock).close();
        else
            verify(channelMock, never()).close();
    }

    @DataProvider(value = {
        "true   |   true",
        "false  |   true",
        "true   |   false",
        "false  |   false"
    }, splitBy = "\\|")
    @Test
    public void doChannelInactive_completes_releases_resources_and_completes_tracing_if_necessary(
        boolean tracingAlreadyDone, boolean responseSendingCompleted
    ) throws Exception {
        // given
        Span span = setupTracingForChannelInactive(tracingAlreadyDone);
        doReturn(responseSendingCompleted).when(responseInfoMock).isResponseSendingLastChunkSent();
        if (responseSendingCompleted) {
            state.setResponseWriterFinalChunkChannelFuture(mock(ChannelFuture.class));
        }

        // when
        PipelineContinuationBehavior result = handler.doChannelInactive(ctxMock);

        // then
        Assertions.assertThat(span.isCompleted()).isEqualTo(!tracingAlreadyDone);
        Assertions.assertThat(state.isTraceCompletedOrScheduled()).isTrue();

        verify(requestInfoMock).releaseAllResources();
        verify(proxyRouterStateMock).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock).cancelDownstreamRequest(any());
        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void doChannelInactive_completes_metrics_if_necessary_and_HttpProcessingState_is_not_null(
        boolean stateIsNull
    ) throws Exception {
        // given
        ChannelPipelineFinalizerHandler handlerSpy = spy(handler);
        if (stateIsNull) {
            doReturn(null).when(stateAttributeMock).get();
        }

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelInactive(ctxMock);

        // then
        if (stateIsNull) {
            verify(handlerSpy, never()).handleMetricsForCompletedRequestIfNotAlreadyDone(any(HttpProcessingState.class));
        }
        else {
            verify(handlerSpy).handleMetricsForCompletedRequestIfNotAlreadyDone(state);
        }
    }

    private Span setupTracingForChannelInactive(boolean traceCompletedOrScheduled) {
        state.setTraceCompletedOrScheduled(traceCompletedOrScheduled);
        Span span = Span.newBuilder("fooSpan", Span.SpanPurpose.SERVER).build();
        Assertions.assertThat(span.isCompleted()).isFalse();
        Deque<Span> spanStack = new LinkedList<>();
        spanStack.add(span);
        state.setDistributedTraceStack(spanStack);

        return span;
    }

    @DataProvider(value = {
        "true   |   false",
        "false  |   true",
        "true   |   true"
    }, splitBy = "\\|")
    @Test
    public void doChannelInactive_does_what_it_can_when_the_HttpProcessingState_or_ProxyRouterProcessingState_is_null(
        boolean httpStateIsNull, boolean proxyRouterStateIsNull
    ) throws Exception {
        // given
        if (httpStateIsNull)
            doReturn(null).when(stateAttributeMock).get();

        if (proxyRouterStateIsNull)
            doReturn(null).when(proxyRouterProcessingStateAttributeMock).get();

        Span span = setupTracingForChannelInactive(false);

        // when
        PipelineContinuationBehavior result = handler.doChannelInactive(ctxMock);

        // then
        if (httpStateIsNull)
            Assertions.assertThat(span.isCompleted()).isFalse();
        else
            Assertions.assertThat(span.isCompleted()).isTrue();

        if (httpStateIsNull)
            verifyNoInteractions(requestInfoMock);
        else
            verify(requestInfoMock).releaseAllResources();

        if (proxyRouterStateIsNull)
            verifyNoInteractions(proxyRouterStateMock);
        else {
            verify(proxyRouterStateMock).cancelRequestStreaming(any(), any());
            verify(proxyRouterStateMock).cancelDownstreamRequest(any());
        }
        
        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @DataProvider(value = {
        "true   |   false",
        "false  |   true",
        "true   |   true"
    }, splitBy = "\\|")
    @Test
    public void doChannelInactive_does_what_it_can_when_the_RequestInfo_or_ResponseInfo_is_null(
        boolean requestInfoIsNull, boolean responseInfoIsNull
    ) throws Exception {
        // given
        Span span = setupTracingForChannelInactive(false);

        if (requestInfoIsNull)
            state.setRequestInfo(null);

        if (responseInfoIsNull)
            state.setResponseInfo(null, null);

        // when
        PipelineContinuationBehavior result = handler.doChannelInactive(ctxMock);

        // then
        Assertions.assertThat(span.isCompleted()).isTrue();
        Assertions.assertThat(state.isTraceCompletedOrScheduled()).isTrue();

        if (requestInfoIsNull)
            verifyNoInteractions(requestInfoMock);
        else
            verify(requestInfoMock).releaseAllResources();
        
        verify(proxyRouterStateMock).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock).cancelDownstreamRequest(any());
        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelInactive_does_not_try_to_recomplete_span_if_already_completed() throws Exception {
        // given
        Span span = setupTracingForChannelInactive(false);
        Deque<Span> deque = new LinkedList<>();
        deque.add(span);
        Tracer.getInstance().registerWithThread(deque);
        Tracer.getInstance().completeRequestSpan();
        Assertions.assertThat(span.isCompleted()).isTrue();
        long durationNanosBefore = span.getDurationNanos();

        // when
        PipelineContinuationBehavior result = handler.doChannelInactive(ctxMock);

        // then
        Assertions.assertThat(span.isCompleted()).isTrue(); // no change
        Assertions.assertThat(span.getDurationNanos()).isEqualTo(durationNanosBefore);

        verify(requestInfoMock).releaseAllResources();
        verify(proxyRouterStateMock).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock).cancelDownstreamRequest(any());
        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelInactive_does_not_explode_if_span_is_missing() throws Exception {
        // given
        Assertions.assertThat(state.getDistributedTraceStack()).isNull();

        // when
        PipelineContinuationBehavior result = handler.doChannelInactive(ctxMock);

        // then
        verify(requestInfoMock).releaseAllResources();
        verify(proxyRouterStateMock).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock).cancelDownstreamRequest(any());
        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }
    
    @Test
    public void doChannelInactive_does_not_explode_if_crazy_exception_occurs() throws Exception {
        // given
        doThrow(new RuntimeException("kaboom")).when(proxyRouterProcessingStateAttributeMock).get();

        // when
        PipelineContinuationBehavior result = handler.doChannelInactive(ctxMock);

        // then
        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo_returns_false() {
        // expect
        Assertions.assertThat(
            handler.argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(null, null, null, null)
        ).isFalse();
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void doChannelInactive_should_call_accessLogger_if_access_logging_not_already_scheduled(
        boolean requestInfoIsNull
    ) throws Exception {
        // given
        state.setAccessLogCompletedOrScheduled(false);
        if (requestInfoIsNull) {
            state.setRequestInfo(null);
        }

        HttpResponse actualResponseMock = mock(HttpResponse.class);
        state.setActualResponseObject(actualResponseMock);

        HttpProcessingState stateSpy = spy(state);
        doReturn(42L).when(stateSpy).calculateTotalRequestTimeMillis();
        doReturn(stateSpy).when(stateAttributeMock).get();

        Assertions.assertThat(stateSpy.isAccessLogCompletedOrScheduled()).isFalse();

        // when
        handler.doChannelInactive(ctxMock);

        // then
        verify(stateSpy).setResponseEndTimeNanosToNowIfNotAlreadySet();
        ArgumentCaptor<RequestInfo> requestInfoArgumentCaptor = ArgumentCaptor.forClass(RequestInfo.class);
        verify(accessLoggerMock).log(
            requestInfoArgumentCaptor.capture(), eq(actualResponseMock), eq(responseInfoMock), eq(42L)
        );
        RequestInfo actualRequestInfo = requestInfoArgumentCaptor.getValue();
        if (requestInfoIsNull) {
            Assertions.assertThat(actualRequestInfo.getUri()).isEqualTo(RequestInfo.NONE_OR_UNKNOWN_TAG);
        }
        else {
            Assertions.assertThat(actualRequestInfo).isSameAs(requestInfoMock);
        }
        Assertions.assertThat(stateSpy.isAccessLogCompletedOrScheduled()).isTrue();
    }

    @DataProvider(value = {
        "true   |   false",
        "false  |   true"
    }, splitBy = "\\|")
    @Test
    public void doChannelInactive_should_not_call_accessLogger_if_accessLogger_is_null_or_access_logging_already_scheduled(
        boolean accessLoggerIsNull, boolean accessLoggingAlreadyScheduled
    ) throws Exception {
        // given
        if (accessLoggerIsNull)
            Whitebox.setInternalState(handler, "accessLogger", null);

        state.setAccessLogCompletedOrScheduled(accessLoggingAlreadyScheduled);

        // when
        handler.doChannelInactive(ctxMock);

        // then
        verifyNoInteractions(accessLoggerMock);
        Assertions.assertThat(state.isAccessLogCompletedOrScheduled()).isEqualTo(accessLoggingAlreadyScheduled);
    }

    @Test
    public void handleMetricsForCompletedRequestIfNotAlreadyDone_does_not_propagate_unexpected_exceptions() {
        // given
        HttpProcessingState stateMock = mock(HttpProcessingState.class);
        doThrow(new RuntimeException("intentional exception")).when(stateMock).isRequestMetricsRecordedOrScheduled();

        // when
        Throwable ex = catchThrowable(() -> handler.handleMetricsForCompletedRequestIfNotAlreadyDone(stateMock));
        
        // then
        Assertions.assertThat(ex).isNull();
        verify(stateMock).isRequestMetricsRecordedOrScheduled();
    }

    @Test
    public void doChannelInactive_does_not_propagate_unexpected_exception_while_handling_tracing_completion(
    ) throws Exception {
        // given
        HttpProcessingState stateSpy = spy(state);

        doReturn(stateSpy).when(stateAttributeMock).get();

        doThrow(new RuntimeException("intentional exception"))
            .when(stateSpy).handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone();

        Assertions.assertThat(stateSpy.isAccessLogCompletedOrScheduled()).isFalse();
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isFalse();
        verify(requestInfoMock, never()).releaseAllResources();
        verify(proxyRouterStateMock, never()).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock, never()).cancelDownstreamRequest(any());

        // when
        PipelineContinuationBehavior result = handler.doChannelInactive(ctxMock);

        // then
        // Verify that the exception was thrown.
        verify(stateSpy).handleTracingResponseTaggingAndFinalSpanNameIfNotAlreadyDone();

        // Verify that no other finalization logic was impacted.
        Assertions.assertThat(stateSpy.isAccessLogCompletedOrScheduled()).isTrue();
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isTrue();
        verify(requestInfoMock).releaseAllResources();
        verify(proxyRouterStateMock).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock).cancelDownstreamRequest(any());
        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelInactive_does_not_propagate_unexpected_exception_while_handling_access_logging(
    ) throws Exception {
        // given
        HttpProcessingState stateSpy = spy(state);

        doReturn(stateSpy).when(stateAttributeMock).get();

        doThrow(new RuntimeException("intentional exception"))
            .when(accessLoggerMock).log(any(), any(), any(), any());

        Assertions.assertThat(stateSpy.isTraceCompletedOrScheduled()).isFalse();
        Assertions.assertThat(stateSpy.isAccessLogCompletedOrScheduled()).isFalse();
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isFalse();
        verify(requestInfoMock, never()).releaseAllResources();
        verify(proxyRouterStateMock, never()).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock, never()).cancelDownstreamRequest(any());

        // when
        PipelineContinuationBehavior result = handler.doChannelInactive(ctxMock);

        // then
        // Verify that the exception was thrown.
        verify(accessLoggerMock).log(any(), any(), any(), any());

        // Verify that no other finalization logic was impacted.
        Assertions.assertThat(stateSpy.isTraceCompletedOrScheduled()).isTrue();
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isTrue();
        verify(requestInfoMock).releaseAllResources();
        verify(proxyRouterStateMock).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock).cancelDownstreamRequest(any());
        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelInactive_does_not_propagate_unexpected_exception_while_releasing_request_resources(
    ) throws Exception {
        // given
        HttpProcessingState stateSpy = spy(state);

        doReturn(stateSpy).when(stateAttributeMock).get();

        doThrow(new RuntimeException("intentional exception"))
            .when(requestInfoMock).releaseAllResources();

        Assertions.assertThat(stateSpy.isTraceCompletedOrScheduled()).isFalse();
        Assertions.assertThat(stateSpy.isAccessLogCompletedOrScheduled()).isFalse();
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isFalse();
        verify(requestInfoMock, never()).releaseAllResources();
        verify(proxyRouterStateMock, never()).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock, never()).cancelDownstreamRequest(any());

        // when
        PipelineContinuationBehavior result = handler.doChannelInactive(ctxMock);

        // then
        // Verify that the exception was thrown.
        verify(requestInfoMock).releaseAllResources();

        // Verify that no other finalization logic was impacted.
        Assertions.assertThat(stateSpy.isTraceCompletedOrScheduled()).isTrue();
        Assertions.assertThat(stateSpy.isAccessLogCompletedOrScheduled()).isTrue();
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isTrue();
        verify(requestInfoMock).releaseAllResources();
        verify(proxyRouterStateMock).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock).cancelDownstreamRequest(any());
        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelInactive_does_not_propagate_unexpected_exception_while_releasing_proxy_router_state_resources(
    ) throws Exception {
        // given
        HttpProcessingState stateSpy = spy(state);

        doReturn(stateSpy).when(stateAttributeMock).get();

        doThrow(new RuntimeException("intentional exception"))
            .when(proxyRouterStateMock).cancelDownstreamRequest(any());

        Assertions.assertThat(stateSpy.isTraceCompletedOrScheduled()).isFalse();
        Assertions.assertThat(stateSpy.isAccessLogCompletedOrScheduled()).isFalse();
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isFalse();
        verify(requestInfoMock, never()).releaseAllResources();
        verify(proxyRouterStateMock, never()).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock, never()).cancelDownstreamRequest(any());

        // when
        PipelineContinuationBehavior result = handler.doChannelInactive(ctxMock);

        // then
        // Verify that the exception was thrown.
        verify(requestInfoMock).releaseAllResources();

        // Verify that no other finalization logic was impacted.
        Assertions.assertThat(stateSpy.isTraceCompletedOrScheduled()).isTrue();
        Assertions.assertThat(stateSpy.isAccessLogCompletedOrScheduled()).isTrue();
        Assertions.assertThat(stateSpy.isRequestMetricsRecordedOrScheduled()).isTrue();
        verify(requestInfoMock).releaseAllResources();
        verify(proxyRouterStateMock).cancelRequestStreaming(any(), any());
        verify(proxyRouterStateMock).cancelDownstreamRequest(any());
        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void logErrorWithTracing_using_HttpProcessingState_gracefully_handles_when_state_is_null() {
        // given
        String msg = UUID.randomUUID().toString();
        Throwable exMock = mock(Throwable.class);
        ChannelPipelineFinalizerHandler handlerSpy = spy(handler);

        // when
        handlerSpy.logErrorWithTracing(msg, exMock, (HttpProcessingState)null);

        // then
        verify(handlerSpy).logErrorWithTracing(msg, exMock, null, null);
    }

    @Test
    public void logErrorWithTracing_using_ChannelHandlerContext_gracefully_handles_when_ctx_is_null() {
        // given
        String msg = UUID.randomUUID().toString();
        Throwable exMock = mock(Throwable.class);
        ChannelPipelineFinalizerHandler handlerSpy = spy(handler);

        // when
        handlerSpy.logErrorWithTracing(msg, exMock, (ChannelHandlerContext)null);

        // then
        verify(handlerSpy).logErrorWithTracing(msg, exMock, null, null);
    }

    @Test
    public void code_coverage_hoops() throws Exception {
        // jump!

        // doChannelInactive() does some debug logging if the logger has debug logging enabled.
        Logger loggerMock = mock(Logger.class);
        doReturn(true).when(loggerMock).isDebugEnabled();
        Whitebox.setInternalState(handler, "logger", loggerMock);
        handler.doChannelInactive(ctxMock);
        doReturn(false).when(loggerMock).isDebugEnabled();
        handler.doChannelInactive(ctxMock);
    }
}