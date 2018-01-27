package com.nike.riposte.server.http;

import com.nike.riposte.util.Matcher;
import io.netty.channel.ChannelHandlerContext;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

public class ProxyRouterEndpointTest {

    ProxyRouterEndpoint defaultImpl;

    @Before
    public void setup() {
        defaultImpl = new ProxyRouterEndpoint() {
            @Override
            public CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(RequestInfo<?> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
                return null;
            }

            @Override
            public Matcher requestMatcher() {
                return null;
            }
        };
    }

    @Test
    public void isRequireRequestContent_returnsFalse() {
        assertThat(defaultImpl.isRequireRequestContent()).isFalse();
    }

    @Test
    public void isValidateRequestContent_returnsFalse() {
        assertThat(defaultImpl.isValidateRequestContent(null)).isFalse();
    }

    @Test
    public void requestContentType_returnsNull() {
        assertThat(defaultImpl.requestContentType()).isNull();
    }
}
