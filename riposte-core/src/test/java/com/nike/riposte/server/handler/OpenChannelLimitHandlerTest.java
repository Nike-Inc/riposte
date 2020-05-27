package com.nike.riposte.server.handler;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.error.exception.TooManyOpenChannelsException;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Attribute;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ScheduledFuture;

import static com.nike.riposte.server.handler.OpenChannelLimitHandler.TOO_MANY_OPEN_CONNECTIONS_THIS_CHANNEL_SHOULD_CLOSE;
import static com.nike.riposte.server.handler.base.PipelineContinuationBehavior.CONTINUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link OpenChannelLimitHandler}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class OpenChannelLimitHandlerTest {

    private ChannelGroup channelGroupMock;
    private int maxOpenChannelsThreshold;

    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<Integer> tooManyOpenConnectionsAttributeMock;
    private EventLoop eventLoopMock;
    private ChannelFuture closeFutureMock;

    private Object msg = mock(HttpRequest.class);

    private ScheduledFuture doubleCheckScheduledFutureMock;
    private ArgumentCaptor<Runnable> doubleCheckRunnableCaptor;

    private ArgumentCaptor<GenericFutureListener> closeFutureListenerCaptor;

    private OpenChannelLimitHandler handler;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        tooManyOpenConnectionsAttributeMock = mock(Attribute.class);
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(tooManyOpenConnectionsAttributeMock).when(channelMock)
                                                     .attr(TOO_MANY_OPEN_CONNECTIONS_THIS_CHANNEL_SHOULD_CLOSE);
        doReturn(true).when(channelMock).isOpen();

        eventLoopMock = mock(EventLoop.class);
        closeFutureMock = mock(ChannelFuture.class);
        doReturn(eventLoopMock).when(channelMock).eventLoop();
        doReturn(closeFutureMock).when(channelMock).closeFuture();

        doubleCheckScheduledFutureMock = mock(ScheduledFuture.class);
        doubleCheckRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        closeFutureListenerCaptor = ArgumentCaptor.forClass(GenericFutureListener.class);

        doReturn(doubleCheckScheduledFutureMock).when(eventLoopMock)
                                                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        doReturn(false).when(doubleCheckScheduledFutureMock).isDone();

        channelGroupMock = mock(ChannelGroup.class);
        maxOpenChannelsThreshold = 42;

        handler = new OpenChannelLimitHandler(channelGroupMock, maxOpenChannelsThreshold);
    }

    @Test
    public void constructor_sets_fields_as_expected() {
        // when
        OpenChannelLimitHandler instance = new OpenChannelLimitHandler(channelGroupMock, maxOpenChannelsThreshold);

        // then
        assertThat((Object)instance.openChannelsGroup).isEqualTo(channelGroupMock);
        assertThat(instance.maxOpenChannelsThreshold).isEqualTo(maxOpenChannelsThreshold);
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_ChannelGroup_is_null() {
        // when
        Throwable ex = catchThrowable(() -> new OpenChannelLimitHandler(null, maxOpenChannelsThreshold));

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_threshold_is_less_than_1() {
        // when
        Throwable ex = catchThrowable(() -> new OpenChannelLimitHandler(channelGroupMock, 0));

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class);
    }

    private void setActualOpenChannels(int actualOpenChannels) {
        doReturn(actualOpenChannels).when(channelGroupMock).size();
    }

    private Pair<Runnable, GenericFutureListener> extractDoubleCheckRunnableAndCloseFutureListener() {
        verify(eventLoopMock).schedule(doubleCheckRunnableCaptor.capture(), anyLong(), any(TimeUnit.class));
        verify(closeFutureMock).addListener(closeFutureListenerCaptor.capture());
        return Pair.of(doubleCheckRunnableCaptor.getValue(), closeFutureListenerCaptor.getValue());
    }

    private void verifyDoubleCheckFuture(Runnable future) {
        // If channel is open then the future should close it.
        reset(channelMock);
        doReturn(true).when(channelMock).isOpen();
        future.run();
        verify(channelMock).isOpen();
        verify(channelMock).close();
        verifyNoMoreInteractions(channelMock);

        // And when the channel is already closed then it doesn't do anything.
        reset(channelMock);
        doReturn(false).when(channelMock).isOpen();
        future.run();
        verify(channelMock).isOpen();
        verifyNoMoreInteractions(channelMock);
    }

    private void verifyCloseFutureListener(GenericFutureListener closeFutureListener) throws Exception {
        // If the double-check ScheduledFuture is not done then it cancels it.
        reset(doubleCheckScheduledFutureMock);
        doReturn(false).when(doubleCheckScheduledFutureMock).isDone();
        closeFutureListener.operationComplete(null);
        verify(doubleCheckScheduledFutureMock).isDone();
        verify(doubleCheckScheduledFutureMock).cancel(false);
        verifyNoMoreInteractions(doubleCheckScheduledFutureMock);

        // And when the double-check ScheduledFuture is done, then nothing happens.
        reset(doubleCheckScheduledFutureMock);
        doReturn(true).when(doubleCheckScheduledFutureMock).isDone();
        closeFutureListener.operationComplete(null);
        verify(doubleCheckScheduledFutureMock).isDone();
        verifyNoMoreInteractions(doubleCheckScheduledFutureMock);
    }

    @DataProvider(value = {
        "0",
        "1"
    })
    @Test
    public void doChannelActive_marks_and_schedules_double_check_timeout_if_too_many_open_channels(
        int numOpenChannelsGreaterThanMax
    ) throws Exception {
        // given
        int actualOpenChannels = maxOpenChannelsThreshold + numOpenChannelsGreaterThanMax;
        setActualOpenChannels(actualOpenChannels);

        // when
        PipelineContinuationBehavior result = handler.doChannelActive(ctxMock);

        // then
        assertThat(result).isEqualTo(CONTINUE);
        Pair<Runnable, GenericFutureListener> futureInfoPair = extractDoubleCheckRunnableAndCloseFutureListener();
        verify(tooManyOpenConnectionsAttributeMock).set(actualOpenChannels);
        verifyDoubleCheckFuture(futureInfoPair.getLeft());
        verifyCloseFutureListener(futureInfoPair.getRight());
        verify(channelGroupMock, never()).add(channelMock);
    }

    @Test
    public void doChannelActive_adds_channel_to_channelGroup_if_open_channel_count_lower_than_max_threshold()
        throws Exception {
        // given
        setActualOpenChannels(maxOpenChannelsThreshold - 1);

        // when
        PipelineContinuationBehavior result = handler.doChannelActive(ctxMock);

        // then
        assertThat(result).isEqualTo(CONTINUE);
        verify(channelGroupMock).add(channelMock);
    }

    @DataProvider(value = {
        "null   |   false",
        "-1     |   false",
        "0      |   true",
        "1      |   true"
    }, splitBy = "\\|")
    @Test
    public void doChannelRead_throws_TooManyOpenChannelsException_or_not_depending_on_the_number_of_open_channels(
        Integer numOpenChannelsDiffFromMaxThreshold, boolean expectTooManyOpenChannelsException
    ) {
        // given
        Integer numOpenChannels = (numOpenChannelsDiffFromMaxThreshold == null)
                                  ? null
                                  : maxOpenChannelsThreshold + numOpenChannelsDiffFromMaxThreshold;
        doReturn(numOpenChannels).when(tooManyOpenConnectionsAttributeMock).get();

        // when
        Throwable ex = null;
        PipelineContinuationBehavior result = null;
        try {
            result = handler.doChannelRead(ctxMock, msg);
        }
        catch (Throwable t) {
            ex = t;
        }

        // then
        if (expectTooManyOpenChannelsException) {
            assertThat(ex).isInstanceOf(TooManyOpenChannelsException.class);
        }
        else {
            assertThat(ex).isNull();
            assertThat(result).isEqualTo(CONTINUE);
        }
    }
}