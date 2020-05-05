package com.nike.riposte.serviceregistration.eureka.helpers;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.DiscoveryClient;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import org.assertj.core.util.Lists;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.nike.riposte.testutils.Whitebox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link EurekaVipAddressRoundRobinService}
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class EurekaVipAddressServiceTest {

    private String vip;
    private DiscoveryClient discoveryClientMock;
    private EurekaVipAddressRoundRobinService serviceSpy;

    @Before
    public void beforeMethod() {
        vip = UUID.randomUUID().toString();
        discoveryClientMock = mock(DiscoveryClient.class);
        serviceSpy = spy(new EurekaVipAddressRoundRobinService(() -> discoveryClientMock));
    }

    @Test
    public void getActiveInstanceInfosForVipAddressBlocking_retrieves_active_InstanceInfos_by_vip_from_discovery_client() {
        // given
        InstanceInfo active1 = mock(InstanceInfo.class);
        InstanceInfo active2 = mock(InstanceInfo.class);
        InstanceInfo down = mock(InstanceInfo.class);
        InstanceInfo outOfService = mock(InstanceInfo.class);
        InstanceInfo starting = mock(InstanceInfo.class);
        InstanceInfo unknown = mock(InstanceInfo.class);
        doReturn(InstanceInfo.InstanceStatus.UP).when(active1).getStatus();
        doReturn(InstanceInfo.InstanceStatus.UP).when(active2).getStatus();
        doReturn(InstanceInfo.InstanceStatus.DOWN).when(down).getStatus();
        doReturn(InstanceInfo.InstanceStatus.OUT_OF_SERVICE).when(outOfService).getStatus();
        doReturn(InstanceInfo.InstanceStatus.STARTING).when(starting).getStatus();
        doReturn(InstanceInfo.InstanceStatus.UNKNOWN).when(unknown).getStatus();
        List<InstanceInfo> allInstancesForVip = Arrays.asList(active1, down, outOfService, active2, starting, unknown);
        doReturn(allInstancesForVip).when(discoveryClientMock).getInstancesByVipAddress(vip, false);

        // when
        List<InstanceInfo> result = serviceSpy.getActiveInstanceInfosForVipAddressBlocking(vip);

        // then
        List<InstanceInfo> expected = Arrays.asList(active1, active2);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    public void getActiveInstanceInfosForVipAddressBlocking_returns_empty_list_if_discovery_client_returns_null() {
        // given
        doReturn(null).when(discoveryClientMock).getInstancesByVipAddress(vip, false);

        // when
        List<InstanceInfo> result = serviceSpy.getActiveInstanceInfosForVipAddressBlocking(vip);

        // then
        assertThat(result)
            .isNotNull()
            .isEmpty();
    }

    @Test
    public void getActiveInstanceInfosForVipAddressBlocking_returns_empty_list_if_discovery_client_returns_empty_list() {
        // given
        doReturn(Collections.emptyList()).when(discoveryClientMock).getInstancesByVipAddress(vip, false);

        // when
        List<InstanceInfo> result = serviceSpy.getActiveInstanceInfosForVipAddressBlocking(vip);

        // then
        assertThat(result)
            .isNotNull()
            .isEmpty();
    }

    private static class SpyableExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            Executors.newSingleThreadExecutor().execute(command);
        }
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void getActiveInstanceInfosForVipAddress_returns_future_that_returns_data_from_getActiveInstanceInfosForVipAddressBlocking(boolean useExecutor) {
        // given
        InstanceInfo iiMock = mock(InstanceInfo.class);
        List<InstanceInfo> expectedInstances = Collections.singletonList(iiMock);
        doReturn(expectedInstances).when(serviceSpy).getActiveInstanceInfosForVipAddressBlocking(vip);
        Optional<Executor> executorOpt = useExecutor ? Optional.of(Executors.newSingleThreadExecutor()) : Optional.empty();

        // when
        CompletableFuture<List<InstanceInfo>> result = serviceSpy.getActiveInstanceInfosForVipAddress(vip, executorOpt);

        // then
        assertThat(result.join()).isEqualTo(expectedInstances);
    }

    @Test
    public void getActiveInstanceInfosForVipAddress_uses_provided_executor_for_future_if_provided_one() {
        // given
        InstanceInfo iiMock = mock(InstanceInfo.class);
        List<InstanceInfo> expectedInstances = Collections.singletonList(iiMock);
        doReturn(expectedInstances).when(serviceSpy).getActiveInstanceInfosForVipAddressBlocking(vip);
        Executor executorSpy = spy(new SpyableExecutor());

        // when
        CompletableFuture<List<InstanceInfo>> result = serviceSpy.getActiveInstanceInfosForVipAddress(vip, Optional.of(executorSpy));

        // then
        assertThat(result.join()).isEqualTo(expectedInstances);
        verify(executorSpy).execute(any(Runnable.class));
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void getActiveInstanceInfoForVipAddress_returns_future_that_returns_data_from_getActiveInstanceInfoForVipAddressBlocking(boolean useExecutor) {
        // given
        InstanceInfo iiMock = mock(InstanceInfo.class);
        doReturn(Optional.of(iiMock)).when(serviceSpy).getActiveInstanceInfoForVipAddressBlocking(vip);
        Optional<Executor> executorOpt = useExecutor ? Optional.of(Executors.newSingleThreadExecutor()) : Optional.empty();

        // when
        CompletableFuture<Optional<InstanceInfo>> result = serviceSpy.getActiveInstanceInfoForVipAddress(vip, executorOpt);

        // then
        assertThat(result.join()).isEqualTo(Optional.of(iiMock));
    }

    @Test
    public void getActiveInstanceInfoForVipAddress_uses_provided_executor_for_future_if_provided_one() {
        // given
        InstanceInfo iiMock = mock(InstanceInfo.class);
        doReturn(Optional.of(iiMock)).when(serviceSpy).getActiveInstanceInfoForVipAddressBlocking(vip);
        Executor executorSpy = spy(new SpyableExecutor());

        // when
        CompletableFuture<Optional<InstanceInfo>> result = serviceSpy.getActiveInstanceInfoForVipAddress(vip, Optional.of(executorSpy));

        // then
        assertThat(result.join()).isEqualTo(Optional.of(iiMock));
        verify(executorSpy).execute(any(Runnable.class));
    }

    @Test
    public void roundRobinInstanceListWhenWeExperienceIntegerOverflowTest() {
        // given
        InstanceInfo iiMock = mock(InstanceInfo.class);
        List<InstanceInfo> instances = Lists.newArrayList(iiMock, iiMock, iiMock);
        serviceSpy.vipRoundRobinCounterMap.put("myVip", new AtomicInteger(Integer.MAX_VALUE));
        // when
        Optional<InstanceInfo> result1 = serviceSpy.roundRobinInstanceList("myVip", instances);
        Optional<InstanceInfo> result2 = serviceSpy.roundRobinInstanceList("myVip", instances);
        Optional<InstanceInfo> result3 = serviceSpy.roundRobinInstanceList("myVip", instances);
        // then
        assertThat(result1).isNotEmpty();
        assertThat(result2).isNotEmpty();
        assertThat(result3).isNotEmpty();
        assertThat(serviceSpy.vipRoundRobinCounterMap.get("myVip").intValue()).isEqualTo(1);
    }
}