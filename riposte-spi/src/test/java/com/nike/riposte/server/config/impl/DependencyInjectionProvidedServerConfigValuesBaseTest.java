package com.nike.riposte.server.config.impl;

import com.nike.riposte.server.http.Endpoint;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link DependencyInjectionProvidedServerConfigValuesBase}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class DependencyInjectionProvidedServerConfigValuesBaseTest {

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void constructor_sets_values_as_expected(boolean boolVal) {
        // given
        Integer port = 42;
        Integer sslPort = 43;
        Boolean useSsl = boolVal;
        Integer numBossThreads = 82;
        Integer numWorkerThreads = 83;
        Integer maxRequestSizeBytes = 4242;
        Set<Endpoint<?>> endpoints = mock(Set.class);
        Boolean debugActionsEnabled = boolVal;
        Boolean debugChannelLifecycleLoggingEnabled = boolVal;

        // when
        DependencyInjectionProvidedServerConfigValuesBase base = new DependencyInjectionProvidedServerConfigValuesBase(
            port, sslPort, useSsl, numBossThreads, numWorkerThreads, maxRequestSizeBytes, endpoints,
            debugActionsEnabled, debugChannelLifecycleLoggingEnabled
        );

        // then
        assertThat(base.endpointsPort).isEqualTo(port);
        assertThat(base.endpointsSslPort).isEqualTo(sslPort);
        assertThat(base.endpointsUseSsl).isEqualTo(useSsl);
        assertThat(base.numBossThreads).isEqualTo(numBossThreads);
        assertThat(base.numWorkerThreads).isEqualTo(numWorkerThreads);
        assertThat(base.maxRequestSizeInBytes).isEqualTo(maxRequestSizeBytes);
        assertThat(base.appEndpoints).isEqualTo(endpoints);
        assertThat(base.debugActionsEnabled).isEqualTo(debugActionsEnabled);
        assertThat(base.debugChannelLifecycleLoggingEnabled).isEqualTo(debugChannelLifecycleLoggingEnabled);
    }

}