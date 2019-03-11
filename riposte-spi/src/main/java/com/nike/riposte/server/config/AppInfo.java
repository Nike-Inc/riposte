package com.nike.riposte.server.config;

import org.jetbrains.annotations.NotNull;

/**
 * Interface for app & instance info required for metrics and healthcheck/service registry. See {@link
 * com.nike.riposte.server.config.impl.AppInfoImpl} for a basic DTO style implementation and static helper methods for
 * helping detect app ID and environment and factory methods for creating "local" instances.
 */
public interface AppInfo {

    /**
     * The value that should be returned by some methods of this interface if the correct value cannot be determined or
     * is otherwise unknown. This may or may not be a breaking error condition depending on your application's
     * requirements.
     */
    String UNKNOWN_VALUE = "unknown";

    /**
     * @return the AppId/name for this service (like {@code foo-svc}). This should always be a valid value - never
     * {@link #UNKNOWN_VALUE} if at all possible.
     */
    @NotNull String appId();

    /**
     * @return the environment for the AppId (like {@code test} or {@code prod}). This should always be a valid
     * value - never {@link #UNKNOWN_VALUE} if at all possible.
     */
    @NotNull String environment();

    /**
     * @return the datacenter/region for the AppId (like {@code us-west-2}), or {@link #UNKNOWN_VALUE} if the
     * datacenter/region could not be determined.
     */
    @NotNull String dataCenter();

    /**
     * @return the instanceId/ip/hostname of this machine/VM running the AppId service, or {@link #UNKNOWN_VALUE} if
     * the instanceId/ip/hostname could not be determined.
     */
    @NotNull String instanceId();
}
