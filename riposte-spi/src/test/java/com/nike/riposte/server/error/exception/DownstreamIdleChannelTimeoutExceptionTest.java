package com.nike.riposte.server.error.exception;

import io.netty.channel.Channel;
import org.junit.Test;

import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link DownstreamIdleChannelTimeoutException}
 *
 * @author Nic Munroe
 */
public class DownstreamIdleChannelTimeoutExceptionTest {

    @Test
    public void should_honor_constructor_params() {
        //given
        long timeoutValue = 42;
        Channel channelMock = mock(Channel.class);
        String uuid = UUID.randomUUID().toString();
        doReturn(uuid).when(channelMock).toString();

        //when
        DownstreamIdleChannelTimeoutException ex = new DownstreamIdleChannelTimeoutException(timeoutValue, channelMock);

        //then
        assertThat(ex.timeoutValueMillis, is(timeoutValue));
        assertThat(ex.channelId, is(uuid));
    }

}