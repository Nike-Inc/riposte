package com.nike.riposte.client.asynchttp.netty;

import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.ObjectHolder;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.StreamingChannel;
import com.nike.wingtips.Span;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Deque;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import static com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.CHANNEL_IS_BROKEN_ATTR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link StreamingAsyncHttpClient}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class StreamingAsyncHttpClientTest {

    private Channel channelMock;
    private ChannelPool channelPoolMock;
    private EventLoop eventLoopMock;
    private ObjectHolder<Boolean> callActiveHolder;
    private ObjectHolder<Boolean> downstreamLastChunkSentHolder;
    private StreamingChannel streamingChannelSpy;
    private HttpContent contentChunkMock;
    private ChannelFuture writeAndFlushChannelFutureMock;

    private Attribute<Boolean> channelIsBrokenAttrMock;

    private ChannelPromise streamChunkChannelPromiseMock;

    private ChannelFuture failedFutureMock;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        channelPoolMock = mock(ChannelPool.class);
        eventLoopMock = mock(EventLoop.class);

        contentChunkMock = mock(HttpContent.class);

        callActiveHolder = new ObjectHolder<>();
        callActiveHolder.heldObject = true;

        downstreamLastChunkSentHolder = new ObjectHolder<>();
        downstreamLastChunkSentHolder.heldObject = false;

        streamingChannelSpy = spy(new StreamingChannel(channelMock, channelPoolMock, callActiveHolder,
                                                       downstreamLastChunkSentHolder, null, null));

        writeAndFlushChannelFutureMock = mock(ChannelFuture.class);

        doReturn(eventLoopMock).when(channelMock).eventLoop();

        doReturn(writeAndFlushChannelFutureMock).when(channelMock).writeAndFlush(contentChunkMock);

        channelIsBrokenAttrMock = mock(Attribute.class);
        doReturn(channelIsBrokenAttrMock).when(channelMock).attr(CHANNEL_IS_BROKEN_ATTR);

        streamChunkChannelPromiseMock = mock(ChannelPromise.class);
        doReturn(streamChunkChannelPromiseMock).when(channelMock).newPromise();

        failedFutureMock = mock(ChannelFuture.class);
        doReturn(failedFutureMock).when(channelMock).newFailedFuture(any(Throwable.class));
    }

    @Test
    public void constructor_sets_fields_as_expected() {
        // given
        Deque<Span> spanStackMock = mock(Deque.class);
        Map<String, String> mdcInfoMock = mock(Map.class);

        // when
        StreamingChannel sc = new StreamingChannel(
            channelMock, channelPoolMock, callActiveHolder, downstreamLastChunkSentHolder, spanStackMock, mdcInfoMock
        );

        // then
        assertThat(sc.channel).isSameAs(channelMock);
        assertThat(sc.getChannel()).isSameAs(sc.channel);
        assertThat(sc.pool).isSameAs(channelPoolMock);
        assertThat(sc.callActiveHolder).isSameAs(callActiveHolder);
        assertThat(sc.downstreamLastChunkSentHolder).isSameAs(downstreamLastChunkSentHolder);
        assertThat(sc.distributedTracingSpanStack).isSameAs(spanStackMock);
        assertThat(sc.distributedTracingMdcInfo).isSameAs(mdcInfoMock);
    }

    @Test
    public void StreamingChannel_streamChunk_sets_up_task_in_event_loop_to_call_doStreamChunk_and_adds_listener_to_complete_promise()
        throws Exception {
        // given
        ChannelFuture doStreamChunkFutureMock = mock(ChannelFuture.class);
        doReturn(doStreamChunkFutureMock).when(streamingChannelSpy).doStreamChunk(any(HttpContent.class));

        // when
        ChannelFuture result = streamingChannelSpy.streamChunk(contentChunkMock);

        // then
        assertThat(result).isSameAs(streamChunkChannelPromiseMock);
        verifyZeroInteractions(streamChunkChannelPromiseMock); // not yet completed
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(eventLoopMock).execute(taskCaptor.capture());
        Runnable task = taskCaptor.getValue();

        // and given
        verify(streamingChannelSpy, never()).doStreamChunk(any(HttpContent.class)); // not yet called

        // when
        task.run();

        // then
        verify(streamingChannelSpy).doStreamChunk(contentChunkMock);
        ArgumentCaptor<GenericFutureListener> listenerCaptor = ArgumentCaptor.forClass(GenericFutureListener.class);
        verify(doStreamChunkFutureMock).addListener(listenerCaptor.capture());
        GenericFutureListener listener = listenerCaptor.getValue();
        assertThat(listener).isNotNull();

        // and when
        listener.operationComplete(getFutureForCase(true, false, null));

        // then
        verify(streamChunkChannelPromiseMock).cancel(true);
        verifyNoMoreInteractions(streamChunkChannelPromiseMock);

        // and when
        listener.operationComplete(getFutureForCase(false, true, null));

        // then
        verify(streamChunkChannelPromiseMock).setSuccess();
        verifyNoMoreInteractions(streamChunkChannelPromiseMock);

        // and when
        Throwable normalFutureFailure = new RuntimeException("normal future failure");
        listener.operationComplete(getFutureForCase(false, false, normalFutureFailure));

        // then
        verify(streamChunkChannelPromiseMock).setFailure(normalFutureFailure);
        verifyNoMoreInteractions(streamChunkChannelPromiseMock);

        // and when
        reset(streamChunkChannelPromiseMock);
        listener.operationComplete(getFutureForCase(false, false, null));

        // then
        ArgumentCaptor<Throwable> throwableCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(streamChunkChannelPromiseMock).setFailure(throwableCaptor.capture());
        assertThat(throwableCaptor.getValue()).hasMessage("Received ChannelFuture that was in an impossible state");
        verifyNoMoreInteractions(streamChunkChannelPromiseMock);
    }

    private Future getFutureForCase(boolean isCanceled, boolean isSuccess, Throwable failureCause) {
        Future futureMock = mock(Future.class);
        doReturn(isCanceled).when(futureMock).isCancelled();
        doReturn(isSuccess).when(futureMock).isSuccess();
        doReturn(failureCause).when(futureMock).cause();
        return futureMock;
    }

    @Test
    public void StreamingChannel_streamChunk_fails_promise_with_unexpected_exception() {
        // given
        Throwable crazyEx = new RuntimeException("kaboom");
        doThrow(crazyEx).when(eventLoopMock).execute(any(Runnable.class));

        // when
        ChannelFuture result = streamingChannelSpy.streamChunk(contentChunkMock);

        // then
        verifyFailedChannelFuture(result, "StreamingChannel.streamChunk() threw an exception", crazyEx);
    }

    private void verifyFailedChannelFuture(ChannelFuture result,
                                           String expectedExceptionMessagePrefix,
                                           Throwable expectedExCause) {
        assertThat(result).isSameAs(failedFutureMock);
        ArgumentCaptor<Throwable> exCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(channelMock).newFailedFuture(exCaptor.capture());
        Throwable exThatFailedTheFuture = exCaptor.getValue();
        assertThat(exThatFailedTheFuture).hasMessageStartingWith(expectedExceptionMessagePrefix);
        if (expectedExCause != null)
            assertThat(exThatFailedTheFuture).hasCause(expectedExCause);
    }

    @Test
    public void StreamingChannel_doStreamChunk_works_as_expected_when_last_chunk_already_sent_downstream_and_incoming_chunk_is_empty_last_chunk() {
        // given
        streamingChannelSpy.downstreamLastChunkSentHolder.heldObject = true;

        LastHttpContent contentChunkMock = mock(LastHttpContent.class);
        ByteBuf contentByteBufMock = mock(ByteBuf.class);
        doReturn(contentByteBufMock).when(contentChunkMock).content();
        doReturn(0).when(contentByteBufMock).readableBytes();

        ChannelFuture successFutureMock = mock(ChannelFuture.class);
        doReturn(successFutureMock).when(channelMock).newSucceededFuture();

        // when
        ChannelFuture result = streamingChannelSpy.doStreamChunk(contentChunkMock);

        // then
        verify(channelMock, never()).writeAndFlush(any(Object.class));
        verify(contentChunkMock).release();
        verify(channelMock).newSucceededFuture();
        assertThat(result).isSameAs(successFutureMock);
    }

    @DataProvider(value = {
        "false  |   0",
        "true   |   42"
    }, splitBy = "\\|")
    @Test
    public void StreamingChannel_doStreamChunk_works_as_expected_when_last_chunk_already_sent_downstream_and_incoming_chunk_does_not_match_requirements(
        boolean chunkIsLastChunk, int readableBytes
    ) {
        // given
        streamingChannelSpy.downstreamLastChunkSentHolder.heldObject = true;
        streamingChannelSpy.callActiveHolder.heldObject = true;
        streamingChannelSpy.channelClosedDueToUnrecoverableError = false;

        HttpContent contentChunkMock = (chunkIsLastChunk) ? mock(LastHttpContent.class) : mock(HttpContent.class);
        ByteBuf contentByteBufMock = mock(ByteBuf.class);
        doReturn(contentByteBufMock).when(contentChunkMock).content();
        doReturn(readableBytes).when(contentByteBufMock).readableBytes();

        doReturn(writeAndFlushChannelFutureMock).when(channelMock).writeAndFlush(contentChunkMock);

        // when
        ChannelFuture result = streamingChannelSpy.doStreamChunk(contentChunkMock);

        // then
        verify(channelMock).writeAndFlush(contentChunkMock);
        assertThat(result).isSameAs(writeAndFlushChannelFutureMock);
    }

    @Test
    public void StreamingChannel_doStreamChunk_works_as_expected_when_downstream_call_is_not_active() {
        // given
        streamingChannelSpy.callActiveHolder.heldObject = false;

        // when
        ChannelFuture result = streamingChannelSpy.doStreamChunk(contentChunkMock);

        // then
        verify(channelMock, never()).writeAndFlush(any(Object.class));
        verify(contentChunkMock).release();

        verifyFailedChannelFuture(
            result, "Unable to stream chunk - downstream call is no longer active.", null
        );
    }

    @Test
    public void StreamingChannel_doStreamChunk_works_as_expected_when_closeChannelDueToUnrecoverableError_was_called_previously() {
        // given
        streamingChannelSpy.channelClosedDueToUnrecoverableError = true;

        // when
        ChannelFuture result = streamingChannelSpy.doStreamChunk(contentChunkMock);

        // then
        verify(channelMock, never()).writeAndFlush(any(Object.class));
        verify(contentChunkMock).release();

        verifyFailedChannelFuture(
            result, "Unable to stream chunks downstream - the channel was closed previously due to an unrecoverable error", null
        );
    }

    @Test
    public void StreamingChannel_doStreamChunk_works_as_expected_when_crazy_exception_is_thrown() {
        // given
        Throwable crazyEx = new RuntimeException("kaboom");
        doThrow(crazyEx).when(channelMock).writeAndFlush(any(Object.class));

        // when
        ChannelFuture result = streamingChannelSpy.doStreamChunk(contentChunkMock);

        // then
        verify(channelMock).writeAndFlush(any(Object.class));
        verify(contentChunkMock, never()).release();

        verifyFailedChannelFuture(
            result, "StreamingChannel.doStreamChunk() threw an exception", crazyEx
        );
    }

    @Test
    public void StreamingChannel_closeChannelDueToUnrecoverableError_calls_the_do_method_and_sets_field_to_true_when_not_closed_and_call_active() {
        // given
        Throwable unrecoverableError = new RuntimeException("kaboom");
        streamingChannelSpy.channelClosedDueToUnrecoverableError = false;
        streamingChannelSpy.callActiveHolder.heldObject = true;

        // when
        streamingChannelSpy.closeChannelDueToUnrecoverableError(unrecoverableError);

        // then
        assertThat(streamingChannelSpy.channelClosedDueToUnrecoverableError).isTrue();
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(eventLoopMock).execute(taskCaptor.capture());
        Runnable task = taskCaptor.getValue();
        assertThat(task).isNotNull();

        // and given
        verify(streamingChannelSpy, never()).doCloseChannelDueToUnrecoverableError(any(Throwable.class));

        // when
        task.run();

        // then
        verify(streamingChannelSpy).doCloseChannelDueToUnrecoverableError(unrecoverableError);
    }

    @DataProvider(value = {
        "true   |   true",
        "false  |   false",
        "true   |   false"
    }, splitBy = "\\|")
    @Test
    public void StreamingChannel_closeChannelDueToUnrecoverableError_sets_field_to_true_but_otherwise_does_nothing_if_already_closed_or_call_inactive(
        boolean alreadyClosed, boolean callActive
    ) {
        // given
        Throwable unrecoverableError = new RuntimeException("kaboom");
        streamingChannelSpy.channelClosedDueToUnrecoverableError = alreadyClosed;
        streamingChannelSpy.callActiveHolder.heldObject = callActive;

        // when
        streamingChannelSpy.closeChannelDueToUnrecoverableError(unrecoverableError);

        // then
        assertThat(streamingChannelSpy.channelClosedDueToUnrecoverableError).isTrue();
        verifyZeroInteractions(channelMock);
    }

    @Test
    public void StreamingChannel_closeChannelDueToUnrecoverableError_sets_field_to_true_even_if_crazy_exception_occurs() {
        // given
        streamingChannelSpy.channelClosedDueToUnrecoverableError = false;
        streamingChannelSpy.callActiveHolder.heldObject = true;
        Throwable crazyEx = new RuntimeException("kaboom");
        doThrow(crazyEx).when(channelMock).eventLoop();

        // when
        Throwable caughtEx = catchThrowable(
            () -> streamingChannelSpy.closeChannelDueToUnrecoverableError(new RuntimeException("some other error"))
        );

        // then
        assertThat(caughtEx).isSameAs(crazyEx);
        assertThat(streamingChannelSpy.channelClosedDueToUnrecoverableError).isTrue();
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void StreamingChannel_doCloseChannelDueToUnrecoverableError_works_as_expected(boolean callActive) {
        // given
        streamingChannelSpy.callActiveHolder.heldObject = callActive;
        Throwable unrecoverableError = new RuntimeException("kaboom");

        // when
        streamingChannelSpy.doCloseChannelDueToUnrecoverableError(unrecoverableError);

        // then
        if (callActive) {
            verify(channelIsBrokenAttrMock).set(true);
            verifyChannelReleasedBackToPool(streamingChannelSpy.callActiveHolder, channelPoolMock, channelMock);
            verify(channelMock).close();
        }
        else {
            verify(channelIsBrokenAttrMock, never()).set(anyBoolean());
            verify(channelMock, never()).close();
        }

    }

    private void verifyChannelReleasedBackToPool(ObjectHolder<Boolean> callActiveHolder,
                                                 ChannelPool theChannelPoolMock,
                                                 Channel theChannelMock) {
        assertThat(callActiveHolder.heldObject).isFalse();
        verify(theChannelPoolMock).release(theChannelMock);
    }

}