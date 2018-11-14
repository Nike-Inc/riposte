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
    public void default_constructor_creates_instance_that_uses_default_DefaultRiposteServerSpanNamingAndTaggingStrategy() {
        // when
        DefaultRiposteDistributedTracingConfigImpl instance = new DefaultRiposteDistributedTracingConfigImpl();

        // then
        assertThat(instance.serverSpanNamingAndTaggingStrategy)
            .isSameAs(DefaultRiposteServerSpanNamingAndTaggingStrategy.getDefaultInstance())
            .isSameAs(instance.getServerSpanNamingAndTaggingStrategy());
        assertThat(instance.getSpanClassType()).isEqualTo(Span.class);
    }

    @Test
    public void alternate_constructor_creates_instance_with_specified_ServerSpanNamingAndTaggingStrategy() {
        // given
        ServerSpanNamingAndTaggingStrategy<Span> strategyMock = mock(ServerSpanNamingAndTaggingStrategy.class);
        // when
        DefaultRiposteDistributedTracingConfigImpl instance = new DefaultRiposteDistributedTracingConfigImpl(
            strategyMock
        );

        // then
        assertThat(instance.serverSpanNamingAndTaggingStrategy)
            .isSameAs(strategyMock)
            .isSameAs(instance.getServerSpanNamingAndTaggingStrategy());
        assertThat(instance.getSpanClassType()).isEqualTo(Span.class);
    }

    @Test
    public void alternate_constructor_throws_IllegalArgumentException_if_passed_null_strategy() {
        // when
        Throwable ex = catchThrowable(
            () -> new DefaultRiposteDistributedTracingConfigImpl(null)
        );

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("serverSpanNamingAndTaggingStrategy cannot be null");
    }

}