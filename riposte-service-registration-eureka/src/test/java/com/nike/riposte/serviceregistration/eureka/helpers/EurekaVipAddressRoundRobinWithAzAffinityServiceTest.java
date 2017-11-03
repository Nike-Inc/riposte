package com.nike.riposte.serviceregistration.eureka.helpers;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.DiscoveryClient;
import org.assertj.core.util.Lists;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

@SuppressWarnings("ConstantConditions")
public class EurekaVipAddressRoundRobinWithAzAffinityServiceTest {

    private String vip;
    private DiscoveryClient discoveryClientMock;
    private EurekaVipAddressRoundRobinWithAzAffinityService serviceSpy;

    @Before
    public void beforeMethod() {
        vip = UUID.randomUUID().toString();
        discoveryClientMock = mock(DiscoveryClient.class);
        serviceSpy = spy(new EurekaVipAddressRoundRobinWithAzAffinityService(() -> discoveryClientMock, "us-west-2a"));
    }

    @Test
    public void zeroInstances() {
        // given
        List<InstanceInfo> expectedInstances = Collections.emptyList();
        doReturn(expectedInstances).when(serviceSpy).getActiveInstanceInfosForVipAddressBlocking(vip);
        // when
        Optional<InstanceInfo> result = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);
        //then
        assertThat(result).isEmpty();
    }

    @Test
    public void roundRobinsSingleInstanceInAzAndIgnoresOtherAzInstances() {
        // given
        List<InstanceInfo> expectedInstances = generateListWithMockInstanceInfos(Lists.newArrayList("us-west-2a", "us-west-2b", "us-west-2c"));
        doReturn(expectedInstances).when(serviceSpy).getActiveInstanceInfosForVipAddressBlocking(vip);

        // when
        Optional<InstanceInfo> result1 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);
        Optional<InstanceInfo> result2 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);
        Optional<InstanceInfo> result3 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);
        Optional<InstanceInfo> result4 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);

        // then
        assertThat(result1).isNotEmpty();
        assertThat(result1.get()).isEqualTo(expectedInstances.get(0));
        assertThat(result2).isNotEmpty();
        assertThat(result2.get()).isEqualTo(expectedInstances.get(0));
        assertThat(result3).isNotEmpty();
        assertThat(result3.get()).isEqualTo(expectedInstances.get(0));
        assertThat(result4).isNotEmpty();
        assertThat(result4.get()).isEqualTo(expectedInstances.get(0));
    }

    @Test
    public void roundRobinsDoesNotTriggerNPEWhenNoAvailabilityZoneIsPresentInTheMetadata() {
        // given
        InstanceInfo iiMock = mock(InstanceInfo.class);
        doReturn(new HashMap<>()).when(iiMock).getMetadata();
        doReturn(InstanceInfo.InstanceStatus.UP).when(iiMock).getStatus();
        List<InstanceInfo> expectedInstances = Lists.newArrayList(iiMock);
        doReturn(expectedInstances).when(serviceSpy).getActiveInstanceInfosForVipAddressBlocking(vip);

        // when
        Optional<InstanceInfo> result = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);

        // then
        assertThat(result).isNotEmpty();
        assertThat(result.get()).isEqualTo(expectedInstances.get(0));
    }

    @Test
    public void roundRobinsMultipleInstancesInAzAndIgnoresOtherAzInstances() {
        // given
        List<InstanceInfo> expectedInstances = generateListWithMockInstanceInfos(Lists.newArrayList("us-west-2a", "us-west-2a", "us-west-2a", "us-west-2b", "us-west-2b", "us-west-2c", "us-west-2c"));
        doReturn(expectedInstances).when(serviceSpy).getActiveInstanceInfosForVipAddressBlocking(vip);

        // when
        Optional<InstanceInfo> result1 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);
        Optional<InstanceInfo> result2 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);
        Optional<InstanceInfo> result3 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);
        Optional<InstanceInfo> result4 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);

        // then
        assertThat(result1).isNotEmpty();
        assertThat(result1.get()).isEqualTo(expectedInstances.get(0));
        assertThat(result2).isNotEmpty();
        assertThat(result2.get()).isEqualTo(expectedInstances.get(1));
        assertThat(result3).isNotEmpty();
        assertThat(result3.get()).isEqualTo(expectedInstances.get(2));
        assertThat(result4).isNotEmpty();
        assertThat(result4.get()).isEqualTo(expectedInstances.get(0));
    }

    @Test
    public void roundRobinsOriginalListWhenNoInstancesAreInSameAZ() {
        // given
        List<InstanceInfo> expectedInstances = generateListWithMockInstanceInfos(Lists.newArrayList("us-west-2b", "us-west-2b", "us-west-2c"));
        doReturn(expectedInstances).when(serviceSpy).getActiveInstanceInfosForVipAddressBlocking(vip);

        // when
        Optional<InstanceInfo> result1 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);
        Optional<InstanceInfo> result2 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);
        Optional<InstanceInfo> result3 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);
        Optional<InstanceInfo> result4 = serviceSpy.getActiveInstanceInfoForVipAddressBlocking(vip);

        // then
        assertThat(result1).isNotEmpty();
        assertThat(result1.get()).isEqualTo(expectedInstances.get(0));
        assertThat(result2).isNotEmpty();
        assertThat(result2.get()).isEqualTo(expectedInstances.get(1));
        assertThat(result3).isNotEmpty();
        assertThat(result3.get()).isEqualTo(expectedInstances.get(2));
        assertThat(result4).isNotEmpty();
        assertThat(result4.get()).isEqualTo(expectedInstances.get(0));
    }

    private List<InstanceInfo> generateListWithMockInstanceInfos(List<String> availabilityZones) {
        List<InstanceInfo> instanceInfos = new ArrayList<>();
        for (String az : availabilityZones) {
            InstanceInfo iiMock = mock(InstanceInfo.class);
            Map<String, String> metaData = new HashMap<>();
            metaData.put("availability-zone", az);
            doReturn(metaData).when(iiMock).getMetadata();
            doReturn(InstanceInfo.InstanceStatus.UP).when(iiMock).getStatus();
            instanceInfos.add(iiMock);
        }
        return instanceInfos;
    }
}
