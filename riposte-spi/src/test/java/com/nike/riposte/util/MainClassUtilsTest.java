package com.nike.riposte.util;

import com.nike.internal.util.Pair;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;

import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetector.Level;

import static com.nike.riposte.util.MainClassUtils.DEBUG_ACTIONS_ENABLED_PROP_KEY;
import static com.nike.riposte.util.MainClassUtils.DELAY_CRASH_ON_STARTUP_SYSTEM_PROP_KEY;
import static com.nike.riposte.util.MainClassUtils.JBOSS_LOGGING_PROVIDER_SYSTEM_PROP_KEY;
import static com.nike.riposte.util.MainClassUtils.NETTY_LEAK_DETECTION_LEVEL_APP_PROP_KEY;
import static com.nike.riposte.util.MainClassUtils.NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link MainClassUtils}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class MainClassUtilsTest {

    private Logger loggerMock;

    @Before
    public void beforeMethod() throws NoSuchFieldException, IllegalAccessException {
        setAppIdAndEnvironemntSystemProperties(null, null, null, null);
        resetNettyLeakDetectionLevel();
        setSystemPropWithNullSupport(JBOSS_LOGGING_PROVIDER_SYSTEM_PROP_KEY, null);
        setSystemPropWithNullSupport(DELAY_CRASH_ON_STARTUP_SYSTEM_PROP_KEY, null);

        loggerMock = mock(Logger.class);
        setLoggerFieldTo(loggerMock);
    }

    @After
    public void afterMethod() throws NoSuchFieldException, IllegalAccessException {
        setAppIdAndEnvironemntSystemProperties(null, null, null, null);
        resetNettyLeakDetectionLevel();
        setSystemPropWithNullSupport(JBOSS_LOGGING_PROVIDER_SYSTEM_PROP_KEY, null);
        setSystemPropWithNullSupport(DELAY_CRASH_ON_STARTUP_SYSTEM_PROP_KEY, null);
        setLoggerFieldTo(LoggerFactory.getLogger(MainClassUtils.class));
    }

    @Test
    public void code_coverage_hoops() {
        // jump!
        new MainClassUtils();
    }

    private void setLoggerFieldTo(Logger newLogger) throws NoSuchFieldException, IllegalAccessException {
        Field loggerField = MainClassUtils.class.getDeclaredField("logger");
        loggerField.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(loggerField, loggerField.getModifiers() & ~Modifier.FINAL);

        loggerField.set(null, loggerMock);
    }

    private void setSystemPropWithNullSupport(String key, String val) {
        if (val == null)
            System.clearProperty(key);
        else
            System.setProperty(key, val);
    }

    private void resetNettyLeakDetectionLevel() {
        System.clearProperty(NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY);
        ResourceLeakDetector.setLevel(Level.SIMPLE);
    }

    private void setAppIdAndEnvironemntSystemProperties(
        String appId, String archaiusAppId, String environment, String archaiusEnvironment
    ) {
        setSystemPropWithNullSupport("@appId", appId);
        setSystemPropWithNullSupport("archaius.deployment.applicationId", archaiusAppId);
        setSystemPropWithNullSupport("@environment", environment);
        setSystemPropWithNullSupport("archaius.deployment.environment", archaiusEnvironment);
    }

    @Test
    public void setupJbossLoggingToUseSlf4j_sets_relevant_system_property_to_slf4j() {
        // given
        assertThat(System.getProperty(JBOSS_LOGGING_PROVIDER_SYSTEM_PROP_KEY)).isNull();

        // when
        MainClassUtils.setupJbossLoggingToUseSlf4j();

        // then
        assertThat(System.getProperty(JBOSS_LOGGING_PROVIDER_SYSTEM_PROP_KEY)).isEqualTo("slf4j");
    }

    @DataProvider(value = {
        "foo    |   null    |   bar     |   null    |   false   |   foo     |   bar",
        "foo    |   notused |   bar     |   notused |   false   |   foo     |   bar",
        "null   |   foo     |   null    |   bar     |   false   |   foo     |   bar",
        "null   |   null    |   bar     |   notused |   true    |   null    |   null",
        "foo    |   notused |   null    |   null    |   true    |   null    |   null"
    }, splitBy = "\\|")
    @Test
    public void getAppIdAndEnvironmentFromSystemProperties_works_as_expected(
        String appId, String archaiusAppId, String environment, String archaiusEnvironment,
        boolean expectIllegalStateException, String expectedAppId, String expectedEnvironment
    ) {
        // given
        setAppIdAndEnvironemntSystemProperties(appId, archaiusAppId, environment, archaiusEnvironment);

        // when
        Throwable ex = null;
        Pair<String, String> results = null;
        try {
            results = MainClassUtils.getAppIdAndEnvironmentFromSystemProperties();
        }
        catch(Throwable t) {
            ex = t;
        }

        // then
        if (expectIllegalStateException)
            assertThat(ex).isInstanceOf(IllegalStateException.class);
        else {
            assertThat(ex).isNull();
            assertThat(results.getLeft()).isEqualTo(expectedAppId);
            assertThat(results.getRight()).isEqualTo(expectedEnvironment);
        }
    }

    @DataProvider(value = {
        // no-op case
        "null       |   null        |   null        |   null",
        // cases showing that system property takes precedence over everything
        "PARANOID   |   null        |   null        |   PARANOID",
        "disabled   |   PARANOID    |   null        |   DISABLED", // also - lowercase works
        "aDvAnCeD   |   PARANOID    |   DISABLED    |   ADVANCED", // also - mixed case works
        // cases showing that NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY takes precedence
        //      over NETTY_LEAK_DETECTION_LEVEL_APP_PROP_KEY if the system property is absent
        "null       |   ADVANCED    |   null        |   ADVANCED",
        "null       |   aDvAnCeD    |   PARANOID    |   ADVANCED", // yes, lower/mixed case still works here too
        // cases showing NETTY_LEAK_DETECTION_LEVEL_APP_PROP_KEY will be used if the other
        //      options are not available
        "null       |   null        |   DISABLED    |   DISABLED",
        "null       |   null        |   pArAnOiD    |   PARANOID", // yes, lower/mixed case still works here too
    }, splitBy = "\\|")
    @Test
    public void setupNettyLeakDetectionLevel_works_as_expected(
        String systemPropValue, String configValueForSystemPropKey, String configValueForAppPropKey, Level expectedFinalLevel
    ) {
        // given
        assertThat(ResourceLeakDetector.getLevel()).isEqualTo(Level.SIMPLE);
        assertThat(expectedFinalLevel).isNotEqualTo(Level.SIMPLE);

        setSystemPropWithNullSupport(NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY, systemPropValue);
        Function<String, String> propertyExtractionFunction = (key) -> {
            switch(key) {
                case NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY:
                    return configValueForSystemPropKey;
                case NETTY_LEAK_DETECTION_LEVEL_APP_PROP_KEY:
                    return configValueForAppPropKey;
                default:
                    throw new IllegalArgumentException("Unhandled config key: " + key);
            }
        };
        Function<String, Boolean> hasPropertyFunction = (key) -> (propertyExtractionFunction.apply(key) != null);

        // when
        MainClassUtils.setupNettyLeakDetectionLevel(hasPropertyFunction, propertyExtractionFunction);

        // then
        if (expectedFinalLevel == null) {
            // We expect that the method did nothing since it couldn't find anything to set
            assertThat(System.getProperty(NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY)).isNull();
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(Level.SIMPLE);
        }
        else {
            assertThat(System.getProperty(NETTY_LEAK_DETECTION_LEVEL_SYSTEM_PROP_KEY))
                .isEqualTo(expectedFinalLevel.name());
            assertThat(ResourceLeakDetector.getLevel()).isEqualTo(expectedFinalLevel);
        }
    }

    @DataProvider(value = {
        "null   |   false   |   false",
        "null   |   true    |   true",
        "true   |   true    |   true",
        "true   |   false   |   true",
        "false  |   true    |   true",
        "false  |   false   |   false",
    }, splitBy = "\\|")
    @Test
    public void logApplicationPropertiesIfDebugActionsEnabled_works_as_expected(
        String debugActionsEnabled, boolean forceLogging, boolean expectLogging
    ) {
        // given
        Function<String, String> propertyExtractionFunction = (key) -> {
            if (DEBUG_ACTIONS_ENABLED_PROP_KEY.equals(key))
                return debugActionsEnabled;

            return key + "-value";
        };
        Function<String, Boolean> hasPropertyFunction = (key) -> (propertyExtractionFunction.apply(key) != null);
        List<String> appPropNames = Arrays.asList("propZ", "propA");

        // when
        MainClassUtils.logApplicationPropertiesIfDebugActionsEnabled(hasPropertyFunction, propertyExtractionFunction, appPropNames, forceLogging);

        // then
        if (expectLogging) {
            verify(loggerMock).info("====== APP PROPERTIES ======");
            verify(loggerMock).info("propA: propA-value");
            verify(loggerMock).info("propZ: propZ-value");
            verify(loggerMock).info("====== END APP PROPERTIES ======");
            verifyNoMoreInteractions(loggerMock);
        }
        else {
            verifyZeroInteractions(loggerMock);
        }
    }

    @Test
    public void executeCallableWithLoggingReplayProtection_does_nothing_if_callable_does_not_throw_exception()
        throws Exception {
        // given
        String expectedResult = UUID.randomUUID().toString();
        Callable<String> callable = () -> expectedResult;

        // when
        String result = MainClassUtils.executeCallableWithLoggingReplayProtection(callable);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void executeCallableWithLoggingReplayProtection_delays_by_specified_amount_if_callable_throws_exception() {
        // given
        System.setProperty(DELAY_CRASH_ON_STARTUP_SYSTEM_PROP_KEY, "true");
        RuntimeException ex = new RuntimeException("kaboom");
        Callable<String> callable = () -> { throw ex; };
        long delay = 200;

        // when
        long beforeMillis = System.currentTimeMillis();
        Throwable caughtEx = catchThrowable(() -> MainClassUtils.executeCallableWithLoggingReplayProtection(callable, delay));
        long afterMillis = System.currentTimeMillis();

        // then
        assertThat(caughtEx).isSameAs(ex);
        assertThat(afterMillis - beforeMillis).isGreaterThanOrEqualTo(delay);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void executeCallableWithLoggingReplayProtection_does_not_delay_on_exception_if_system_property_is_set(
        boolean useNull
    ) {
        // given
        if (useNull)
            System.clearProperty(DELAY_CRASH_ON_STARTUP_SYSTEM_PROP_KEY);
        else
            System.setProperty(DELAY_CRASH_ON_STARTUP_SYSTEM_PROP_KEY, "false");
        RuntimeException ex = new RuntimeException("kaboom");
        Callable<String> callable = () -> { throw ex; };
        long delay = 200;

        // when
        long beforeMillis = System.currentTimeMillis();
        Throwable caughtEx = catchThrowable(() -> MainClassUtils.executeCallableWithLoggingReplayProtection(callable, delay));
        long afterMillis = System.currentTimeMillis();

        // then
        assertThat(caughtEx).isSameAs(ex);
        assertThat(afterMillis - beforeMillis).isLessThan(delay);
    }
}