package com.nike.riposte.server.http;

import com.nike.riposte.server.http.ProxyRouterEndpoint.DownstreamRequestFirstChunkInfo;
import com.nike.riposte.util.Matcher;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@RunWith(DataProviderRunner.class)
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

    @Test
    public void downstreamRequestFirstChunkInfo_constructorSetsValuesAsExpected() {
        // given
        HttpRequest firstChunk = mock(HttpRequest.class);

        // when
        DownstreamRequestFirstChunkInfo downstreamRequestFirstChunkInfo = new DownstreamRequestFirstChunkInfo("localhost", 8080, true, firstChunk);

        // then
        assertThat(downstreamRequestFirstChunkInfo.host).isEqualTo("localhost");
        assertThat(downstreamRequestFirstChunkInfo.port).isEqualTo(8080);
        assertThat(downstreamRequestFirstChunkInfo.isHttps).isTrue();
        assertThat(downstreamRequestFirstChunkInfo.relaxedHttpsValidation).isFalse();
        assertThat(downstreamRequestFirstChunkInfo.firstChunk).isEqualTo(firstChunk);
        assertThat(downstreamRequestFirstChunkInfo.addTracingHeadersToDownstreamCall).isTrue();
        assertThat(downstreamRequestFirstChunkInfo.performSubSpanAroundDownstreamCall).isTrue();
    }

    @Test
    @DataProvider(value = {
            "true",
            "false"
    }, splitBy = "\\|")
    public void downstreamRequestFirstChunkInfo_allowsOverrideOfRelaxHttpsValidationFlag(boolean flagValue) {
        // when
        DownstreamRequestFirstChunkInfo downstreamRequestFirstChunkInfo =
                new DownstreamRequestFirstChunkInfo("localhost", 8080, true, null)
                        .withRelaxedHttpsValidation(flagValue);

        // then
        assertThat(downstreamRequestFirstChunkInfo.relaxedHttpsValidation).isEqualTo(flagValue);
    }

    @Test
    @DataProvider(value = {
            "true",
            "false"
    }, splitBy = "\\|")
    public void downstreamRequestFirstChunkInfo_allowsOverrideOfAddTracingFlag(boolean flagValue) {
        // when
        DownstreamRequestFirstChunkInfo downstreamRequestFirstChunkInfo =
                new DownstreamRequestFirstChunkInfo("localhost", 8080, true, null)
                .withAddTracingHeadersToDownstreamCall(flagValue);

        // then
        assertThat(downstreamRequestFirstChunkInfo.addTracingHeadersToDownstreamCall).isEqualTo(flagValue);
    }

    @Test
    @DataProvider(value = {
            "true",
            "false"
    }, splitBy = "\\|")
    public void downstreamRequestFirstChunkInfo_allowsOverrideOfPerformSubspanOnDownstreamCall(boolean flagValue) {
        // when
        DownstreamRequestFirstChunkInfo downstreamRequestFirstChunkInfo =
                new DownstreamRequestFirstChunkInfo("localhost", 8080, true, null)
                        .withPerformSubSpanAroundDownstreamCall(flagValue);

        // then
        assertThat(downstreamRequestFirstChunkInfo.performSubSpanAroundDownstreamCall).isEqualTo(flagValue);
    }
}
