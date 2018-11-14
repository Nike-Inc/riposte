package com.nike.riposte.server.handler;

import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.metrics.ServerMetricsEvent;
import com.nike.wingtips.Span;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;

import static com.nike.riposte.server.channelpipeline.HttpChannelInitializer.IDLE_CHANNEL_TIMEOUT_HANDLER_NAME;
import static com.nike.riposte.server.channelpipeline.HttpChannelInitializer.INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link RequestStateCleanerHandler}
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class RequestStateCleanerHandlerTest {

    private RequestStateCleanerHandler handler;
    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private ChannelPipeline pipelineMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private Attribute<ProxyRouterProcessingState> proxyRouterProcessingStateAttrMock;
    private MetricsListener metricsListenerMock;
    private HttpRequest msgMockFirstChunkOnly;
    private FullHttpRequest msgMockFullRequest;
    private LastHttpContent msgMockLastChunkOnly;
    private IdleChannelTimeoutHandler idleChannelTimeoutHandlerMock;
    private long incompleteHttpCallTimeoutMillis = 4242;
    private DistributedTracingConfig<Span> distributedTracingConfigMock;

    @Before
    public void beforeMethod() {
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        pipelineMock = mock(ChannelPipeline.class);
        stateAttrMock = mock(Attribute.class);
        proxyRouterProcessingStateAttrMock = mock(Attribute.class);
        metricsListenerMock = mock(MetricsListener.class);
        distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        msgMockFirstChunkOnly = mock(HttpRequest.class);
        msgMockFullRequest = mock(FullHttpRequest.class);
        msgMockLastChunkOnly = mock(LastHttpContent.class);
        idleChannelTimeoutHandlerMock = mock(IdleChannelTimeoutHandler.class);

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(pipelineMock).when(ctxMock).pipeline();
        doReturn(idleChannelTimeoutHandlerMock).when(pipelineMock).get(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(proxyRouterProcessingStateAttrMock).when(channelMock).attr(ChannelAttributes.PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY);

        handler = new RequestStateCleanerHandler(
            metricsListenerMock, incompleteHttpCallTimeoutMillis, distributedTracingConfigMock
        );
    }

    @Test
    public void constructor_sets_fields_as_expected() {
        // when
        RequestStateCleanerHandler handler = new RequestStateCleanerHandler(
            metricsListenerMock, incompleteHttpCallTimeoutMillis, distributedTracingConfigMock
        );

        // then
        assertThat(handler.metricsListener).isSameAs(metricsListenerMock);
        assertThat(handler.incompleteHttpCallTimeoutMillis).isEqualTo(incompleteHttpCallTimeoutMillis);
        assertThat(handler.distributedTracingConfig).isSameAs(distributedTracingConfigMock);
    }

    @Test
    public void channelRead_cleans_the_state_and_starts_metrics_request_and_removes_any_existing_IdleChannelTimeoutHandler() throws Exception {
        // when
        handler.channelRead(ctxMock, msgMockFirstChunkOnly);

        // then
        verify(stateMock).cleanStateForNewRequest();
        verify(metricsListenerMock).onEvent(ServerMetricsEvent.REQUEST_RECEIVED, stateMock);
        verify(pipelineMock).get(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
        verify(pipelineMock).remove(idleChannelTimeoutHandlerMock);
    }

    @Test
    public void channelRead_does_not_remove_IdleChannelTimeoutHandler_if_it_is_not_in_the_pipeline() throws Exception {
        // given
        doReturn(null).when(pipelineMock).get(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);

        // when
        handler.channelRead(ctxMock, msgMockFirstChunkOnly);

        // then
        verify(pipelineMock).get(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
        verify(pipelineMock, never()).remove(idleChannelTimeoutHandlerMock);
    }

    @Test
    public void channelRead_creates_new_state_if_one_does_not_already_exist() throws Exception {
        // given
        AtomicReference<HttpProcessingState> stateRef = new AtomicReference<>(null);
        doAnswer(invocation -> stateRef.get()).when(stateAttrMock).get();
        doAnswer(
            invocation -> {
                stateRef.set(invocation.getArgumentAt(0, HttpProcessingState.class));
                return null;
            }
        ).when(stateAttrMock).set(any(HttpProcessingState.class));

        // when
        handler.channelRead(ctxMock, msgMockFirstChunkOnly);

        // then
        // Verify a real HttpProcessingState was created and set on the stateAttrMock.
        HttpProcessingState actualState = stateRef.get();
        assertThat(actualState).isNotNull();
        verify(stateAttrMock).set(actualState);

        // Verify the expected DistributedTracingConfig was set on the new state.
        assertThat(Whitebox.getInternalState(actualState, "distributedTracingConfig"))
            .isSameAs(distributedTracingConfigMock);

        // Verify metrics listener was called for a request received event using the new state.
        verify(metricsListenerMock).onEvent(eq(ServerMetricsEvent.REQUEST_RECEIVED), eq(actualState));

        // sanity check - we should have rewired stateAttrMock to not do anything with stateMock.
        verifyZeroInteractions(stateMock);
    }

    @Test
    public void channelRead_does_not_explode_if_metricsListener_is_null() throws Exception {
        // given
        RequestStateCleanerHandler handlerNoMetrics = new RequestStateCleanerHandler(
            null, incompleteHttpCallTimeoutMillis, distributedTracingConfigMock
        );

        // when
        handlerNoMetrics.channelRead(ctxMock, msgMockFirstChunkOnly);

        // then
        verify(stateMock).cleanStateForNewRequest();
        verifyZeroInteractions(metricsListenerMock);
    }

    @DataProvider(value = {
        // the *ONLY* time the handler is added is if the timeout is non-zero *and* it's the first chunk *only*
        "true   |   true    |   true",
        "true   |   false   |   false",
        "false  |   true    |   false",
        "false  |   false   |   false",
    }, splitBy = "\\|")
    @Test
    public void channelRead_adds_IncompleteHttpCallTimeoutHandler_if_appropriate_and_handler_does_not_already_exist(
        boolean timeoutMillisGreaterThanZero, boolean isFirstChunkOnly, boolean expectIncompleteTimeoutHandlerAdded
    ) throws Exception {
        // given
        long timeoutMillis = (timeoutMillisGreaterThanZero) ? 42 : 0;
        Object msg = (isFirstChunkOnly) ? msgMockFirstChunkOnly : msgMockFullRequest;
        RequestStateCleanerHandler handlerToUse = new RequestStateCleanerHandler(
            null, timeoutMillis, distributedTracingConfigMock
        );
        doReturn(null).when(pipelineMock).get(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME);
        doReturn(null).when(pipelineMock).get(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);

        // when
        handlerToUse.channelRead(ctxMock, msg);

        // then
        if (expectIncompleteTimeoutHandlerAdded) {
            ArgumentCaptor<ChannelHandler> handlerArgCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
            verify(pipelineMock).addFirst(eq(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME), handlerArgCaptor.capture());
            assertThat(handlerArgCaptor.getValue()).isInstanceOf(IncompleteHttpCallTimeoutHandler.class);
            IncompleteHttpCallTimeoutHandler handlerAdded = (IncompleteHttpCallTimeoutHandler)handlerArgCaptor.getValue();
            assertThat(handlerAdded.idleTimeoutMillis).isEqualTo(timeoutMillis);
        }
        else {
            verify(pipelineMock).get(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
            verifyNoMoreInteractions(pipelineMock);
        }
    }

    @Test
    public void channelRead_replaces_IncompleteHttpCallTimeoutHandler_if_one_already_exists() throws Exception {
        // given
        long timeoutMillis = 42;
        RequestStateCleanerHandler handlerToUse = new RequestStateCleanerHandler(
            null, timeoutMillis, distributedTracingConfigMock
        );
        IncompleteHttpCallTimeoutHandler alreadyExistingHandler = mock(IncompleteHttpCallTimeoutHandler.class);
        doReturn(alreadyExistingHandler).when(pipelineMock).get(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME);
        doReturn(null).when(pipelineMock).get(IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);

        // when
        handlerToUse.channelRead(ctxMock, msgMockFirstChunkOnly);

        // then
        // The normal happy path addition of the timeout handler should not have occurred.
        verify(pipelineMock, never()).addFirst(eq(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME), any(ChannelHandler.class));

        // Instead, the existing handler should have been replaced.
        ArgumentCaptor<ChannelHandler> handlerArgCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(pipelineMock).replace(eq(alreadyExistingHandler), eq(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME), handlerArgCaptor.capture());
        assertThat(handlerArgCaptor.getValue()).isInstanceOf(IncompleteHttpCallTimeoutHandler.class);
        IncompleteHttpCallTimeoutHandler handlerAdded = (IncompleteHttpCallTimeoutHandler)handlerArgCaptor.getValue();
        assertThat(handlerAdded.idleTimeoutMillis).isEqualTo(timeoutMillis);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void channelRead_removes_IncompleteHttpCallTimeoutHandler_gracefully_on_last_chunk_only_messages(
        boolean handlerExistsInPipeline
    ) throws Exception {
        // given
        IncompleteHttpCallTimeoutHandler existingHandler = (handlerExistsInPipeline)
                                                           ? mock(IncompleteHttpCallTimeoutHandler.class)
                                                           : null;
        doReturn(existingHandler).when(pipelineMock).get(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME);

        // when
        handler.channelRead(ctxMock, msgMockLastChunkOnly);

        // then
        verify(pipelineMock).get(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME);

        if (handlerExistsInPipeline)
            verify(pipelineMock).remove(INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME);
        else
            verifyNoMoreInteractions(pipelineMock);
    }

    @Test
    public void channelRead_does_nothing_if_msg_is_not_HttpRequest_or_LastHttpContent() throws Exception {
        // given
        HttpMessage ignoredMsgMock = mock(HttpMessage.class);

        // when
        handler.channelRead(ctxMock, ignoredMsgMock);

        // then
        verify(ctxMock).fireChannelRead(ignoredMsgMock); // the normal continuation behavior from the super class.
        verifyNoMoreInteractions(ctxMock); // nothing else should have happened related to the ctx.
        verifyZeroInteractions(stateMock);
        verifyZeroInteractions(metricsListenerMock);
    }

}