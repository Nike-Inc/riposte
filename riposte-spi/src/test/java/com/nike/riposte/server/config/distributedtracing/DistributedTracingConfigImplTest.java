package com.nike.riposte.server.config.distributedtracing;

import com.nike.wingtips.Span;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link DistributedTracingConfigImpl}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class DistributedTracingConfigImplTest {

    @Test
    public void constructor_sets_fields_as_expected() {
        // given
        ServerSpanNamingAndTaggingStrategy<Span> serverStrategyMock = mock(ServerSpanNamingAndTaggingStrategy.class);
        ProxyRouterSpanNamingAndTaggingStrategy<Span> proxyStrategyMock =
            mock(ProxyRouterSpanNamingAndTaggingStrategy.class);
        Class<Span> spanClassType = Span.class;

        // when
        DistributedTracingConfigImpl<Span> instance = new DistributedTracingConfigImpl<>(
            serverStrategyMock, proxyStrategyMock, spanClassType
        );

        // then
        assertThat(instance.serverSpanNamingAndTaggingStrategy).isSameAs(serverStrategyMock);
        assertThat(instance.proxyRouterSpanNamingAndTaggingStrategy).isSameAs(proxyStrategyMock);
        assertThat(instance.spanClassType).isSameAs(spanClassType);
    }

    private enum InvalidConstructorArgsScenario {
        NULL_SERVER_STRATEGY(
            null,
            mock(ProxyRouterSpanNamingAndTaggingStrategy.class),
            Span.class,
            "serverSpanNamingAndTaggingStrategy cannot be null"
        ),
        NULL_PROXY_STRATEGY(
            mock(ServerSpanNamingAndTaggingStrategy.class),
            null,
            Span.class,
            "proxyRouterSpanNamingAndTaggingStrategy cannot be null"
        ),
        NULL_SPAN_CLASS_TYPE(
            mock(ServerSpanNamingAndTaggingStrategy.class),
            mock(ProxyRouterSpanNamingAndTaggingStrategy.class),
            null,
            "spanClassType cannot be null"
        ),
        NON_WINGTIPS_SPAN_CLASS_TYPE(
            mock(ServerSpanNamingAndTaggingStrategy.class),
            mock(ProxyRouterSpanNamingAndTaggingStrategy.class),
            Object.class,
            "Riposte currently only supports Wingtips Spans"
        );

        public final ServerSpanNamingAndTaggingStrategy<?> serverStrategy;
        public final ProxyRouterSpanNamingAndTaggingStrategy<?> proxyStrategy;
        public final Class<?> spanClassType;
        public final String expectedExceptionMessage;

        InvalidConstructorArgsScenario(
            ServerSpanNamingAndTaggingStrategy<?> serverStrategy,
            ProxyRouterSpanNamingAndTaggingStrategy<?> proxyStrategy,
            Class<?> spanClassType,
            String expectedExceptionMessage
        ) {
            this.serverStrategy = serverStrategy;
            this.proxyStrategy = proxyStrategy;
            this.spanClassType = spanClassType;
            this.expectedExceptionMessage = expectedExceptionMessage;
        }
    }

    @DataProvider
    public static List<List<InvalidConstructorArgsScenario>> invalidConstructorArgsScenarioDataProvider() {
        return Arrays.stream(InvalidConstructorArgsScenario.values())
                     .map(Collections::singletonList)
                     .collect(Collectors.toList());
    }

    @UseDataProvider("invalidConstructorArgsScenarioDataProvider")
    @Test
    public void constructor_throws_illegal_argument_exception_for_invalid_args(
        InvalidConstructorArgsScenario scenario
    ) {
        // when
        @SuppressWarnings("unchecked")
        Throwable ex = catchThrowable(
            () -> new DistributedTracingConfigImpl(
                scenario.serverStrategy, scenario.proxyStrategy, scenario.spanClassType
            )
        );

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(scenario.expectedExceptionMessage);
    }

    @Test
    public void getter_methods_return_field_values() {
        // given
        ServerSpanNamingAndTaggingStrategy<Span> serverStrategyMock = mock(ServerSpanNamingAndTaggingStrategy.class);
        ProxyRouterSpanNamingAndTaggingStrategy<Span> proxyStrategyMock =
            mock(ProxyRouterSpanNamingAndTaggingStrategy.class);
        Class<Span> spanClassType = Span.class;
        DistributedTracingConfigImpl<Span> instance = new DistributedTracingConfigImpl<>(
            serverStrategyMock, proxyStrategyMock, spanClassType
        );

        // expect
        assertThat(instance.getServerSpanNamingAndTaggingStrategy())
            .isSameAs(instance.serverSpanNamingAndTaggingStrategy)
            .isSameAs(serverStrategyMock);
        assertThat(instance.getProxyRouterSpanNamingAndTaggingStrategy())
            .isSameAs(instance.proxyRouterSpanNamingAndTaggingStrategy)
            .isSameAs(proxyStrategyMock);
        assertThat(instance.getSpanClassType())
            .isSameAs(instance.spanClassType)
            .isSameAs(spanClassType);
    }

}