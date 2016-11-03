package com.nike.riposte.typesafeconfig;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.typesafeconfig.util.TypesafeConfigUtil;
import com.nike.riposte.util.MainClassUtils;

import com.typesafe.config.Config;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Riposte Server that uses Typesafe Config to load properties. This is intended as the Main class entry point for a
 * Typesafe-Config-based app. It Sets up Typesafe Config and initializes a new {@link Server} with the application's
 * {@link ServerConfig} (provided by {@link #getServerConfig(Config)}).
 * <p/>
 * INTENDED USAGE: Create a {@code com.nike.Main} class that looks like the following:
 * <pre>
 *  public class Main extends TypesafeConfigServer {
 *      &#64;Override
 *      protected ServerConfig getServerConfig(Config appConfig) { return new MyAppServerConfig(appConfig); }
 *
 *      public static void main(String[] args) throws Exception {
 *          new Main().launchServer(args);
 *      }
 *  }
 * </pre>
 * All you have to do is replace the {@code return new MyAppServerConfig(appConfig)} line with a reference to your
 * application's {@link ServerConfig}.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public abstract class TypesafeConfigServer {

    protected Server server;
    protected Config appConfig;

    /**
     * @return The {@link ServerConfig} that should be used to configure the launched server.
     */
    protected abstract ServerConfig getServerConfig(Config appConfig);

    /**
     * This will be called after the application's config files have been loaded. See {@link #infrastructureInit()} for
     * details on how the loading is done.
     */
    protected void setAppConfig(Config appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * Returns {@link #appConfig}. This will be null until {@link #setAppConfig(Config)} is called, which is done at the
     * end of {@link #infrastructureInit()}.
     */
    public Config getAppConfig() {
        return appConfig;
    }

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
     * Initializes the Typesafe Config system and configures the Netty leak detection level (if necessary).
     * DO NOT CALL THIS DIRECTLY. Use {@link #launchServer(String[])} when you're ready to start the server.
     */
    protected void infrastructureInit() {
        MainClassUtils.setupJbossLoggingToUseSlf4j();

        Pair<String, String> appIdAndEnvironmentPair = MainClassUtils.getAppIdAndEnvironmentFromSystemProperties();
        Config appConfig = TypesafeConfigUtil
            .loadConfigForAppIdAndEnvironment(appIdAndEnvironmentPair.getLeft(), appIdAndEnvironmentPair.getRight());

        MainClassUtils.logApplicationPropertiesIfDebugActionsEnabled(appConfig::hasPath,
                                                                     (path) -> appConfig.getAnyRef(path).toString(),
                                                                     appConfig.entrySet().stream()
                                                                              .map(Map.Entry::getKey)
                                                                              .collect(Collectors.toList()),
                                                                     false);

        MainClassUtils.setupNettyLeakDetectionLevel(appConfig::hasPath, appConfig::getString);

        setAppConfig(appConfig);
    }

    /**
     * Creates the {@link Server} instance using {@link #getServerConfig(Config)} and {@link #getAppConfig()}, then
     * calls {@link Server#startup()}. DO NOT CALL THIS DIRECTLY. Use {@link #launchServer(String[])} when you're ready
     * to start the server.
     */
    protected void startServer() throws CertificateException, InterruptedException, IOException {
        server = new Server(getServerConfig(getAppConfig()));
        server.startup();
    }

}
