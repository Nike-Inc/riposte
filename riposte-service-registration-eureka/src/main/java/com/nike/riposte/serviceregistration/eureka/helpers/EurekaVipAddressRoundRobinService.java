package com.nike.riposte.serviceregistration.eureka.helpers;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.DiscoveryClient;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Service that uses the Eureka discovery client to provide all the instances associated with a VIP name ({@link
 * #getActiveInstanceInfosForVipAddress(String, Optional)}), or the "next" instance that should be called using
 * round-robin strategy ({@link #getActiveInstanceInfoForVipAddress(String, Optional)}.
 * <p/>
 * NOTE: If you need your requests routed to instances in the same availability zone, use
 * {@link EurekaVipAddressRoundRobinWithAzAffinityService}
 * <p/>
 * NOTE: The round-robin counters are on a per-EurekaVipAddressRoundRobinService basis. Each new instance of this class
 * will do its own separate round-robin counting. Therefore you should use a single instance of this class anywhere you
 * want the round robin effect.
 *
 * @author Nic Munroe
 */
@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType"})
public class EurekaVipAddressRoundRobinService extends EurekaVipAddressService {
    protected final ConcurrentMap<String, AtomicInteger> vipRoundRobinCounterMap = new ConcurrentHashMap<>();

    @Inject
    public EurekaVipAddressRoundRobinService(@Named("eurekaRoundRobinDiscoveryClientSupplier")
                                             Supplier<DiscoveryClient> discoveryClientSupplier) {
        super(discoveryClientSupplier);
    }

    /**
     * Round-robins the instances returned by {@link #getActiveInstanceInfosForVipAddressBlocking(String)} to determine
     * the *next* active {@link InstanceInfo} that should be called for the given VIP name
     *
     * @return The *next* active {@link InstanceInfo} that should be called for the given VIP name (using a round-robin
     * strategy), or an empty Optional if no active instances were found.
     */
    @Override
    public Optional<InstanceInfo> getActiveInstanceInfoForVipAddressBlocking(String vipName) {
        List<InstanceInfo> instancesByVipAddress = getActiveInstanceInfosForVipAddressBlocking(vipName);

        if (instancesByVipAddress.isEmpty())
            return Optional.empty();

        return roundRobinInstanceList(vipName, instancesByVipAddress);
    }

    protected Optional<InstanceInfo> roundRobinInstanceList(String vipName, List<InstanceInfo> instanceList) {
        // Found at least one "up" instance at this VIP. Grab the AtomicInteger associated with this VIP
        //      (map a new one if necessary).
        AtomicInteger roundRobinCounter = vipRoundRobinCounterMap.computeIfAbsent(vipName, vip -> new AtomicInteger(0));

        // Atomically get-and-increment the atomic int associated with this VIP, then mod it against the number of
        //      instances available. This effectively round robins the use of all the instances associated with the VIP.
        int instanceIndexToUse = roundRobinCounter.getAndIncrement() % instanceList.size();

        if (instanceIndexToUse < 0) {
            // The counter went high enough to do an integer overflow. Fix the index so we don't blow up this call,
            //      and reset the counter to 0.
            instanceIndexToUse = Math.abs(instanceIndexToUse);
            roundRobinCounter.set(0);
        }

        return Optional.of(instanceList.get(instanceIndexToUse));
    }
}
