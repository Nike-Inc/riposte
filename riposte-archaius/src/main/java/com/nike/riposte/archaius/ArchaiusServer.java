package com.nike.riposte.archaius;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.util.MainClassUtils;

import com.netflix.config.ConfigurationManager;

import org.apache.commons.configuration.AbstractConfiguration;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Riposte Server that uses Archaius to load properties. This is intended as the Main class entry point for a
 * Padstone-with-Archaius app. It Sets up Archaius and initializes a new {@link com.nike.riposte.server.Server} with the
 * application's {@link com.nike.riposte.server.config.ServerConfig} (provided by {@link #getServerConfig()}).
 * <p/>
 * INTENDED USAGE: Create a main class that looks something like the following:
 * <pre>
 *  public class Main extends ArchaiusServer {
 *      &#64;Override
 *      protected ServerConfig getServerConfig() { return new MyAppServerConfig(); }
 *
 *      public static void main(String[] args) throws Exception {
 *          new Main().launchServer(args);
 *      }
 *  }
 * </pre>
 * All you have to do is replace the {@code return new MyAppServerConfig()} line with a reference to your application's
 * {@link ServerConfig}.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public abstract class ArchaiusServer {

    protected Server server;

    /**
     * @return The {@link ServerConfig} that should be used to configure the launched server.
     */
    protected abstract ServerConfig getServerConfig();

    /**
     * Call this when you're ready to launch/start the server. The {@code args} argument should be the same as what's
     * passed into the application's {@code public static void main} method entrypoint.
     */
    @SuppressWarnings("UnusedParameters")
    public void launchServer(String[] args) throws Exception {
        MainClassUtils.executeCallableWithLoggingReplayProtection(() -> {
            infrastructureInit();
            startServer();
            return null;
        });
    }

    /**
     * Initializes the Archaius system and configures the Netty leak detection level (if necessary).
     * DO NOT CALL THIS DIRECTLY. Use {@link #launchServer(String[])} when you're ready to start the server.
     */
    protected void infrastructureInit() {
        MainClassUtils.setupJbossLoggingToUseSlf4j();

        try {
            Pair<String, String> appIdAndEnvironmentPair = MainClassUtils.getAppIdAndEnvironmentFromSystemProperties();
            ConfigurationManager.loadCascadedPropertiesFromResources(appIdAndEnvironmentPair.getLeft());
        }
        catch (IOException e) {
            throw new RuntimeException("Error loading Archaius properties", e);
        }

        AbstractConfiguration appConfig = ConfigurationManager.getConfigInstance();
        Function<String, Boolean> hasPropertyFunction = (propKey) -> appConfig.getProperty(propKey) != null;
        Function<String, String> propertyExtractionFunction = (propKey) -> {
            // Properties in Archaius might be a Collection or an Object.
            Object propValObj = appConfig.getProperty(propKey);
            return (propValObj instanceof Collection)
                   ? ((Collection<?>) propValObj).stream().map(String::valueOf).collect(Collectors.joining(","))
                   : String.valueOf(propValObj);
        };
        Set<String> propKeys = new LinkedHashSet<>();
        appConfig.getKeys().forEachRemaining(propKeys::add);

        MainClassUtils.logApplicationPropertiesIfDebugActionsEnabled(
            hasPropertyFunction, propertyExtractionFunction, propKeys, false
        );

        MainClassUtils.setupNettyLeakDetectionLevel(hasPropertyFunction, propertyExtractionFunction);
    }

    /**
     * Creates the {@link Server} instance using {@link #getServerConfig()}, then calls {@link Server#startup()}.
     * DO NOT CALL THIS DIRECTLY. Use {@link #launchServer(String[])} when you're ready to start the server.
     */
    protected void startServer() throws Exception {
        server = new Server(getServerConfig());
        server.startup();
    }
}
