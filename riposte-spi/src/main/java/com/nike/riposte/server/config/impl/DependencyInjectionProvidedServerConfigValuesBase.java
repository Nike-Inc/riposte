package com.nike.riposte.server.config.impl;

import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;

import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Helper class that sets up some basic properties intended for use by a {@link ServerConfig} implementation. These
 * properties are dependency injected via constructor injection, and except for {@link #appEndpoints} are generally
 * extracted from a config file and loaded into the dependency injection system automatically for you (e.g. for Guice
 * you might use a {@code com.nike.guice.PropertiesRegistrationGuiceModule}).
 * <p/>
 * An application that uses dependency injection might have its {@link ServerConfig} implementation use the
 * application's dependency injection system to generate one of these classes and then return these properties directly
 * for the relevant {@link ServerConfig} methods.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class DependencyInjectionProvidedServerConfigValuesBase {

    /**
     * Corresponds to {@link ServerConfig#endpointsPort()}
     */
    public final Integer endpointsPort;
    /**
     * Corresponds to {@link ServerConfig#endpointsSslPort()}
     */
    public final Integer endpointsSslPort;
    /**
     * Corresponds to {@link ServerConfig#isEndpointsUseSsl()}
     */
    public final Boolean endpointsUseSsl;
    /**
     * Corresponds to {@link ServerConfig#numBossThreads()}
     */
    public final Integer numBossThreads;
    /**
     * Corresponds to {@link ServerConfig#numWorkerThreads()}
     */
    public final Integer numWorkerThreads;
    /**
     * Corresponds to {@link ServerConfig#maxRequestSizeInBytes()}
     */
    public final Integer maxRequestSizeInBytes;
    /**
     * Corresponds to {@link ServerConfig#appEndpoints()}
     */
    public final Set<Endpoint<?>> appEndpoints;
    /**
     * Corresponds to {@link ServerConfig#isDebugActionsEnabled()}
     */
    public final Boolean debugActionsEnabled;
    /**
     * Corresponds to {@link ServerConfig#isDebugChannelLifecycleLoggingEnabled()}
     */
    public final Boolean debugChannelLifecycleLoggingEnabled;

    @Inject
    public DependencyInjectionProvidedServerConfigValuesBase(
        @Named("endpoints.port") Integer endpointsPort,
        @Named("endpoints.sslPort") Integer endpointsSslPort,
        @Named("endpoints.useSsl") Boolean endpointsUseSsl,
        @Named("netty.bossThreadCount") Integer numBossThreads,
        @Named("netty.workerThreadCount") Integer numWorkerThreads,
        @Named("netty.maxRequestSizeInBytes") Integer maxRequestSizeInBytes,
        @Named("appEndpoints") Set<Endpoint<?>> appEndpoints,
        @Named("debugActionsEnabled") Boolean debugActionsEnabled,
        @Named("debugChannelLifecycleLoggingEnabled") Boolean debugChannelLifecycleLoggingEnabled
    ) {

        this.endpointsPort = endpointsPort;
        this.endpointsSslPort = endpointsSslPort;
        this.endpointsUseSsl = endpointsUseSsl;
        this.numBossThreads = numBossThreads;
        this.numWorkerThreads = numWorkerThreads;
        this.maxRequestSizeInBytes = maxRequestSizeInBytes;
        this.appEndpoints = appEndpoints;
        this.debugActionsEnabled = debugActionsEnabled;
        this.debugChannelLifecycleLoggingEnabled = debugChannelLifecycleLoggingEnabled;
    }
}
