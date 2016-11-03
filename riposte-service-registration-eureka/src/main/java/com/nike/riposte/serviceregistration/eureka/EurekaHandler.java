package com.nike.riposte.serviceregistration.eureka;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryManager;
import com.netflix.discovery.EurekaClientConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A handler for integrating with <a href="https://github.com/Netflix/eureka/">Eureka</a>. This handler can be disabled
 * by setting a property {@link #DISABLE_EUREKA_INTEGRATION} as <code>true</code> (and passing in an appropriate
 * {@link Supplier} to the constructor for this class that retrieves that property).
 *
 * <p>If enabled, this class registers the application with eureka as a cloud instance if the value of the property
 * {@link #EUREKA_DATACENTER_TYPE_PROP_NAME} is set to {@link DataCenterInfo.Name#Amazon} or
 * {@link DataCenterInfo.Name#Netflix}. In such a case, this class uses {@link CloudInstanceConfig} to register with
 * eureka. Otherwise {@link MyDataCenterInstanceConfig} is used as the eureka config.
 *
 * @author Florin
 */
@SuppressWarnings("WeakerAccess")
public class EurekaHandler {

    /**
     * Common prefix for all Nike-specific properties.
     */
    public static final String NIKE_PROPERTIES_PREFIX = "com.nike.";

    /**
     * Datacenter type for eureka.
     */
    public static final String EUREKA_DATACENTER_TYPE_PROP_NAME = NIKE_PROPERTIES_PREFIX + "eureka.datacenter.type";

    public static final String EUREKA_COMPONENT_NAME = "eureka";

    /**
     * Set this to <code>true</code>  to disable integration with eureka.
     */
    public static final String DISABLE_EUREKA_INTEGRATION = NIKE_PROPERTIES_PREFIX + EUREKA_COMPONENT_NAME + ".disable";

    protected static final Logger logger = LoggerFactory.getLogger(EurekaHandler.class);

    protected AtomicBoolean registered = new AtomicBoolean();

    public enum ServiceStatus {UP, DOWN}

    protected String eurekaNamespace = EUREKA_COMPONENT_NAME + ".";

    protected String eurekaClientNamespace = EUREKA_COMPONENT_NAME + ".";

    protected final Supplier<Boolean> eurekaIsDisabledPropertySupplier;
    protected final Supplier<String> datacenterTypePropertySupplier;

    /**
     * Creates a new instance that uses the given {@link Supplier} arguments to supply the values of the data center
     * type when {@link #createEurekaInstanceConfig()} is creating the Eureka config, and the value returned by {@link
     * #isEurekaDisabled()}. You are free to build these suppliers any way you want, however the standard mechanism is
     * to get these values from your application's properties files. See the following for suggestions for common
     * configuration frameworks.
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
     * Keep in mind that {@link #isEurekaDisabled()} will call the supplier every time the method is called to provide
     * the value returned by the method, so make sure the supplier does not do anything complex or expensive more than
     * once.
     */
    public EurekaHandler(Supplier<Boolean> eurekaIsDisabledPropertySupplier,
                         Supplier<String> datacenterTypePropertySupplier) {
        this.eurekaIsDisabledPropertySupplier = eurekaIsDisabledPropertySupplier;
        this.datacenterTypePropertySupplier = datacenterTypePropertySupplier;
    }

    public void register() {
        if (isEurekaDisabled()) {
            logger.info("Eureka is disabled, skipping instance's eureka registration.");
            return;
        }

        if (!registered.compareAndSet(false, true)) {
            logger.info("Eureka handler already registered, skipping registration.");
            return;
        }

        EurekaInstanceConfig eurekaInstanceConfig = createEurekaInstanceConfig();

        initDiscoveryManager(eurekaInstanceConfig, new DefaultEurekaClientConfig(eurekaClientNamespace));
        setEurekaInstanceStatus(InstanceInfo.InstanceStatus.UP);
    }

    protected EurekaInstanceConfig createEurekaInstanceConfig() {
        EurekaInstanceConfig eurekaInstanceConfig;

        String datacenterType = datacenterTypePropertySupplier.get();
        DataCenterInfo.Name dcType = DataCenterInfo.Name.MyOwn;
        if (null != datacenterType) {
            try {
                dcType = DataCenterInfo.Name.valueOf(datacenterType);
            }
            catch (IllegalArgumentException e) {
                logger.warn(String.format(
                    "Illegal value %s for eureka datacenter provided in property %s. "
                    + "Ignoring the same and defaulting to %s",
                    datacenterType, EUREKA_DATACENTER_TYPE_PROP_NAME, dcType)
                );
            }
        }

        switch (dcType) {
            case Amazon:
            case Netflix: // Intentional fall-through
                eurekaInstanceConfig = createCloudInstanceConfig(eurekaNamespace);
                break;
            default:
                // Every other value is just custom data center.
                eurekaInstanceConfig = new MyDataCenterInstanceConfig(eurekaNamespace);
                break;
        }
        return eurekaInstanceConfig;
    }

    public void markAsUp() {
        if (isEurekaDisabled()) {
            logger.info("Eureka is disabled, skipping instance's eureka update to up.");
            return;
        }

        setEurekaInstanceStatus(InstanceInfo.InstanceStatus.UP);
    }

    public void markAsDown() {
        logger.info("Eureka handler update status as DOWN.");
        if (isEurekaDisabled()) {
            logger.info("Eureka is disabled, skipping instance's eureka update to down.");
            return;
        }

        shutdownDiscoveryManager();
    }

    public void updateStatus(ServiceStatus newStatus) {
        if (newStatus == null)
            throw new IllegalArgumentException("Service status can not be null.");

        switch (newStatus) {
            case UP:
                markAsUp();
                break;
            case DOWN:
                markAsDown();
                break;
            default:
                throw new IllegalArgumentException("Unhandled ServiceStatus: " + newStatus.name());
        }
    }

    public boolean isEurekaDisabled() {
        return eurekaIsDisabledPropertySupplier.get();
    }

    @SuppressWarnings("deprecation")
    protected void initDiscoveryManager(EurekaInstanceConfig eurekaInstanceConfig,
                                        EurekaClientConfig eurekaClientConfig) {
        DiscoveryManager.getInstance().initComponent(eurekaInstanceConfig, eurekaClientConfig);
    }

    @SuppressWarnings("deprecation")
    protected void shutdownDiscoveryManager() {
        DiscoveryManager.getInstance().shutdownComponent();
    }

    @SuppressWarnings("deprecation")
    protected void setEurekaInstanceStatus(InstanceInfo.InstanceStatus status) {
        ApplicationInfoManager.getInstance().setInstanceStatus(status);
    }

    protected CloudInstanceConfig createCloudInstanceConfig(String namespace) {
        return new CloudInstanceConfig(namespace);
    }
}
