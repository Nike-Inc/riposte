package com.nike.riposte.server.config.distributedtracing;

import com.nike.wingtips.Span;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link DefaultRiposteDistributedTracingConfigImpl}.
 *
 * @author Nic Munroe
 */
public class DefaultRiposteDistributedTracingConfigImplTest {

    @Test
    public void getDefaultInstance_returns_DEFAULT_INSTANCE() {
        // when
        DefaultRiposteDistributedTracingConfigImpl instance =
            DefaultRiposteDistributedTracingConfigImpl.getDefaultInstance();

        // then
        assertThat(instance)
            .isSameAs(DefaultRiposteDistributedTracingConfigImpl.DEFAULT_INSTANCE);
        assertThat(instance.serverSpanNamingAndTaggingStrategy)
            .isSameAs(DefaultRiposteServerSpanNamingAndTaggingStrategy.getDefaultInstance())
            .isSameAs(instance.getServerSpanNamingAndTaggingStrategy());
        assertThat(instance.getSpanClassType()).isEqualTo(Span.class);
    }

    @Test
    public void default_constructor_creates_instance_that_uses_default_strategies() {
        // when
        DefaultRiposteDistributedTracingConfigImpl instance = new DefaultRiposteDistributedTracingConfigImpl();

        // then
        assertThat(instance.serverSpanNamingAndTaggingStrategy)
            .isSameAs(DefaultRiposteServerSpanNamingAndTaggingStrategy.getDefaultInstance())
            .isSameAs(instance.getServerSpanNamingAndTaggingStrategy());
        assertThat(instance.proxyRouterSpanNamingAndTaggingStrategy)
            .isSameAs(DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy.getDefaultInstance())
            .isSameAs(instance.getProxyRouterSpanNamingAndTaggingStrategy());
        assertThat(instance.getSpanClassType()).isEqualTo(Span.class);
    }

    @Test
    public void alternate_constructor_creates_instance_with_specified_strategies() {
        // given
        ServerSpanNamingAndTaggingStrategy<Span> serverStrategyMock = mock(ServerSpanNamingAndTaggingStrategy.class);
        ProxyRouterSpanNamingAndTaggingStrategy<Span> proxyStrategyMock =
            mock(ProxyRouterSpanNamingAndTaggingStrategy.class);

        // when
        DefaultRiposteDistributedTracingConfigImpl instance = new DefaultRiposteDistributedTracingConfigImpl(
            serverStrategyMock, proxyStrategyMock
        );

        // then
        assertThat(instance.serverSpanNamingAndTaggingStrategy)
            .isSameAs(serverStrategyMock)
            .isSameAs(instance.getServerSpanNamingAndTaggingStrategy());
        assertThat(instance.proxyRouterSpanNamingAndTaggingStrategy)
            .isSameAs(proxyStrategyMock)
            .isSameAs(instance.getProxyRouterSpanNamingAndTaggingStrategy());
        assertThat(instance.getSpanClassType()).isEqualTo(Span.class);
    }

    @Test
    public void alternate_constructor_throws_IllegalArgumentException_if_passed_null_server_strategy() {
        // when
        Throwable ex = catchThrowable(
            () -> new DefaultRiposteDistributedTracingConfigImpl(
                null, mock(ProxyRouterSpanNamingAndTaggingStrategy.class)
            )
        );

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("serverSpanNamingAndTaggingStrategy cannot be null");
    }

    @Test
    public void alternate_constructor_throws_IllegalArgumentException_if_passed_null_proxy_strategy() {
        // when
        Throwable ex = catchThrowable(
            () -> new DefaultRiposteDistributedTracingConfigImpl(
                mock(ServerSpanNamingAndTaggingStrategy.class), null
            )
        );

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("proxyRouterSpanNamingAndTaggingStrategy cannot be null");
    }

}