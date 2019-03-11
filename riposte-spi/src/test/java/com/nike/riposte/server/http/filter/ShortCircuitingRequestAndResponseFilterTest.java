package com.nike.riposte.server.http.filter;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.util.Optional;

import io.netty.channel.ChannelHandlerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * Tests the default method implementations of the {@link ShortCircuitingRequestAndResponseFilter} interface.
 *
 * @author Nic Munroe
 */
public class ShortCircuitingRequestAndResponseFilterTest {

    @Test
    public void default_method_implementations_behave_as_expected() {
        // given
        ShortCircuitingRequestAndResponseFilter defaultImpl = new ShortCircuitingRequestAndResponseFilter() {
            @Override
            public <T> @Nullable ResponseInfo<T> filterResponse(
                @NotNull ResponseInfo<T> currentResponseInfo,
                @NotNull RequestInfo<?> requestInfo,
                @NotNull ChannelHandlerContext ctx
            ) {
                return null;
            }

            @Override
            public <T> @Nullable Pair<RequestInfo<T>, Optional<ResponseInfo<?>>> filterRequestFirstChunkWithOptionalShortCircuitResponse(
                @NotNull RequestInfo<T> currentRequestInfo,
                @NotNull ChannelHandlerContext ctx
            ) {
                return null;
            }

            @Override
            public <T> @Nullable Pair<RequestInfo<T>, Optional<ResponseInfo<?>>> filterRequestLastChunkWithOptionalShortCircuitResponse(
                @NotNull RequestInfo<T> currentRequestInfo,
                @NotNull ChannelHandlerContext ctx
            ) {
                return null;
            }
        };

        // expect
        assertThat(defaultImpl.isShortCircuitRequestFilter()).isTrue();
        Throwable firstChunkEx = catchThrowable(() -> defaultImpl.filterRequestFirstChunkNoPayload(
            mock(RequestInfo.class), mock(ChannelHandlerContext.class)
        ));
        assertThat(firstChunkEx)
            .isNotNull()
            .isInstanceOf(UnsupportedOperationException.class);
        Throwable lastChunkEx = catchThrowable(() -> defaultImpl.filterRequestLastChunkWithFullPayload(
            mock(RequestInfo.class), mock(ChannelHandlerContext.class)
        ));
        assertThat(lastChunkEx)
            .isNotNull()
            .isInstanceOf(UnsupportedOperationException.class);
    }

}