package com.nike.riposte.server.config;

import com.nike.riposte.server.http.Endpoint;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.security.cert.CertificateException;
import java.util.Collection;

import javax.net.ssl.SSLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the default functionality of {@link com.nike.riposte.server.config.ServerConfig}
 */
public class ServerConfigTest {

    @Test
    public void default_method_implementations_return_expected_values() throws CertificateException, SSLException {
        // given
        @SuppressWarnings("Convert2Lambda") 
        ServerConfig defaultImpl = new ServerConfig() {
            @Override
            public @NotNull Collection<@NotNull Endpoint<?>> appEndpoints() {
                return null;
            }
        };

        // expect
        assertThat(defaultImpl.requestAndResponseFilters()).isNull();
        assertThat(defaultImpl.bossThreadFactory()).isNull();
        assertThat(defaultImpl.workerThreadFactory()).isNull();
        assertThat(defaultImpl.defaultCompletableFutureTimeoutInMillisForNonblockingEndpoints()).isEqualTo((58L * 1000L));
        assertThat(defaultImpl.defaultRequestContentDeserializer()).isNull();
        assertThat(defaultImpl.defaultResponseContentSerializer()).isNull();
        assertThat(defaultImpl.longRunningTaskExecutor()).isNull();
        assertThat(defaultImpl.metricsListener()).isNull();
        assertThat(defaultImpl.accessLogger()).isNull();
        assertThat(defaultImpl.postServerStartupHooks()).isNull();
        assertThat(defaultImpl.preServerStartupHooks()).isNull();
        assertThat(defaultImpl.serverShutdownHooks()).isNull();
        assertThat(defaultImpl.riposteErrorHandler()).isNotNull();
        assertThat(defaultImpl.riposteUnhandledErrorHandler()).isNotNull();
        assertThat(defaultImpl.numBossThreads()).isEqualTo((1));
        assertThat(defaultImpl.numWorkerThreads()).isEqualTo((0));
        assertThat(defaultImpl.maxRequestSizeInBytes()).isEqualTo((0));
        assertThat(defaultImpl.responseCompressionThresholdBytes()).isEqualTo((500));
        assertThat(defaultImpl.createSslContext()).isNotNull();
        assertThat(defaultImpl.errorResponseBodySerializer()).isNull();
        assertThat(defaultImpl.requestContentValidationService()).isNull();
        assertThat(defaultImpl.isDebugActionsEnabled()).isEqualTo((false));
        assertThat(defaultImpl.endpointsPort()).isEqualTo((8080));
        assertThat(defaultImpl.endpointsSslPort()).isEqualTo((8443));
        assertThat(defaultImpl.isEndpointsUseSsl()).isEqualTo((false));
        assertThat(defaultImpl.appInfo()).isNull();
        assertThat(defaultImpl.isDebugChannelLifecycleLoggingEnabled()).isEqualTo((false));
        assertThat(defaultImpl.workerChannelIdleTimeoutMillis()).isEqualTo((5000L));
        assertThat(defaultImpl.proxyRouterConnectTimeoutMillis()).isEqualTo((10000L));
        assertThat(defaultImpl.incompleteHttpCallTimeoutMillis()).isEqualTo((5000L));
        assertThat(defaultImpl.maxOpenIncomingServerChannels()).isEqualTo((20000));
        assertThat(defaultImpl.pipelineCreateHooks()).isNull();
        assertThat(defaultImpl.customChannelInitializer()).isNull();
        assertThat(defaultImpl.requestSecurityValidator()).isNull();
        assertThat(defaultImpl.distributedTracingConfig()).isNull();
    }

}