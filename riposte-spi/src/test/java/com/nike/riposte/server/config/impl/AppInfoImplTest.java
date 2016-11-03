package com.nike.riposte.server.config.impl;

import com.nike.riposte.server.config.AppInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.internal.util.reflection.Whitebox;

import java.net.UnknownHostException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link AppInfoImpl}
 *
 * @author Nic Munroe
 */
public class AppInfoImplTest {

    private String systemPropAppId;
    private String systemPropArchaiusDeploymentAppId;
    private String systemPropEurekaName;
    private String systemPropEnvironment;
    private String systemPropArchaiusDeploymentEnvironment;

    private static final String AT_APP_ID_SYSPROP_KEY = "@appId";
    private static final String ARCHAIUS_DEPLOYMENT_APPID_SYSPROP_KEY = "archaius.deployment.applicationId";
    private static final String EUREKA_NAME_SYSPROP_KEY = "eureka.name";
    private static final String AT_ENVIRONMENT_SYSPROP_KEY = "@environment";
    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT_SYSPROP_KEY = "archaius.deployment.environment";

    private void resetSystemProp(String key, String value) {
        if (value == null)
            System.clearProperty(key);
        else
            System.setProperty(key, value);
    }

    @Before
    public void beforeMethod() {
        systemPropAppId = System.getProperty(AT_APP_ID_SYSPROP_KEY);
        systemPropArchaiusDeploymentAppId = System.getProperty(ARCHAIUS_DEPLOYMENT_APPID_SYSPROP_KEY);
        systemPropEurekaName = System.getProperty(EUREKA_NAME_SYSPROP_KEY);
        systemPropEnvironment = System.getProperty(AT_ENVIRONMENT_SYSPROP_KEY);
        systemPropArchaiusDeploymentEnvironment = System.getProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT_SYSPROP_KEY);
    }

    @After
    public void afterMethod() {
        resetSystemProp(AT_APP_ID_SYSPROP_KEY, systemPropAppId);
        resetSystemProp(ARCHAIUS_DEPLOYMENT_APPID_SYSPROP_KEY, systemPropArchaiusDeploymentAppId);
        resetSystemProp(EUREKA_NAME_SYSPROP_KEY, systemPropEurekaName);
        resetSystemProp(AT_ENVIRONMENT_SYSPROP_KEY, systemPropEnvironment);
        resetSystemProp(ARCHAIUS_DEPLOYMENT_ENVIRONMENT_SYSPROP_KEY, systemPropArchaiusDeploymentEnvironment);
    }

    @Test
    public void constructor_sets_expected_fields() {
        // given
        String appId = UUID.randomUUID().toString();
        String environment = UUID.randomUUID().toString();
        String datacenter = UUID.randomUUID().toString();
        String instanceId = UUID.randomUUID().toString();

        // when
        AppInfoImpl appInfoImpl = new AppInfoImpl(appId, environment, datacenter, instanceId);

        // then
        assertThat(appInfoImpl.appId).isEqualTo(appInfoImpl.appId()).isEqualTo(appId);
        assertThat(appInfoImpl.environment).isEqualTo(appInfoImpl.environment()).isEqualTo(environment);
        assertThat(appInfoImpl.dataCenter).isEqualTo(appInfoImpl.dataCenter()).isEqualTo(datacenter);
        assertThat(appInfoImpl.instanceId).isEqualTo(appInfoImpl.instanceId()).isEqualTo(instanceId);
    }

    @Test
    public void deserialization_constructor_sets_everything_to_null() {
        // when
        AppInfoImpl appInfoImpl = new AppInfoImpl();

        // then
        assertThat(appInfoImpl.appId).isEqualTo(appInfoImpl.appId()).isNull();
        assertThat(appInfoImpl.environment).isEqualTo(appInfoImpl.environment()).isNull();
        assertThat(appInfoImpl.dataCenter).isEqualTo(appInfoImpl.dataCenter()).isNull();
        assertThat(appInfoImpl.instanceId).isEqualTo(appInfoImpl.instanceId()).isNull();
    }

    private void setAppIdSystemProps(String atAppId, String archaiusDeploymentAppId, String eurekaName) {
        resetSystemProp(AT_APP_ID_SYSPROP_KEY, atAppId);
        resetSystemProp(ARCHAIUS_DEPLOYMENT_APPID_SYSPROP_KEY, archaiusDeploymentAppId);
        resetSystemProp(EUREKA_NAME_SYSPROP_KEY, eurekaName);
    }

    private void setEnvironmentSystemProps(String atEnvironment, String archaiusDeploymentEnvironment) {
        resetSystemProp(AT_ENVIRONMENT_SYSPROP_KEY, atEnvironment);
        resetSystemProp(ARCHAIUS_DEPLOYMENT_ENVIRONMENT_SYSPROP_KEY, archaiusDeploymentEnvironment);
    }

    @Test
    public void detectAppId_returns_atAppId_system_prop_if_available() {
        // given
        String atAppId = UUID.randomUUID().toString();
        String archaiusDeploymentAppId = UUID.randomUUID().toString();
        String eurekaName = UUID.randomUUID().toString();
        setAppIdSystemProps(atAppId, archaiusDeploymentAppId, eurekaName);

        // expect
        assertThat(AppInfoImpl.detectAppId()).isEqualTo(atAppId);
    }

    @Test
    public void detectAppId_returns_archaiusDeploymentAppId_system_prop_if_atAppId_is_not_available() {
        // given
        String archaiusDeploymentAppId = UUID.randomUUID().toString();
        String eurekaName = UUID.randomUUID().toString();
        setAppIdSystemProps(null, archaiusDeploymentAppId, eurekaName);

        // expect
        assertThat(AppInfoImpl.detectAppId()).isEqualTo(archaiusDeploymentAppId);
    }

    @Test
    public void detectAppId_returns_eurekaName_system_prop_if_atAppId_and_archaiusDeploymentAppId_are_not_available() {
        // given
        String eurekaName = UUID.randomUUID().toString();
        setAppIdSystemProps(null, null, eurekaName);

        // expect
        assertThat(AppInfoImpl.detectAppId()).isEqualTo(eurekaName);
    }

    @Test
    public void detectAppId_returns_null_if_known_system_props_are_null() {
        // given
        setAppIdSystemProps(null, null, null);

        // expect
        assertThat(AppInfoImpl.detectAppId()).isNull();
    }

    @Test
    public void detectEnvironment_returns_atEnvironment_system_prop_if_available() {
        // given
        String atEnvironment = UUID.randomUUID().toString();
        String archaiusDeploymentEnvironment = UUID.randomUUID().toString();
        setEnvironmentSystemProps(atEnvironment, archaiusDeploymentEnvironment);

        // expect
        assertThat(AppInfoImpl.detectEnvironment()).isEqualTo(atEnvironment);
    }

    @Test
    public void detectEnvironment_returns_archaiusDeploymentEnvironment_system_prop_if_atEnvironment_is_not_available() {
        // given
        String archaiusDeploymentEnvironment = UUID.randomUUID().toString();
        setEnvironmentSystemProps(null, archaiusDeploymentEnvironment);

        // expect
        assertThat(AppInfoImpl.detectEnvironment()).isEqualTo(archaiusDeploymentEnvironment);
    }

    @Test
    public void detectEnvironment_returns_null_if_known_system_props_are_null() {
        // given
        setEnvironmentSystemProps(null, null);

        // expect
        assertThat(AppInfoImpl.detectEnvironment()).isNull();
    }

    @Test
    public void createLocalInstance_with_appId_arg_creates_instance_with_expected_values() throws UnknownHostException {
        // given
        String appId = UUID.randomUUID().toString();

        // when
        AppInfoImpl localInstance = AppInfoImpl.createLocalInstance(appId);

        // then
        assertThat(localInstance.appId).isEqualTo(appId);
        assertThat(localInstance.environment).isEqualTo("local");
        assertThat(localInstance.dataCenter).isEqualTo("local");
        assertThat(localInstance.instanceId).isEqualTo(AppInfoImpl.getLocalHostName());
    }

    @Test
    public void createLocalInstance_with_appId_uses_UNKNOWN_VALUE_for_local_hostname_if_it_throws_UnknownHostException() throws UnknownHostException {
        AppInfoImpl instanceForStaticVariableReflectionJunk = new AppInfoImpl("testappid", "testenvironment", "nodatacenter", "someinstanceid");
        String localHostnameGetterStaticVariableName = "LOCAL_HOSTNAME_GETTER";
        AppInfoImpl.LocalHostnameGetter existingLocalHostnameGetter =
                (AppInfoImpl.LocalHostnameGetter) Whitebox.getInternalState(instanceForStaticVariableReflectionJunk, localHostnameGetterStaticVariableName);

        try {
            // given
            String appId = UUID.randomUUID().toString();
            AppInfoImpl.LocalHostnameGetter localHostnameGetterMock = mock(AppInfoImpl.LocalHostnameGetter.class);
            doThrow(new UnknownHostException("intentionaltestexplosion")).when(localHostnameGetterMock).getLocalHostname();
            Whitebox.setInternalState(instanceForStaticVariableReflectionJunk, localHostnameGetterStaticVariableName, localHostnameGetterMock);

            // when
            AppInfoImpl localInstance = AppInfoImpl.createLocalInstance(appId);

            // then
            assertThat(localInstance.appId).isEqualTo(appId);
            assertThat(localInstance.environment).isEqualTo("local");
            assertThat(localInstance.dataCenter).isEqualTo("local");
            assertThat(localInstance.instanceId).isEqualTo(AppInfo.UNKNOWN_VALUE);
        }
        finally {
            Whitebox.setInternalState(instanceForStaticVariableReflectionJunk, localHostnameGetterStaticVariableName, existingLocalHostnameGetter);
        }
    }

    @Test
    public void createLocalInstance_no_args_throws_IllegalStateException_if_detectAppId_returns_null() {
        // given
        setAppIdSystemProps(null, null, null);

        // when
        Throwable thrown = catchThrowable(AppInfoImpl::createLocalInstance);

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("Unable to autodetect app ID");
    }

    @Test
    public void createLocalInstance_no_args_creates_local_instance_using_detected_appId() {
        // given
        String appId = UUID.randomUUID().toString();
        setAppIdSystemProps(appId, null, null);
        AppInfoImpl localInstanceWithExpectedAppId = AppInfoImpl.createLocalInstance(appId);

        // when
        AppInfoImpl actualLocalInstance = AppInfoImpl.createLocalInstance();

        // then
        assertThat(actualLocalInstance.appId).isEqualTo(localInstanceWithExpectedAppId.appId);
        assertThat(actualLocalInstance.environment).isEqualTo(localInstanceWithExpectedAppId.environment);
        assertThat(actualLocalInstance.dataCenter).isEqualTo(localInstanceWithExpectedAppId.dataCenter);
        assertThat(actualLocalInstance.instanceId).isEqualTo(localInstanceWithExpectedAppId.instanceId);
    }
}