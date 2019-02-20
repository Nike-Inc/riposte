package com.nike.riposte.serviceregistration.eureka;

import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.hooks.PostServerStartupHook;
import com.nike.riposte.server.hooks.ServerShutdownHook;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

import io.netty.channel.Channel;

/**
 * Hook that registers app with Eureka.
 */
@SuppressWarnings("WeakerAccess")
public class EurekaServerHook implements PostServerStartupHook, ServerShutdownHook {

    private static final Logger logger = LoggerFactory.getLogger(EurekaServerHook.class);

    public final EurekaHandler eurekaHandler;

    /**
     * Creates a new instance that uses the given {@link Supplier} arguments when creating {@link #eurekaHandler} to
     * supply the values of the data center type when {@link EurekaHandler#createEurekaInstanceConfig()} is creating the
     * Eureka config, and the value returned by {@link EurekaHandler#isEurekaDisabled()}. You are free to build these
     * suppliers any way you want, however the standard mechanism is to get these values from your application's
     * properties files. See the following for suggestions for common configuration frameworks.
     * <p/>
     * For Archaius:
     * <pre>
     *  new EurekaHandler(
     *   () -> ConfigurationManager.getConfigInstance().getBoolean(EurekaHandler.DISABLE_EUREKA_INTEGRATION, false),
     *   () -> ConfigurationManager.getConfigInstance().getString(EurekaHandler.EUREKA_DATACENTER_TYPE_PROP_NAME, null)
     *  )
     * </pre>
     * For TypesafeConfig (you'll need access to the {@code com.typesafe.config.Config appConfig} for the application):
     * <pre>
     *  new EurekaHandler(
     *   () -> appConfig.getBoolean(EurekaHandler.DISABLE_EUREKA_INTEGRATION),
     *   () -> appConfig.getString(EurekaHandler.EUREKA_DATACENTER_TYPE_PROP_NAME)
     *  )
     * </pre>
     * Keep in mind that {@link EurekaHandler#isEurekaDisabled()} will call the supplier every time the method is called
     * to provide the value returned by the method, so make sure the supplier does not do anything complex or expensive
     * more than once.
     */
    public EurekaServerHook(Supplier<Boolean> eurekaIsDisabledPropertySupplier,
                            Supplier<String> datacenterTypePropertySupplier) {
        eurekaHandler = new EurekaHandler(eurekaIsDisabledPropertySupplier, datacenterTypePropertySupplier);
    }

    @Override
    public void executePostServerStartupHook(@NotNull ServerConfig serverConfig, @NotNull Channel channel) {
        try {
            // register
            logger.info("About to register with Eureka");
            eurekaHandler.register();
        }
        catch (Exception e) {
            logger.warn("Eureka registration exception", e);
            throw new EurekaException("Eureka registration exception", e);
        }
    }

    @Override
    public void executeServerShutdownHook(@NotNull ServerConfig serverConfig, @NotNull Channel channel) {
        try {
            // deregister
            logger.info("About to de-register with Eureka");
            eurekaHandler.updateStatus(EurekaHandler.ServiceStatus.DOWN);
        }
        catch (Exception e) {
            logger.warn("Eureka deregistration exception", e);
            throw new EurekaException("Eureka deregistration exception", e);
        }
    }
}
