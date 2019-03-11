package com.nike.riposte.server.http.filter;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import io.netty.channel.ChannelHandlerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * Tests the default method implementations of the {@link RequestAndResponseFilter} interface.
 *
 * @author Nic Munroe
 */
public class RequestAndResponseFilterTest {

    @Test
    public void default_method_implementations_behave_as_expected() {
        // given
        RequestAndResponseFilter defaultImpl = new RequestAndResponseFilter() {
            @Override
            public <T> @Nullable RequestInfo<T> filterRequestFirstChunkNoPayload(
                @NotNull RequestInfo<T> currentRequestInfo, @NotNull ChannelHandlerContext ctx
            ) {
                return null;
            }

            @Override
            public <T> @Nullable RequestInfo<T> filterRequestLastChunkWithFullPayload(
                @NotNull RequestInfo<T> currentRequestInfo, @NotNull ChannelHandlerContext ctx
            ) {
                return null;
            }

            @Override
            public <T> @Nullable ResponseInfo<T> filterResponse(
                @NotNull ResponseInfo<T> currentResponseInfo,
                @NotNull RequestInfo<?> requestInfo,
                @NotNull ChannelHandlerContext ctx
            ) {
                return null;
            }
        };

        // expect
        assertThat(defaultImpl.isShortCircuitRequestFilter()).isFalse();
        Throwable firstChunkShortCircuitEx = catchThrowable(() -> defaultImpl.filterRequestFirstChunkWithOptionalShortCircuitResponse(
            mock(RequestInfo.class), mock(ChannelHandlerContext.class)
        ));
        assertThat(firstChunkShortCircuitEx)
            .isNotNull()
            .isInstanceOf(UnsupportedOperationException.class);
        Throwable lastChunkShortCircuitEx = catchThrowable(() -> defaultImpl.filterRequestLastChunkWithOptionalShortCircuitResponse(
            mock(RequestInfo.class), mock(ChannelHandlerContext.class)
        ));
        assertThat(lastChunkShortCircuitEx)
            .isNotNull()
            .isInstanceOf(UnsupportedOperationException.class);

        assertThat(defaultImpl.shouldExecuteBeforeSecurityValidation())
                .isTrue();
    }

}