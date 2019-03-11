package com.nike.riposte.server.http.impl;

import com.nike.fastbreak.CircuitBreaker;
import com.nike.riposte.server.http.ProxyRouterEndpoint.DownstreamRequestFirstChunkInfo;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.util.Matcher;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link SimpleProxyRouterEndpoint}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class SimpleProxyRouterEndpointTest {

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true",
        "false  |   false",
    }, splitBy = "\\|")
    @Test
    public void kitchen_sink_constructor_sets_fields_as_expected(boolean isHttps, boolean disableCircuitBreaker) {
        // given
        Matcher matcherMock = mock(Matcher.class);
        String host = "fooHost";
        int port = 4242;
        String path = "/barPath";
        Optional<CircuitBreaker<HttpResponse>> circuitBreakerOpt = Optional.of(mock(CircuitBreaker.class));

        // when
        SimpleProxyRouterEndpoint instance = new SimpleProxyRouterEndpoint(
            matcherMock, host, port, path, isHttps, circuitBreakerOpt, disableCircuitBreaker
        );

        // then
        assertThat(instance.incomingRequestMatcher).isSameAs(instance.requestMatcher()).isSameAs(matcherMock);
        assertThat(instance.downstreamDestinationHost).isEqualTo(host);
        assertThat(instance.downstreamDestinationPort).isEqualTo(port);
        assertThat(instance.downstreamDestinationUriPath).isEqualTo(path);
        assertThat(instance.isDownstreamCallHttps).isEqualTo(isHttps);
        assertThat(instance.customCircuitBreaker).isEqualTo(circuitBreakerOpt);
        assertThat(instance.disableCircuitBreaker).isEqualTo(disableCircuitBreaker);
    }

    private enum ConstructorNullArgScenario {
        NULL_MATCHER(null, "fooHost", "/fooPath", "incomingRequestMatcher cannot be null."),
        NULL_HOST(mock(Matcher.class), null, "/fooPath", "downstreamDestinationHost cannot be null."),
        NULL_PATH(mock(Matcher.class), "fooHost", null, "downstreamDestinationUriPath cannot be null.");

        public final Matcher matcher;
        public final String host;
        public final String path;
        public final String expectedExceptionMessage;

        ConstructorNullArgScenario(Matcher matcher, String host, String path, String expectedExceptionMessage) {
            this.matcher = matcher;
            this.host = host;
            this.path = path;
            this.expectedExceptionMessage = expectedExceptionMessage;
        }
    }

    @DataProvider(value = {
        "NULL_MATCHER",
        "NULL_HOST",
        "NULL_PATH",
    }, splitBy = "\\|")
    @Test
    public void kitchen_sink_constructor_throws_IllegalArgumentException_when_passed_invalid_null_arg(
        ConstructorNullArgScenario scenario
    ) {
        // when
        Throwable ex = catchThrowable(() -> new SimpleProxyRouterEndpoint(
            scenario.matcher, scenario.host, 4242, scenario.path, false, Optional.empty(), false
        ));

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(scenario.expectedExceptionMessage);
    }

    @Test
    public void kitchen_sink_constructor_uses_empty_optional_when_passed_null_circuit_breaker_option() {
        // when
        SimpleProxyRouterEndpoint instance = new SimpleProxyRouterEndpoint(
            mock(Matcher.class), "fooHost", 4242, "/fooPath", false, null, false
        );

        // then
        assertThat(instance.customCircuitBreaker)
            .isNotNull()
            .isEmpty();
    }

    @Test
    public void getDownstreamRequestFirstChunkInfo_returns_exceptionally_completed_future_if_passed_request_with_null_method() {
        // given
        SimpleProxyRouterEndpoint instance = new SimpleProxyRouterEndpoint(
            mock(Matcher.class), "fooHost", 4242, "/barPath", false
        );
        RequestInfo<?> requestMock = mock(RequestInfo.class);
        doReturn(null).when(requestMock).getMethod();

        // when
        CompletableFuture<DownstreamRequestFirstChunkInfo> result = instance.getDownstreamRequestFirstChunkInfo(
            requestMock, mock(Executor.class), mock(ChannelHandlerContext.class)
        );

        // then
        assertThat(result).isCompletedExceptionally();
        Throwable ex = catchThrowable(result::get);
        assertThat(ex).isInstanceOf(ExecutionException.class);
        assertThat(ex.getCause())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Received a request with null request.getMethod(). This should never happen.");
    }

}