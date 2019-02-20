package com.nike.riposte.server.http;

import com.nike.riposte.server.http.ProxyRouterEndpoint.DownstreamRequestFirstChunkInfo;
import com.nike.riposte.util.Matcher;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

@RunWith(DataProviderRunner.class)
public class ProxyRouterEndpointTest {

    ProxyRouterEndpoint defaultImpl;

    @Before
    public void setup() {
        defaultImpl = new ProxyRouterEndpoint() {
            @Override
            public @NotNull CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(
                @NotNull RequestInfo<?> request,
                @NotNull Executor longRunningTaskExecutor,
                @NotNull ChannelHandlerContext ctx
            ) {
                return null;
            }

            @Override
            public @NotNull Matcher requestMatcher() {
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
    public void downstreamRequestFirstChunkInfo_constructor_throws_IllegalArgumentException_if_passed_null_host() {
        // when
        Throwable ex = catchThrowable(
            () -> new DownstreamRequestFirstChunkInfo(null, 8080, true, mock(HttpRequest.class))
        );

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("host cannot be null.");
    }

    @Test
    public void downstreamRequestFirstChunkInfo_constructor_throws_IllegalArgumentException_if_passed_null_firstChunk() {
        // when
        Throwable ex = catchThrowable(
            () -> new DownstreamRequestFirstChunkInfo("localhost", 8080, true, null)
        );

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("firstChunk cannot be null.");
    }

    @Test
    public void downstreamRequestFirstChunkInfo_defaults_to_empty_optional_if_passed_null_customCircuitBreaker_optional() {
        // when
        DownstreamRequestFirstChunkInfo downstreamRequestFirstChunkInfo = new DownstreamRequestFirstChunkInfo(
            "localhost", 8080, true, mock(HttpRequest.class), null, false
        );

        // then
        assertThat(downstreamRequestFirstChunkInfo.customCircuitBreaker)
            .isNotNull()
            .isEmpty();
    }

    @Test
    @DataProvider(value = {
            "true",
            "false"
    }, splitBy = "\\|")
    public void downstreamRequestFirstChunkInfo_allowsOverrideOfRelaxHttpsValidationFlag(boolean flagValue) {
        // when
        DownstreamRequestFirstChunkInfo downstreamRequestFirstChunkInfo =
                new DownstreamRequestFirstChunkInfo("localhost", 8080, true, mock(HttpRequest.class))
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
                new DownstreamRequestFirstChunkInfo("localhost", 8080, true, mock(HttpRequest.class))
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
                new DownstreamRequestFirstChunkInfo("localhost", 8080, true, mock(HttpRequest.class))
                        .withPerformSubSpanAroundDownstreamCall(flagValue);

        // then
        assertThat(downstreamRequestFirstChunkInfo.performSubSpanAroundDownstreamCall).isEqualTo(flagValue);
    }

    public void downstreamRequestFirstChunkInfo_throws_IllegalArgumentException_if_constructed_with_null_firstChunk() {
        // when
        Throwable ex = catchThrowable(
            () -> new DownstreamRequestFirstChunkInfo("localhost", 8080, true, null)
        );

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("firstChunk cannot be null.");
    }

    public void downstreamRequestFirstChunkInfo_throws_IllegalArgumentException_if_constructed_with_null_host() {
        // when
        Throwable ex = catchThrowable(
            () -> new DownstreamRequestFirstChunkInfo(null, 8080, true, mock(HttpRequest.class))
        );

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("host cannot be null.");
    }

    public void downstreamRequestFirstChunkInfo_uses_empty_option_if_constructed_with_null_circuit_breaker() {
        // when
        DownstreamRequestFirstChunkInfo instance = new DownstreamRequestFirstChunkInfo(
            "localhost", 8080, true, mock(HttpRequest.class), null, false
        );

        // then
        assertThat(instance.customCircuitBreaker)
            .isNotNull()
            .isEmpty();
    }
}
