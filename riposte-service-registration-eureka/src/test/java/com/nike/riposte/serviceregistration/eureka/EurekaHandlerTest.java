package com.nike.riposte.serviceregistration.eureka;

import com.nike.riposte.serviceregistration.eureka.EurekaHandler.ServiceStatus;

import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.EurekaClientConfig;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import com.nike.riposte.testutils.Whitebox;

import java.util.function.Supplier;

import static com.netflix.appinfo.DataCenterInfo.Name.MyOwn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link EurekaHandler}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class EurekaHandlerTest {

    private EurekaHandler handlerSpy;
    private Supplier<Boolean> eurekaIsDisabledPropertySupplierMock;
    private Supplier<String> datacenterTypePropertySupplierMock;
    private CloudInstanceConfig cloudInstanceConfigMock;

    @Before
    public void beforeMethod() {
        eurekaIsDisabledPropertySupplierMock = mock(Supplier.class);
        datacenterTypePropertySupplierMock = mock(Supplier.class);
        cloudInstanceConfigMock = mock(CloudInstanceConfig.class);

        doReturn(false).when(eurekaIsDisabledPropertySupplierMock).get();
        doReturn(MyOwn.name()).when(datacenterTypePropertySupplierMock).get();

        handlerSpy = spy(new EurekaHandler(eurekaIsDisabledPropertySupplierMock, datacenterTypePropertySupplierMock));

        doNothing().when(handlerSpy).initDiscoveryManager(any(EurekaInstanceConfig.class),
                                                          any(EurekaClientConfig.class));
        doNothing().when(handlerSpy).shutdownDiscoveryManager();
        doNothing().when(handlerSpy).setEurekaInstanceStatus(any(InstanceStatus.class));
        doReturn(cloudInstanceConfigMock).when(handlerSpy).createCloudInstanceConfig(anyString());
    }

    @Test
    public void code_coverage_hoops() {
        // jump!
        for (ServiceStatus status : ServiceStatus.values()) {
            assertThat(ServiceStatus.valueOf(status.name())).isEqualTo(status);
        }
    }

    @Test
    public void register_uses_createEurekaInstanceConfig_then_calls_initDiscoveryManager_with_it_and_sets_instance_status_UP() {
        // given
        EurekaInstanceConfig instanceConfigMock = mock(EurekaInstanceConfig.class);
        doReturn(instanceConfigMock).when(handlerSpy).createEurekaInstanceConfig();
        assertThat(handlerSpy.registered.get()).isFalse();

        // when
        handlerSpy.register();

        // then
        assertThat(handlerSpy.registered.get()).isTrue();
        verify(handlerSpy).createEurekaInstanceConfig();

        ArgumentCaptor<EurekaClientConfig> clientConfigCaptor = ArgumentCaptor.forClass(EurekaClientConfig.class);
        verify(handlerSpy).initDiscoveryManager(eq(instanceConfigMock),
                                                clientConfigCaptor.capture());
        EurekaClientConfig clientConfigUsed = clientConfigCaptor.getValue();
        assertThat(clientConfigUsed).isInstanceOf(DefaultEurekaClientConfig.class);
        assertThat(Whitebox.getInternalState(clientConfigUsed, "namespace")).isEqualTo(handlerSpy.eurekaClientNamespace);

        verify(handlerSpy).setEurekaInstanceStatus(InstanceStatus.UP);
    }

    @Test
    public void register_does_nothing_if_eureka_is_disabled() {
        // given
        doReturn(true).when(eurekaIsDisabledPropertySupplierMock).get();

        // when
        handlerSpy.register();

        // then
        verify(handlerSpy, never()).createEurekaInstanceConfig();
        verify(handlerSpy, never()).initDiscoveryManager(any(EurekaInstanceConfig.class),
                                                         any(EurekaClientConfig.class));
        verify(handlerSpy, never()).setEurekaInstanceStatus(any(InstanceStatus.class));
    }

    @Test
    public void register_does_nothing_if_already_registered() {
        // given
        handlerSpy.registered.set(true);

        // when
        handlerSpy.register();

        // then
        verify(handlerSpy, never()).createEurekaInstanceConfig();
        verify(handlerSpy, never()).initDiscoveryManager(any(EurekaInstanceConfig.class),
                                                         any(EurekaClientConfig.class));
        verify(handlerSpy, never()).setEurekaInstanceStatus(any(InstanceStatus.class));
    }

    @DataProvider(value = {
        "null",
        "MyOwn",
        "not-a-real-datacenter-type"
    })
    @Test
    public void createEurekaInstanceConfig_returns_MyDataCenterInstanceConfig_when_datacenterType_is_null_or_MyOwn_or_invalid(
        String datacenterType
    ) {
        // given
        doReturn(datacenterType).when(datacenterTypePropertySupplierMock).get();

        // when
        EurekaInstanceConfig instanceConfig = handlerSpy.createEurekaInstanceConfig();

        // then
        assertThat(instanceConfig).isInstanceOf(MyDataCenterInstanceConfig.class);
        assertThat(Whitebox.getInternalState(instanceConfig, "namespace")).isEqualTo(handlerSpy.eurekaClientNamespace);
    }

    @DataProvider(value = {
        "Amazon",
        "Netflix"
    })
    @Test
    public void createEurekaInstanceConfig_returns_CloudInstanceConfig_when_datacenterType_is_amazon_or_netflix(
        String datacenterType
    ) {
        // given
        doReturn(datacenterType).when(datacenterTypePropertySupplierMock).get();

        // when
        EurekaInstanceConfig instanceConfig = handlerSpy.createEurekaInstanceConfig();

        // then
        verify(handlerSpy).createCloudInstanceConfig(handlerSpy.eurekaNamespace);
        assertThat(instanceConfig).isSameAs(cloudInstanceConfigMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void markAsUp_sets_instance_status_to_up_only_if_eureka_is_enabled(
        boolean eurekaDisabled
    ) {
        // given
        doReturn(eurekaDisabled).when(eurekaIsDisabledPropertySupplierMock).get();

        // when
        handlerSpy.markAsUp();

        // then
        if (eurekaDisabled)
            verify(handlerSpy, never()).setEurekaInstanceStatus(any(InstanceStatus.class));
        else
            verify(handlerSpy).setEurekaInstanceStatus(InstanceStatus.UP);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void markAsDown_calls_shutdownDiscoveryManager_only_if_eureka_is_enabled(
        boolean eurekaDisabled
    ) {
        // given
        doReturn(eurekaDisabled).when(eurekaIsDisabledPropertySupplierMock).get();

        // when
        handlerSpy.markAsDown();

        // then
        if (eurekaDisabled)
            verify(handlerSpy, never()).shutdownDiscoveryManager();
        else
            verify(handlerSpy).shutdownDiscoveryManager();
    }

    @DataProvider(value = {
        "UP",
        "DOWN"
    })
    @Test
    public void updateStatus_calls_markAsUp_or_markAsDown_as_appropriate(ServiceStatus status) {
        // when
        handlerSpy.updateStatus(status);

        // then
        switch (status) {
            case UP:
                verify(handlerSpy).markAsUp();
                break;
            case DOWN:
                verify(handlerSpy).markAsDown();
                break;
            default:
                throw new IllegalArgumentException("Unhandled ServiceStatus: " + status.name());
        }
    }

    @Test
    public void updateStatus_throws_IllegalArgumentException_if_passed_null() {
        // when
        Throwable ex = catchThrowable(() -> handlerSpy.updateStatus(null));

        // then
        assertThat(ex).isInstanceOf(IllegalArgumentException.class);
    }
}