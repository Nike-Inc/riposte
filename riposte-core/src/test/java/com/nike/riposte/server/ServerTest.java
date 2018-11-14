package com.nike.riposte.server;

import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.config.distributedtracing.DefaultRiposteDistributedTracingConfigImpl;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfigImpl;
import com.nike.riposte.server.config.distributedtracing.ServerSpanNamingAndTaggingStrategy;
import com.nike.wingtips.Span;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link Server}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class ServerTest {

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void getOrGenerateWingtipsDistributedTracingConfig_works_as_expected_for_wingtips_span_type(
        boolean nullDtConfig
    ) {
        // given
        DistributedTracingConfig<Span> dtConfig =
            (nullDtConfig)
            ? null
            : new DistributedTracingConfigImpl<>(mock(ServerSpanNamingAndTaggingStrategy.class), Span.class);
        DistributedTracingConfig<Span> expectedResult =
            (dtConfig == null)
            ? DefaultRiposteDistributedTracingConfigImpl.getDefaultInstance()
            : dtConfig;

        ServerConfig serverConfigMock = mock(ServerConfig.class);
        doReturn(dtConfig).when(serverConfigMock).distributedTracingConfig();

        Server server = new Server(serverConfigMock);

        // when
        DistributedTracingConfig<Span> result = server.getOrGenerateWingtipsDistributedTracingConfig(serverConfigMock);

        // then
        assertThat(result).isSameAs(expectedResult);
        verify(serverConfigMock).distributedTracingConfig();
    }

    @Test
    public void getOrGenerateWingtipsDistributedTracingConfig_throws_IllegalArgumentException_if_span_type_is_not_wingtips_Span() {
        // given
        DistributedTracingConfig<Object> dtConfigMock = mock(DistributedTracingConfig.class);
        doReturn(Object.class).when(dtConfigMock).getSpanClassType();

        ServerConfig serverConfigMock = mock(ServerConfig.class);
        doReturn(dtConfigMock).when(serverConfigMock).distributedTracingConfig();

        Server server = new Server(serverConfigMock);

        // when
        Throwable ex = catchThrowable(() -> server.getOrGenerateWingtipsDistributedTracingConfig(serverConfigMock));

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Your ServerConfig.distributedTracingConfig() does not support Wingtips Spans.");
        verify(serverConfigMock).distributedTracingConfig();
    }

}
