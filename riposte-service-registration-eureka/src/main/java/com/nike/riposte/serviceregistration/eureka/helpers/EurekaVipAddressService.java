package com.nike.riposte.serviceregistration.eureka.helpers;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.DiscoveryClient;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "WeakerAccess"})
public abstract class EurekaVipAddressService {
    protected final Supplier<DiscoveryClient> discoveryClientSupplier;
    protected DiscoveryClient discoveryClientCache;

    public EurekaVipAddressService(Supplier<DiscoveryClient> discoveryClientSupplier) {
        this.discoveryClientSupplier = discoveryClientSupplier;
    }
    /**
     * Calls {@link #getActiveInstanceInfosForVipAddressBlocking(String)} to determine
     * the *next* active {@link InstanceInfo} that should be called for the given VIP name
     *
     * @return The *next* active {@link InstanceInfo} that should be called for the given VIP name, or an empty Optional
     * if no active instances were found.
     */
    public abstract Optional<InstanceInfo> getActiveInstanceInfoForVipAddressBlocking(String vipName);

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
     * @return A {@link CompletableFuture} that when it completes will call {@link
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
