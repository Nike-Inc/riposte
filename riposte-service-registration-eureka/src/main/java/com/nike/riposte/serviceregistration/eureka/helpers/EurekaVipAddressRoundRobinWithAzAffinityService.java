package com.nike.riposte.serviceregistration.eureka.helpers;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.DiscoveryClient;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
/**
 * Service that uses the Eureka discovery client to provide all the instances associated with a VIP name ({@link
 * #getActiveInstanceInfosForVipAddress(String, Optional)}) in the current availability zone., or the "next" instance
 * that should be called using round-robin strategy ({@link #getActiveInstanceInfoForVipAddress(String, Optional)} in
 * the current availability zone.
 * If there are no instances in the current availability zone, this method will round-robin the instances in all
 * availability zones.
 * <p/>
 * NOTE: The round-robin counters are on a per-EurekaVipAddressRoundRobinService basis. Each new instance of this class
 * will do its own separate round-robin counting. Therefore you should use a single instance of this class anywhere you
 * want the round robin effect.
 *
 */
@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType"})
public class EurekaVipAddressRoundRobinWithAzAffinityService extends EurekaVipAddressRoundRobinService {
    protected final String currentAvailabilityZone;

    @Inject
    public EurekaVipAddressRoundRobinWithAzAffinityService(
            @Named("eurekaRoundRobinDiscoveryClientSupplier") Supplier<DiscoveryClient> discoveryClientSupplier,
            @Named("currentAvailabilityZone") String currentAvailabilityZone
    ) {
        super(discoveryClientSupplier);
        this.currentAvailabilityZone = currentAvailabilityZone;
    }

    /**
     * Round-robins the instances in the current availability zone returned by
     * {@link #getActiveInstanceInfosForVipAddressBlocking(String)} to determine the *next* active {@link InstanceInfo}
     * that should be called for the given VIP name. If there are no instances in the current availability zone, this
     * method will round-robin the instances in all availability zones.
     *
     * @return The *next* active {@link InstanceInfo} that should be called for the given VIP name (using a round-robin
     * strategy with current availability zone affinity), or an empty Optional if no active instances were found.
     */
    @Override
    public Optional<InstanceInfo> getActiveInstanceInfoForVipAddressBlocking(String vipName) {
        List<InstanceInfo> instancesByVipAddress = getActiveInstanceInfosForVipAddressBlocking(vipName);

        if (instancesByVipAddress.isEmpty())
            return Optional.empty();

        List<InstanceInfo> filteredInstanceList = calculateInstanceListWithAzAffinity(instancesByVipAddress);
        return roundRobinInstanceList(vipName, filteredInstanceList);
    }

    /**
     *
     * @param fullInstanceList list of all instances in the current region
     * @return list of all instances in the current availability zone. if there are none, it will return the fullInstanceList
     */
    protected List<InstanceInfo> calculateInstanceListWithAzAffinity(List<InstanceInfo> fullInstanceList) {
        List<InstanceInfo> currentAzList = fullInstanceList.stream().
                filter(instanceInfo ->
                        currentAvailabilityZone.equalsIgnoreCase(instanceInfo.getMetadata().get("availability-zone"))
                ).
                collect(Collectors.toList());
        return currentAzList.isEmpty() ? fullInstanceList : currentAzList;
    }
}
