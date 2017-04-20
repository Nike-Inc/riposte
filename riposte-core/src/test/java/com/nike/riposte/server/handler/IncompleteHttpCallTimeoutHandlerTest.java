package com.nike.riposte.server.handler;

import com.nike.riposte.server.error.exception.IncompleteHttpCallTimeoutException;

import org.junit.Before;
import org.junit.Test;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import static io.netty.handler.timeout.IdleStateEvent.ALL_IDLE_STATE_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link IncompleteHttpCallTimeoutHandler}.
 *
 * @author Nic Munroe
 */
public class IncompleteHttpCallTimeoutHandlerTest {

    private Channel channelMock;
    private ChannelHandlerContext ctxMock;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(mock(Attribute.class)).when(channelMock).attr(any(AttributeKey.class));
    }

    @Test
    public void IncompleteHttpCallTimeoutHandler_throws_IncompleteHttpCallTimeoutException_and_sets_alreadyTriggeredException_to_true_when_channelIdle_is_called() throws Exception {
        // given
        long timeoutMillis = 4242;
        IncompleteHttpCallTimeoutHandler handlerSpy = spy(new IncompleteHttpCallTimeoutHandler(timeoutMillis));
        assertThat(handlerSpy.alreadyTriggeredException).isFalse();

        // when
        Throwable ex = catchThrowable(() -> handlerSpy.channelIdle(ctxMock, ALL_IDLE_STATE_EVENT));

        // then
        assertThat(ex).isInstanceOf(IncompleteHttpCallTimeoutException.class);
        IncompleteHttpCallTimeoutException timeoutEx = (IncompleteHttpCallTimeoutException)ex;
        assertThat(timeoutEx.timeoutMillis).isEqualTo(timeoutMillis);
        assertThat(handlerSpy.alreadyTriggeredException).isTrue();
        verify(handlerSpy).channelIdleTriggered(ctxMock, ALL_IDLE_STATE_EVENT);
    }

    @Test
    public void IncompleteHttpCallTimeoutHandler_does_nothing_if_alreadyTriggeredException_is_true() {
        // given
        long timeoutMillis = 4242;
        IncompleteHttpCallTimeoutHandler handlerSpy = spy(new IncompleteHttpCallTimeoutHandler(timeoutMillis));
        handlerSpy.alreadyTriggeredException = true;

        // when
        Throwable ex = catchThrowable(() -> handlerSpy.channelIdle(ctxMock, ALL_IDLE_STATE_EVENT));

        // then
        assertThat(ex).isNull();
        verify(handlerSpy, never()).channelIdleTriggered(any(ChannelHandlerContext.class), any(IdleStateEvent.class));

    }
}