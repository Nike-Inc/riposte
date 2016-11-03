package com.nike.riposte.serviceregistration.eureka.helpers;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.DiscoveryClient;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link EurekaVipAddressRoundRobinService}
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class EurekaVipAddressRoundRobinServiceTest {

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

    private List<InstanceInfo> generateListWithMockInstanceInfos(int numInstanceInfos) {
        List<InstanceInfo> instanceInfos = new ArrayList<>();
        for (int i = 0; i < numInstanceInfos; i++) {
            InstanceInfo iiMock = mock(InstanceInfo.class);
            doReturn(InstanceInfo.InstanceStatus.UP).when(iiMock).getStatus();
            instanceInfos.add(iiMock);
        }
        return instanceInfos;
    }

    @Test
    public void getActiveInstanceInfoForVipAddressBlocking_uses_round_robin_strategy_per_vip() {
        // given
        String vip1 = UUID.randomUUID().toString();
        String vip2 = UUID.randomUUID().toString();
        int vip1Counter = 0;
        int vip2Counter = 0;
        int numVip1Instances = 3;
        int numVip2Instances = 5;
        List<InstanceInfo> vip1Instances = generateListWithMockInstanceInfos(numVip1Instances);
        List<InstanceInfo> vip2Instances = generateListWithMockInstanceInfos(numVip2Instances);
        doReturn(vip1Instances).when(discoveryClientMock).getInstancesByVipAddress(vip1, false);
        doReturn(vip2Instances).when(discoveryClientMock).getInstancesByVipAddress(vip2, false);

        for (int i = 0; i < 1000; i++) {
            // when
            InstanceInfo v1Instance = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip1).get();
            InstanceInfo v2Instance = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip2).get();

            // then
            int expectedV1Index = vip1Counter % numVip1Instances;
            int expectedV2Index = vip2Counter % numVip2Instances;
            assertThat(v1Instance).isEqualTo(vip1Instances.get(expectedV1Index));
            assertThat(v2Instance).isEqualTo(vip2Instances.get(expectedV2Index));

            vip1Counter++;
            vip2Counter++;
        }
    }

    @Test
    public void getActiveInstanceInfoForVipAddressBlocking_returns_empty_if_no_instances_are_active() {
        // given
        InstanceInfo down = mock(InstanceInfo.class);
        doReturn(InstanceInfo.InstanceStatus.DOWN).when(down).getStatus();
        List<InstanceInfo> allInstancesForVip = Collections.singletonList(down);
        doReturn(allInstancesForVip).when(discoveryClientMock).getInstancesByVipAddress(vip, false);

        // when
        Optional<InstanceInfo> result = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    public void getActiveInstanceInfoForVipAddressBlocking_uses_ConcurrentMap_and_computeIfAbsent_to_generate_and_track_round_robin_counters() {
        // given
        InstanceInfo active1 = mock(InstanceInfo.class);
        InstanceInfo active2 = mock(InstanceInfo.class);
        doReturn(InstanceInfo.InstanceStatus.UP).when(active1).getStatus();
        doReturn(InstanceInfo.InstanceStatus.UP).when(active2).getStatus();
        List<InstanceInfo> allInstancesForVip = Arrays.asList(active1, active2);
        doReturn(allInstancesForVip).when(discoveryClientMock).getInstancesByVipAddress(vip, false);

        assertThat(serviceSpy.vipRoundRobinCounterMap).isInstanceOf(ConcurrentMap.class);
        assertThat(serviceSpy.vipRoundRobinCounterMap.containsKey(vip)).isFalse();
        ConcurrentMap<String, AtomicInteger> roundRobinCounterMapSpy = spy(serviceSpy.vipRoundRobinCounterMap);
        Whitebox.setInternalState(serviceSpy, "vipRoundRobinCounterMap", roundRobinCounterMapSpy);

        // when
        serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);

        // then
        // The first time we should get a new AtomicInteger instance set at 1 (started at 0 and then incremented).
        verify(roundRobinCounterMapSpy).computeIfAbsent(eq(vip), any(Function.class));
        AtomicInteger vipCounterFirstCall = roundRobinCounterMapSpy.get(vip);
        assertThat(vipCounterFirstCall.get()).isEqualTo(1);

        // and when
        reset(roundRobinCounterMapSpy);
        serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);

        // then
        // The second time we should get the exact same AtomicInteger instance, but it should be set to 2 (incremented since previous call).
        verify(roundRobinCounterMapSpy).computeIfAbsent(eq(vip), any(Function.class));
        AtomicInteger vipCounterSecondCall = roundRobinCounterMapSpy.get(vip);
        assertThat(vipCounterSecondCall).isSameAs(vipCounterFirstCall);
        assertThat(vipCounterSecondCall.get()).isEqualTo(2);
    }
}