package com.nike.riposte.server.config;

import com.nike.riposte.server.http.Endpoint;

import org.junit.Test;

import java.security.cert.CertificateException;
import java.util.Collection;

import javax.net.ssl.SSLException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the default functionality of {@link com.nike.riposte.server.config.ServerConfig}
 */
public class ServerConfigTest {

    @Test
    public void default_method_implementations_return_expected_values() throws CertificateException, SSLException {
        // given
        ServerConfig defaultImpl = new ServerConfig() {
            @Override
            public Collection<Endpoint<?>> appEndpoints() {
                return null;
            }
        };

        // expect
        assertThat(defaultImpl.requestAndResponseFilters(), nullValue());
        assertThat(defaultImpl.bossThreadFactory(), nullValue());
        assertThat(defaultImpl.workerThreadFactory(), nullValue());
        assertThat(defaultImpl.defaultCompletableFutureTimeoutInMillisForNonblockingEndpoints(), is(58L * 1000L));
        assertThat(defaultImpl.defaultRequestContentDeserializer(), nullValue());
        assertThat(defaultImpl.defaultResponseContentSerializer(), nullValue());
        assertThat(defaultImpl.longRunningTaskExecutor(), nullValue());
        assertThat(defaultImpl.metricsListener(), nullValue());
        assertThat(defaultImpl.accessLogger(), nullValue());
        assertThat(defaultImpl.postServerStartupHooks(), nullValue());
        assertThat(defaultImpl.preServerStartupHooks(), nullValue());
        assertThat(defaultImpl.serverShutdownHooks(), nullValue());
        assertThat(defaultImpl.riposteErrorHandler(), notNullValue());
        assertThat(defaultImpl.riposteUnhandledErrorHandler(), notNullValue());
        assertThat(defaultImpl.numBossThreads(), is(1));
        assertThat(defaultImpl.numWorkerThreads(), is(0));
        assertThat(defaultImpl.maxRequestSizeInBytes(), is(Integer.MAX_VALUE));
        assertThat(defaultImpl.createSslContext(), notNullValue());
        assertThat(defaultImpl.requestContentValidationService(), nullValue());
        assertThat(defaultImpl.isDebugActionsEnabled(), is(false));
        assertThat(defaultImpl.endpointsPort(), is(8080));
        assertThat(defaultImpl.endpointsSslPort(), is(8443));
        assertThat(defaultImpl.isEndpointsUseSsl(), is(false));
        assertThat(defaultImpl.appInfo(), nullValue());
        assertThat(defaultImpl.isDebugChannelLifecycleLoggingEnabled(), is(false));
        assertThat(defaultImpl.workerChannelIdleTimeoutMillis(), is(5000L));
        assertThat(defaultImpl.proxyRouterConnectTimeoutMillis(), is(10000L));
        assertThat(defaultImpl.incompleteHttpCallTimeoutMillis(), is(5000L));
        assertThat(defaultImpl.maxOpenIncomingServerChannels(), is(20000));
        assertThat(defaultImpl.pipelineCreateHooks(), nullValue());
        assertThat(defaultImpl.customChannelInitializer(), nullValue());
        assertThat(defaultImpl.requestSecurityValidator(), is(nullValue()));
    }

}