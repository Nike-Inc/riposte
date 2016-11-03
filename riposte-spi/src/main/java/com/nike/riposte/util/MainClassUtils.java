package com.nike.riposte.util;

import com.nike.internal.util.Pair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

import io.netty.util.ResourceLeakDetector;

/**
 * This class contains static helper methods for Riposte Main classes to use when setting up
 * infrastructure/configuration before launching a Riposte server. The typical pattern for a Riposte main class is:
 * <pre>
 * <ol>
 *     <li>
 *         Call {@link #setupJbossLoggingToUseSlf4j()} to force any third party libraries that use JBoss logging to go
 *         through SLF4J instead.
 *     </li>
 *     <li>
 *         Call {@link #getAppIdAndEnvironmentFromSystemProperties()} to extract the @appId and @environment System
 *         properties. These are then generally used to load the appropriate application properties files for the
 *         current app and environment.
 *     </li>
 *     <li>
 *         Call {@link #logApplicationPropertiesIfDebugActionsEnabled(Function, Function, Collection, boolean)} to
 *         (optionally) log all the app config properties.
 *     </li>
 *     <li>
 *         Call {@link #setupNettyLeakDetectionLevel(Function, Function)} to make sure Netty's leak detection level is
 *         setup as desired from System or application properties.
 *     </li>
 *     <li>
 *         Launch the Riposte server.
 *     </li>
 * </ol>
 * </pre>
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class MainClassUtils {

    private static final Logger logger = LoggerFactory.getLogger(MainClassUtils.class);

    /**
     * The property key for overriding the netty leak detection level by passing in a System property when the
     * application is started. This is preferred over anything specified in the application properties file.
     */
    public static final String NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY = "io.netty.leakDetectionLevel";
    /**
     * The property key for overriding the netty leak detection level from the application properties files. This is
     * only used if {@link #NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY} is *not* specified in either System properties
     * or application properties files.
     */
    public static final String NETTY_LEAK_DETECTION_LEVEL_APP_PROP_KEY = "netty.leakDetectionLevel";
    protected static final String DEBUG_ACTIONS_ENABLED_PROP_KEY = "debugActionsEnabled";

    public static final String DELAY_CRASH_ON_STARTUP_SYSTEM_PROP_KEY = "delayCrashOnStartup";

    public static final String JBOSS_LOGGING_PROVIDER_SYSTEM_PROP_KEY = "org.jboss.logging.provider";

    public static final long DEFAULT_CRASH_DELAY_MILLIS = 20000;

    // Intentionally protected - use the static methods.
    protected MainClassUtils() { /* do nothing */ }

    /**
     * Tells JBoss Logging (if it's around) to use SLF4J. This won't do anything if JBoss Logging isn't available.
     * See http://docs.jboss.org/hibernate/orm/4.3/topical/html/logging/Logging.html
     */
    public static void setupJbossLoggingToUseSlf4j() {
        System.setProperty(JBOSS_LOGGING_PROVIDER_SYSTEM_PROP_KEY, "slf4j");
    }

    /**
     * Returns the appId and environment settings from the System properties. This uses the Archaius naming conventions
     * ({@code @appId} or {@code archaius.deployment.applicationId} for appId and {@code @environment} or {@code
     * archaius.deployment.environment} for environment) but since these are System properties there's no dependency
     * link - we're just reusing the concept. If appId or environment cannot be extracted from the System properties
     * then a {@link IllegalStateException} will be thrown.
     */
    public static Pair<String, String> getAppIdAndEnvironmentFromSystemProperties() {
        String appIdToUse = System.getProperty("@appId");
        if (appIdToUse == null)
            appIdToUse = System.getProperty("archaius.deployment.applicationId");

        if (appIdToUse == null) {
            throw new IllegalStateException(
                "You must set the @appId or archaius.deployment.applicationId System property before this server will "
                + "start. This tells the application Config system (Archaius, Typesafe, etc) which set of properties "
                + "files should be loaded for this application."
            );
        }

        String environmentToUse = System.getProperty("@environment");
        if (environmentToUse == null)
            environmentToUse = System.getProperty("archaius.deployment.environment");

        if (environmentToUse == null) {
            throw new IllegalStateException(
                "You must set the @environment or archaius.deployment.environment System property before this server "
                + "will start. This tells the application Config system (Archaius, Typesafe, etc) which "
                + "environment-specific properties file to load."
            );
        }

        return Pair.of(appIdToUse, environmentToUse);
    }

    /**
     * Sets up the Netty leak detection level to one of the {@link ResourceLeakDetector.Level} options. Honors the
     * {@link #NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY} System property if available first (which matches the private
     * constant {@link ResourceLeakDetector#PROP_LEVEL} which Netty uses). If that System property is not set then the
     * given argument will be used to extract the relevant level value from the application properties and use that if
     * it's not null. NOTE: The logic for pulling from the application properties is as follows:
     * <pre>
     * <ol>
     *     <li>
     *         The system property key will be checked in the application properties first:
     *         {@link #NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY}
     *     </li>
     *     <li>
     *         If the system property key is not found in the application properties, then a slightly different key that
     *         is nicer for application properties files will be checked:
     *         {@link #NETTY_LEAK_DETECTION_LEVEL_APP_PROP_KEY}
     *     </li>
     * </ol>
     * </pre>
     * If the level is not specified in either System or application properties then this method will do nothing,
     * leaving the Netty default to be used.
     *
     * @param hasPropertyFunction
     *     A function that returns true if the argument to the function is a property key that exists.
     * @param propertyExtractionFunction
     *     A function that accepts a property key as an argument and returns the property value as a string. The {@code
     *     hasPropertyFunction} arg will be used to guarantee the property exists before this function is called.
     */
    public static void setupNettyLeakDetectionLevel(Function<String, Boolean> hasPropertyFunction,
                                                    Function<String, String> propertyExtractionFunction) {
        String nettyLeakDetectionLevel = System.getProperty(NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY);
        if (nettyLeakDetectionLevel == null) {
            // No system property. See if it's specified in the app properties.
            if (hasPropertyFunction.apply(NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY))
                nettyLeakDetectionLevel = propertyExtractionFunction.apply(NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY);
            else if (hasPropertyFunction.apply(NETTY_LEAK_DETECTION_LEVEL_APP_PROP_KEY))
                nettyLeakDetectionLevel = propertyExtractionFunction.apply(NETTY_LEAK_DETECTION_LEVEL_APP_PROP_KEY);
        }

        if (nettyLeakDetectionLevel == null) {
            logger.info("No netty leak detection level specified in System or application properties. "
                        + "The default Netty behavior will be used. netty_leak_detection_level_used={}",
                        String.valueOf(ResourceLeakDetector.getLevel())
            );
        }
        else {
            nettyLeakDetectionLevel = nettyLeakDetectionLevel.toUpperCase();
            logger.info("Netty leak detection level specified in the System properties or application properties. "
                        + "netty_leak_detection_level_used={}",
                        nettyLeakDetectionLevel
            );
            System.setProperty(NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY, nettyLeakDetectionLevel);
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.valueOf(nettyLeakDetectionLevel));
        }
    }

    /**
     * Spits out the application properties to the logs if one of those properties is {@link
     * #DEBUG_ACTIONS_ENABLED_PROP_KEY} and it is set to true, or if the {@code forceLogging} argument is true.
     *
     * @param hasPropertyFunction
     *     A function that returns true if the argument to the function is a property key that exists.
     * @param propertyExtractionFunction
     *     A function that accepts a property key as an argument and returns the property value as a string. The {@code
     *     hasPropertyFunction} arg will be used to guarantee the property exists before this function is called.
     * @param applicationPropertyNames
     *     The collection of property names for all the properties associated with this application.
     */
    public static void logApplicationPropertiesIfDebugActionsEnabled(Function<String, Boolean> hasPropertyFunction,
                                                                     Function<String, String> propertyExtractionFunction,
                                                                     Collection<String> applicationPropertyNames,
                                                                     boolean forceLogging) {
        boolean debugActionsEnabledInAppProps =
            (hasPropertyFunction.apply(DEBUG_ACTIONS_ENABLED_PROP_KEY)
            && Boolean.valueOf(propertyExtractionFunction.apply(DEBUG_ACTIONS_ENABLED_PROP_KEY)));

        if (forceLogging || debugActionsEnabledInAppProps) {
            // Print the properties out in alphabetical order based on the property key
            List<String> applicationPropertyNamesSorted = new ArrayList<>(applicationPropertyNames);
            Collections.sort(applicationPropertyNamesSorted);

            logger.info("====== APP PROPERTIES ======");
            for (String propName : applicationPropertyNamesSorted) {
                String value = propertyExtractionFunction.apply(propName);
                logger.info(propName + ": " + value);
            }
            logger.info("====== END APP PROPERTIES ======");
        }
    }

    /**
     * Executes (and returns the result) of the given callable, but surrounds that execution with a try/catch that
     * should allow SLF4J logging replay to occur in cases where the log messages would otherwise get swallowed. One
     * example where this is necessary is when launching an application while using a java agent (e.g. the NewRelic
     * agent) - if the app crashes before enough time has passed, some or all of the log messages sent to SLF4J would
     * not get a chance to be output, swallowing potentially crucial info about what went wrong. By catching any
     * exception and waiting long enough before letting the exception propagate, we give the agent enough time to let
     * SLF4J replay any log messages that were stored. The app will still crash exactly as it normally would, but the
     * application log should be filled with all log messages sent to SLF4J before it crashes.
     * <p/>
     * See http://www.slf4j.org/codes.html#replay for more info.
     */
    public static <T> T executeCallableWithLoggingReplayProtection(Callable<T> callable,
                                                                   long delayInMillisIfExceptionOccurs)
        throws Exception {
        try {
            return callable.call();
        }
        catch (Throwable t) {
            outputExceptionalShutdownMessage(
                "An exception occurred during startup (see exception stack trace following this message). "
                + "Attempting to force SLF4J to finish initializing to make sure all the log messages "
                + "are output and nothing is swallowed. See http://www.slf4j.org/codes.html#replay for info "
                + "on why this can be necessary in some cases.", t, logger
            );
            try {
                LoggerFactory.getILoggerFactory();

                boolean shouldDelayCrash =
                    "true".equals(System.getProperty(DELAY_CRASH_ON_STARTUP_SYSTEM_PROP_KEY, "false"));

                if (shouldDelayCrash) {
                    outputExceptionalShutdownMessage(
                        "Done with initialization call. Waiting " + delayInMillisIfExceptionOccurs
                        + " milliseconds to give it time to finish "
                        + "before letting the app crash normally.", null, logger
                    );
                    Thread.sleep(delayInMillisIfExceptionOccurs);
                    outputExceptionalShutdownMessage("Done waiting. The app will crash now.", null, logger);
                }
            }
            catch (Throwable lfEx) {
                outputExceptionalShutdownMessage(
                    "SLF4J exploded while trying to force initialization with the following error:", lfEx, logger
                );
            }

            throw t;
        }
    }

    /**
     * Executes (and returns the result) of the given callable, but surrounds that execution with a try/catch that
     * should allow SLF4J logging replay to occur in cases where the log messages would otherwise get swallowed. One
     * example where this is necessary is when launching an application while using a java agent (e.g. the NewRelic
     * agent) - if the app crashes before enough time has passed, some or all of the log messages sent to SLF4J would
     * not get a chance to be output, swallowing potentially crucial info about what went wrong. By catching any
     * exception and waiting long enough before letting the exception propagate, we give the agent enough time to let
     * SLF4J replay any log messages that were stored. The app will still crash exactly as it normally would, but the
     * application log should be filled with all log messages sent to SLF4J before it crashes.
     *
     * <p>See http://www.slf4j.org/codes.html#replay for more info.
     *
     * <p>This method will use the {@link #DEFAULT_CRASH_DELAY_MILLIS} as the crash delay value. If you want to specify
     * a different value you can call {@link #executeCallableWithLoggingReplayProtection(Callable, long)} instead.
     */
    public static <T> T executeCallableWithLoggingReplayProtection(Callable<T> callable) throws Exception {
        return executeCallableWithLoggingReplayProtection(callable, DEFAULT_CRASH_DELAY_MILLIS);
    }

    /**
     * Helper method for {@link #executeCallableWithLoggingReplayProtection(Callable)} that spits out the given message
     * to both {@code System.err} and the given logger. If the given exception is not null then its stack trace will be
     * included in any output.
     */
    private static void outputExceptionalShutdownMessage(String message, Throwable ex, Logger logger) {
        System.err.println("STARTUP ERROR: " + message);
        if (ex != null)
            ex.printStackTrace();
        logger.error(message, ex);
    }
}
