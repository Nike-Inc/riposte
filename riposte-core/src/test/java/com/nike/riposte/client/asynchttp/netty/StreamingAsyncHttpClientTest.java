package com.nike.riposte.client.asynchttp.netty;

import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.ObjectHolder;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.StreamingChannel;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.HttpContent;
import io.netty.util.Attribute;

import static com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.CHANNEL_IS_BROKEN_ATTR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link StreamingAsyncHttpClient}.
 *
 * @author Nic Munroe
 */
public class StreamingAsyncHttpClientTest {

    private Channel channelMock;
    private ChannelPool channelPoolMock;
    private ObjectHolder<Boolean> callActiveHolder;
    private StreamingChannel streamingChannel;
    private HttpContent contentChunkMock;
    private ChannelFuture streamChunkChannelFutureMock;

    private Attribute<Boolean> channelIsBrokenAttrMock;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        channelPoolMock = mock(ChannelPool.class);

        contentChunkMock = mock(HttpContent.class);

        callActiveHolder = new ObjectHolder<>();
        callActiveHolder.heldObject = true;

        streamingChannel = new StreamingChannel(channelMock, channelPoolMock, callActiveHolder);

        streamChunkChannelFutureMock = mock(ChannelFuture.class);

        doReturn(streamChunkChannelFutureMock).when(channelMock).writeAndFlush(contentChunkMock);

        channelIsBrokenAttrMock = mock(Attribute.class);
        doReturn(channelIsBrokenAttrMock).when(channelMock).attr(CHANNEL_IS_BROKEN_ATTR);
    }

    @Test
    public void StreamingChannel_streamChunk_works_as_expected_for_normal_case() {
        // when
        ChannelFuture result = streamingChannel.streamChunk(contentChunkMock);

        // then
        verify(channelMock).writeAndFlush(contentChunkMock);
        assertThat(result).isSameAs(streamChunkChannelFutureMock);
    }

    @Test
    public void StreamingChannel_streamChunk_works_as_expected_when_closeChannelDueToUnrecoverableError_was_called_previously() {
        // given
        ChannelFuture failedChannelFutureMock = mock(ChannelFuture.class);
        doReturn(failedChannelFutureMock).when(channelMock).newFailedFuture(any(Throwable.class));
        streamingChannel.closeChannelDueToUnrecoverableError();

        // when
        ChannelFuture result = streamingChannel.streamChunk(contentChunkMock);

        // then
        verify(channelMock, never()).writeAndFlush(any(Object.class));
        assertThat(result).isSameAs(failedChannelFutureMock);
        verify(contentChunkMock).release();

        ArgumentCaptor<Throwable> exceptionCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(channelMock).newFailedFuture(exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue())
            .hasMessage("Unable to stream chunks downstream - "
                        + "the channel was closed previously due to an unrecoverable error"
            );
    }

    @Test
    public void StreamingChannel_closeChannelDueToUnrecoverableError_works_as_expected() {
        // given
        assertThat(streamingChannel.channelClosedDueToUnrecoverableError).isFalse();
        assertThat(streamingChannel.callActiveHolder.heldObject).isTrue();

        // when
        streamingChannel.closeChannelDueToUnrecoverableError();

        // then
        assertThat(streamingChannel.channelClosedDueToUnrecoverableError).isTrue();
        verify(channelIsBrokenAttrMock).set(true);
        verifyChannelReleasedBackToPool(streamingChannel.callActiveHolder, channelPoolMock, channelMock);
        verify(channelMock).close();
    }

    private void verifyChannelReleasedBackToPool(ObjectHolder<Boolean> callActiveHolder,
                                                 ChannelPool theChannelPoolMock,
                                                 Channel theChannelMock) {
        assertThat(callActiveHolder.heldObject).isFalse();
        verify(theChannelPoolMock).release(theChannelMock);
    }

}