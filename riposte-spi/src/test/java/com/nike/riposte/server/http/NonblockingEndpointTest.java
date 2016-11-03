package com.nike.riposte.server.http;

import com.nike.riposte.util.Matcher;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link NonblockingEndpoint}.
 *
 * @author Nic Munroe
 */
public class NonblockingEndpointTest {

    @Test
    public void default_method_implementations_return_expected_values() {
        // given
        NonblockingEndpoint<?, ?> defaultImpl = new NonblockingEndpoint<Object, Object>() {
            @Override
            public CompletableFuture<ResponseInfo<Object>> execute(RequestInfo<Object> request,
                                                                   Executor longRunningTaskExecutor,
                                                                   ChannelHandlerContext ctx) {
                return null;
            }

            @Override
            public Matcher requestMatcher() {
                return null;
            }
        };

        // expect
        assertThat(
            defaultImpl.getCustomTimeoutExceptionCause(mock(RequestInfo.class), mock(ChannelHandlerContext.class)),
            nullValue()
        );
    }

}