package com.nike.riposte.server.handler;

import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.HttpChannelInitializer;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.metrics.ServerMetricsEvent;

import org.junit.Before;
import org.junit.Test;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Attribute;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link RequestStateCleanerHandler}
 *
 * @author Nic Munroe
 */
public class RequestStateCleanerHandlerTest {

    private RequestStateCleanerHandler handler;
    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private ChannelPipeline pipelineMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private Attribute<ProxyRouterProcessingState> proxyRouterProcessingStateAttrMock;
    private MetricsListener metricsListenerMock;
    private HttpRequest msgMock;
    private IdleChannelTimeoutHandler idleChannelTimeoutHandlerMock;

    @Before
    public void beforeMethod() {
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        pipelineMock = mock(ChannelPipeline.class);
        stateAttrMock = mock(Attribute.class);
        proxyRouterProcessingStateAttrMock = mock(Attribute.class);
        metricsListenerMock = mock(MetricsListener.class);
        msgMock = mock(HttpRequest.class);
        idleChannelTimeoutHandlerMock = mock(IdleChannelTimeoutHandler.class);

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(pipelineMock).when(ctxMock).pipeline();
        doReturn(idleChannelTimeoutHandlerMock).when(pipelineMock).get(HttpChannelInitializer.IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(proxyRouterProcessingStateAttrMock).when(channelMock).attr(ChannelAttributes.PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY);

        handler = new RequestStateCleanerHandler(metricsListenerMock);
    }

    @Test
    public void channelRead_cleans_the_state_and_starts_metrics_request_and_removes_any_existing_IdleChannelTimeoutHandler() throws Exception {
        // when
        handler.channelRead(ctxMock, msgMock);

        // then
        verify(stateMock).cleanStateForNewRequest();
        verify(metricsListenerMock).onEvent(ServerMetricsEvent.REQUEST_RECEIVED, stateMock);
        verify(pipelineMock).get(HttpChannelInitializer.IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
        verify(pipelineMock).remove(idleChannelTimeoutHandlerMock);
    }

    @Test
    public void channelRead_does_not_remove_IdleChannelTimeoutHandler_if_it_is_not_in_the_pipeline() throws Exception {
        // given
        doReturn(null).when(pipelineMock).get(HttpChannelInitializer.IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);

        // when
        handler.channelRead(ctxMock, msgMock);

        // then
        verify(pipelineMock).get(HttpChannelInitializer.IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
        verify(pipelineMock, never()).remove(idleChannelTimeoutHandlerMock);
    }

    @Test
    public void channelRead_creates_new_state_if_one_does_not_already_exist() throws Exception {
        // given
        doReturn(null).when(stateAttrMock).get();

        // when
        handler.channelRead(ctxMock, msgMock);

        // then
        verifyNoMoreInteractions(stateMock);
        verify(metricsListenerMock).onEvent(eq(ServerMetricsEvent.REQUEST_RECEIVED), any(HttpProcessingState.class));
    }

    @Test
    public void channelRead_does_not_explode_if_metricsListener_is_null() throws Exception {
        // given
        RequestStateCleanerHandler handlerNoMetrics = new RequestStateCleanerHandler(null);

        // when
        handlerNoMetrics.channelRead(ctxMock, msgMock);

        // then
        verify(stateMock).cleanStateForNewRequest();
        verifyNoMoreInteractions(metricsListenerMock);
    }

    @Test
    public void channelRead_does_nothing_if_msg_is_not_HttpRequest() throws Exception {
        // given
        HttpMessage ignoredMsgMock = mock(HttpMessage.class);

        // when
        handler.channelRead(ctxMock, ignoredMsgMock);

        // then
        verifyNoMoreInteractions(stateMock);
        verifyNoMoreInteractions(metricsListenerMock);
    }

}