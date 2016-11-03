package com.nike.riposte.serviceregistration.eureka.helpers;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.DiscoveryClient;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Service that uses the Eureka discovery client to provide all the instances associated with a VIP name ({@link
 * #getActiveInstanceInfosForVipAddress(String, Optional)}), or the "next" instance that should be called using
 * round-robin strategy ({@link #getActiveInstanceInfoForVipAddress(String, Optional)}.
 * <p/>
 * NOTE: The round-robin counters are on a per-EurekaVipAddressRoundRobinService basis. Each new instance of this class
 * will do its own separate round-robin counting. Therefore you should use a single instance of this class anywhere you
 * want the round robin effect.
 *
 * @author Nic Munroe
 */
@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType"})
public class EurekaVipAddressRoundRobinService {

    protected final ConcurrentMap<String, AtomicInteger> vipRoundRobinCounterMap = new ConcurrentHashMap<>();
    protected final Supplier<DiscoveryClient> discoveryClientSupplier;
    protected DiscoveryClient discoveryClientCache;

    @Inject
    public EurekaVipAddressRoundRobinService(@Named("eurekaRoundRobinDiscoveryClientSupplier")
                                             Supplier<DiscoveryClient> discoveryClientSupplier) {
        this.discoveryClientSupplier = discoveryClientSupplier;
    }

    /**
     * A potentially blocking method that returns the list of *active* {@link InstanceInfo} objects eureka has
     * associated with the given VIP name (i.e. instances whose state is {@link InstanceInfo.InstanceStatus#UP}), or an
     * empty list if no such instances were found. Will never return null.
     * <p/>
     * The eureka discovery client has an internal cache, so this method may return quickly, but if the cache doesn't
     * have info on the VIP or needs to be refreshed then this method could take as long it takes the eureka discovery
     * client to do the necessary network calls.
     * <p/>
     * TODO: See if Netflix has a newer version of the Eureka client that allows for async nonblocking retrieval via futures.
     */
    public List<InstanceInfo> getActiveInstanceInfosForVipAddressBlocking(String vipName) {
        // Lookup instances by VIP address (the Eureka client uses an internal cache, so this won't necessarily incur
        //      the cost of a network call)
        List<InstanceInfo> instancesByVipAddress = discoveryClient().getInstancesByVipAddress(vipName, false);

        // Return an empty list if no registration exists for this VIP
        if (instancesByVipAddress == null || instancesByVipAddress.isEmpty())
            return Collections.emptyList();

        // Filter down to just instances that are marked with "up" status.
        return instancesByVipAddress.stream()
                                    .filter(ii -> ii.getStatus().equals(InstanceInfo.InstanceStatus.UP))
                                    .collect(Collectors.toList());
    }

    /**
     * Round-robins the instances returned by {@link #getActiveInstanceInfosForVipAddressBlocking(String)} to determine
     * the *next* active {@link InstanceInfo} that should be called for the given VIP name
     *
     * @return The *next* active {@link InstanceInfo} that should be called for the given VIP name (using a round-robin
     * strategy), or an empty Optional if no active instances were found.
     */
    public Optional<InstanceInfo> getActiveInstanceInfoForVipAddressBlocking(String vipName) {
        List<InstanceInfo> instancesByVipAddress = getActiveInstanceInfosForVipAddressBlocking(vipName);

        if (instancesByVipAddress.isEmpty())
            return Optional.empty();

        // Found at least one "up" instance at this VIP. Grab the AtomicInteger associated with this VIP
        //      (map a new one if necessary).
        AtomicInteger roundRobinCounter = vipRoundRobinCounterMap.computeIfAbsent(vipName, vip -> new AtomicInteger(0));

        // Atomically get-and-increment the atomic int associated with this VIP, then mod it against the number of
        //      instances available. This effectively round robins the use of all the instances associated with the VIP.
        int instanceIndexToUse = roundRobinCounter.getAndIncrement() % instancesByVipAddress.size();

        if (instanceIndexToUse < 0) {
            // The counter went high enough to do an integer overflow. Fix the index so we don't blow up this call,
            //      and reset the counter to 0.
            instanceIndexToUse = Math.abs(instanceIndexToUse);
            roundRobinCounter.set(0);
        }

        return Optional.of(instancesByVipAddress.get(instanceIndexToUse));
    }

    /**
     * @return A {@link CompletableFuture} that when it completes will return the list of *active* {@link InstanceInfo}
     * objects eureka has associated with the given VIP name (i.e. instances whose state is {@link
     * InstanceInfo.InstanceStatus#UP}), or an empty list if no such instances were found (will never return null). If
     * you pass in an {@link Executor} for the {@code longRunningTaskExecutor} argument then it will be used to execute
     * the future, otherwise the default will be used (typically the Java common fork-join pool).
     */
    public CompletableFuture<List<InstanceInfo>> getActiveInstanceInfosForVipAddress(
        String vipName, Optional<Executor> longRunningTaskExecutor
    ) {
        return longRunningTaskExecutor
            .map(executor -> CompletableFuture.supplyAsync(
                () -> getActiveInstanceInfosForVipAddressBlocking(vipName), executor)
            )
            .orElse(
                CompletableFuture.supplyAsync(() -> getActiveInstanceInfosForVipAddressBlocking(vipName))
            );
    }

    /**
     * @return A {@link CompletableFuture} that when it completes will round-robin the instances returned by {@link
     * #getActiveInstanceInfosForVipAddress(String, Optional)} to determine the *next* active {@link InstanceInfo} that
     * should be called for the given VIP name, or an empty Optional if no active instances were found. If you pass in
     * an {@link Executor} for the {@code longRunningTaskExecutor} argument then it will be used to execute the future,
     * otherwise the default will be used (typically the Java common fork-join pool).
     */
    public CompletableFuture<Optional<InstanceInfo>> getActiveInstanceInfoForVipAddress(
        String vipName, Optional<Executor> longRunningTaskExecutor
    ) {
        return longRunningTaskExecutor
            .map(executor -> CompletableFuture.supplyAsync(
                () -> getActiveInstanceInfoForVipAddressBlocking(vipName), executor)
            )
            .orElse(
                CompletableFuture.supplyAsync(() -> getActiveInstanceInfoForVipAddressBlocking(vipName))
            );
    }

    protected DiscoveryClient discoveryClient() {
        if (discoveryClientCache == null)
            discoveryClientCache = discoveryClientSupplier.get();

        return discoveryClientCache;
    }
}
