package com.nike.riposte.server.error.exception;

import org.junit.Test;

import java.util.UUID;

import io.netty.channel.Channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link DownstreamChannelClosedUnexpectedlyException}.
 *
 * @author Nic Munroe
 */
public class DownstreamChannelClosedUnexpectedlyExceptionTest {

    @Test
    public void constructor_works_with_valid_channel() {
        // given
        Channel channelMock = mock(Channel.class);
        String channelToStringVal = UUID.randomUUID().toString();
        doReturn(channelToStringVal).when(channelMock).toString();

        // when
        DownstreamChannelClosedUnexpectedlyException ex = new DownstreamChannelClosedUnexpectedlyException(channelMock);

        // then
        assertThat(ex.channelId).isEqualTo(channelToStringVal);
    }

    @Test
    public void constructor_works_with_null_channel() {
        // when
        DownstreamChannelClosedUnexpectedlyException ex = new DownstreamChannelClosedUnexpectedlyException(null);

        // then
        assertThat(ex.channelId).isEqualTo("null");
    }

}