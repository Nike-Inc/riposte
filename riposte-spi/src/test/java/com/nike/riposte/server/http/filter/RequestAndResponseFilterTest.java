package com.nike.riposte.server.http.filter;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import org.junit.Test;

import java.security.cert.CertificateException;

import javax.net.ssl.SSLException;

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
    public void default_method_implementations_behave_as_expected() throws CertificateException, SSLException {
        // given
        RequestAndResponseFilter defaultImpl = new RequestAndResponseFilter() {
            @Override
            public <T> RequestInfo<T> filterRequestFirstChunkNoPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
                return null;
            }

            @Override
            public <T> RequestInfo<T> filterRequestLastChunkWithFullPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
                return null;
            }

            @Override
            public <T> ResponseInfo<T> filterResponse(ResponseInfo<T> currentResponseInfo, RequestInfo<?> requestInfo, ChannelHandlerContext ctx) {
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
    }

}