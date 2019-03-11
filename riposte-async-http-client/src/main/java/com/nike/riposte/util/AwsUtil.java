package com.nike.riposte.util;

import com.nike.riposte.client.asynchttp.ning.AsyncHttpClientHelper;
import com.nike.riposte.server.config.AppInfo;
import com.nike.riposte.server.config.impl.AppInfoImpl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ning.http.client.Response;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.netty.handler.codec.http.HttpMethod;

/**
 * Helper class for dealing with AWS related stuff. In particular {@link #getAppInfoFutureWithAwsInfo(String, String,
 * AsyncHttpClientHelper)} or {@link #getAppInfoFutureWithAwsInfo(AsyncHttpClientHelper)} will give you a {@link
 * CompletableFuture} that will return an {@link AppInfo} with the {@link AppInfo#dataCenter()} and {@link
 * AppInfo#instanceId()} pulled from the AWS metadata services.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class AwsUtil {

    private static final Logger logger = LoggerFactory.getLogger(AwsUtil.class);

    /**
     * The base IP of the magic URLs that you can call from an AWS instance to get information about that instance.
     */
    public static final String AMAZON_METADATA_URL_BASE = "http://169.254.169.254";
    /**
     * If you call this URL from an AWS instance you'll get back a simple string response that looks like: i-abc12d3e
     */
    public static final String AMAZON_METADATA_INSTANCE_ID_URL =
        AMAZON_METADATA_URL_BASE + "/latest/meta-data/instance-id";
    /**
     * If you call this URL from an AWS instance you'll get back a simple string response that looks like: us-west-2b
     * NOTE: Don't confuse this with region - they are slightly different (the region for this us-west-2b availability
     * zone would be us-west-2).
     */
    @SuppressWarnings("unused")
    public static final String AMAZON_METADATA_AVAILABILITY_ZONE_URL =
        AMAZON_METADATA_URL_BASE + "/latest/meta-data/placement/availability-zone";
    /**
     * If you call this URL from an AWS instance you'll get back a JSON string that looks like:
     * <pre>
     *      {
     *          "devpayProductCodes" : null,
     *          "privateIp" : "123.45.67.89",
     *          "availabilityZone" : "us-west-2b",
     *          "version" : "2010-08-31",
     *          "accountId" : "111222333444",
     *          "instanceId" : "i-aaa11b2c",
     *          "billingProducts" : null,
     *          "imageId" : "ami-1111a222",
     *          "instanceType" : "m3.medium",
     *          "kernelId" : null,
     *          "ramdiskId" : null,
     *          "architecture" : "x86_64",
     *          "pendingTime" : "2015-04-06T19:49:49Z",
     *          "region" : "us-west-2"
     *      }
     * </pre>
     */
    public static final String AMAZON_METADATA_DOCUMENT_URL =
        AMAZON_METADATA_URL_BASE + "/latest/dynamic/instance-identity/document";

    // Intentionally protected - use the static methods
    protected AwsUtil() { /* do nothing */ }

    /**
     * @param asyncHttpClientHelper The async HTTP client you want this method to use to make the AWS metadata call.
     *
     * @return A {@link CompletableFuture} that will contain the AWS region this app is running in (assuming it
     * completes successfully). If an error occurs retrieving the region from AWS then the error will be logged and
     * {@link AppInfo#UNKNOWN_VALUE} returned as the value.
     */
    public static @NotNull CompletableFuture<@NotNull String> getAwsRegion(
        @NotNull AsyncHttpClientHelper asyncHttpClientHelper
    ) {
        return asyncHttpClientHelper.executeAsyncHttpRequest(
            asyncHttpClientHelper.getRequestBuilder(AMAZON_METADATA_DOCUMENT_URL, HttpMethod.GET),
            response -> {
                String region = null;
                try {
                    ObjectMapper objectMapper = new ObjectMapper();
                    Map<String, String> resultMap =
                        objectMapper.readValue(response.getResponseBody(), new TypeReference<Map<String, String>>() {});

                    region = resultMap.get("region");
                }
                catch (Throwable t) {
                    logger.error("Error retrieving region from AWS", t);
                }

                if (region == null) {
                    logger.error("AWS metadata service returned null for region. Using 'unknown' as fallback.");
                    region = AppInfo.UNKNOWN_VALUE;
                }

                return region;
            }
        ).handle((region, error) -> {
            if (error != null) {
                logger.error("Unable to get region info from AWS metadata service.", error);
                return AppInfo.UNKNOWN_VALUE;
            }

            if (region == null) {
                logger.error("AWS metadata service returned null for region. Using 'unknown' as fallback.");
                region = AppInfo.UNKNOWN_VALUE;
            }

            return region;
        });
    }

    /**
     * @param asyncHttpClientHelper The async HTTP client you want this method to use to make the AWS metadata call.
     *
     * @return A {@link CompletableFuture} that will contain the AWS instance ID this app is running on (assuming it
     * completes successfully). If an error occurs retrieving the instance ID from AWS then the error will be logged and
     * {@link AppInfo#UNKNOWN_VALUE} returned as the value.
     */
    public static @NotNull CompletableFuture<@NotNull String> getAwsInstanceId(
        @NotNull AsyncHttpClientHelper asyncHttpClientHelper
    ) {
        return asyncHttpClientHelper.executeAsyncHttpRequest(
            asyncHttpClientHelper.getRequestBuilder(AMAZON_METADATA_INSTANCE_ID_URL, HttpMethod.GET),
            Response::getResponseBody
        ).handle((instanceId, error) -> {
            if (error != null) {
                logger.error("Unable to get instance ID info from AWS metadata service.", error);
                return AppInfo.UNKNOWN_VALUE;
            }

            if (instanceId == null) {
                logger.error("AWS metadata service returned null for instance ID. Using 'unknown' as fallback.");
                return AppInfo.UNKNOWN_VALUE;
            }

            return instanceId;
        });
    }

    /**
     * Helper that uses {@link AppInfoImpl#detectAppId()} and {@link AppInfoImpl#detectEnvironment()} to get the app ID
     * and environment values, then returns {@link #getAppInfoFutureWithAwsInfo(String, String, AsyncHttpClientHelper)}
     * using those values. If either app ID or environment cannot be determined then an {@link IllegalStateException}
     * will be thrown, therefore if you know that your app's app ID and/or environment will not be successfully
     * extracted using those methods then you should call {@link #getAppInfoFutureWithAwsInfo(String, String,
     * AsyncHttpClientHelper)} directly with the correct values.
     * <p/>
     * See {@link #getAppInfoFutureWithAwsInfo(String, String, AsyncHttpClientHelper)} for more details on how the
     * {@link AppInfo} returned by the {@link CompletableFuture} will be structured.
     */
    public static @NotNull CompletableFuture<@NotNull AppInfo> getAppInfoFutureWithAwsInfo(
        @NotNull AsyncHttpClientHelper asyncHttpClientHelper
    ) {
        String appId = AppInfoImpl.detectAppId();
        if (appId == null)
            throw new IllegalStateException(
                "Unable to autodetect app ID. Please call getAppInfoFutureWithAwsInfo(String, String, "
                + "AsyncHttpClientHelper) instead and pass the app ID and environment manually"
            );

        String environment = AppInfoImpl.detectEnvironment();
        if (environment == null)
            throw new IllegalStateException(
                "Unable to autodetect environment. Please call getAppInfoFutureWithAwsInfo(String, String, "
                + "AsyncHttpClientHelper) instead and pass the app ID and environment manually"
            );

        return getAppInfoFutureWithAwsInfo(appId, environment, asyncHttpClientHelper);
    }

    /**
     * @param appId The app ID for the running application.
     * @param environment The environment the application is running in (local, test, prod, etc).
     * @param asyncHttpClientHelper The async HTTP client you want this method to use to make the AWS metadata calls.
     *
     * @return A {@link CompletableFuture} that will eventually yield an {@link AppInfo} with the values coming from the
     * given arguments for app ID and environment, and coming from the AWS metadata services for datacenter and instance
     * ID. If the given environment is "local" then {@link AppInfoImpl#createLocalInstance(String)} will be returned
     * (see the javadocs of that method for more information on what values it will contain). Otherwise the AWS metadata
     * services will be used to determine {@link AppInfo#dataCenter()} and {@link AppInfo#instanceId()}. If those AWS
     * metadata calls fail for any reason then {@link AppInfo#UNKNOWN_VALUE} will be used instead.
     */
    public static @NotNull CompletableFuture<@NotNull AppInfo> getAppInfoFutureWithAwsInfo(
        @NotNull String appId,
        @NotNull String environment,
        @NotNull AsyncHttpClientHelper asyncHttpClientHelper
    ) {
        if ("local".equalsIgnoreCase(environment) || "compiletimetest".equalsIgnoreCase(environment)) {
            AppInfo localAppInfo = AppInfoImpl.createLocalInstance(appId);

            logger.info(
                "Local environment. Using the following data for AppInfo. "
                + "appId={}, environment={}, dataCenter={}, instanceId={}",
                localAppInfo.appId(), localAppInfo.environment(), localAppInfo.dataCenter(), localAppInfo.instanceId()
            );

            return CompletableFuture.completedFuture(AppInfoImpl.createLocalInstance(appId));
        }

        // Not local, so assume AWS.
        CompletableFuture<@NotNull String> dataCenterFuture = getAwsRegion(asyncHttpClientHelper);
        CompletableFuture<@NotNull String> instanceIdFuture = getAwsInstanceId(asyncHttpClientHelper);

        return CompletableFuture.allOf(dataCenterFuture, instanceIdFuture).thenApply((aVoid) -> {

            String dataCenter = dataCenterFuture.join();
            String instanceId = instanceIdFuture.join();

            if (AppInfo.UNKNOWN_VALUE.equals(instanceId)) {
                try {
                    instanceId = InetAddress.getLocalHost().getHostName();
                }
                catch (UnknownHostException e) {
                    logger.error(
                        "An error occurred trying to use local hostname as fallback. "
                        + "Using 'unknown' as the fallback's fallback.", e
                    );
                }
            }

            logger.info(
                "Non-local environment. Using the following data for AppInfo. "
                + "appId={}, environment={}, dataCenter={}, instanceId={}",
                appId, environment, dataCenter, instanceId
            );

            return new AppInfoImpl(appId, environment, dataCenter, instanceId);
        });
    }

}
