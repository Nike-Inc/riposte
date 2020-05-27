package com.nike.riposte.util;

import com.nike.internal.util.Pair;
import com.nike.riposte.client.asynchttp.ning.AsyncHttpClientHelper;
import com.nike.riposte.client.asynchttp.ning.AsyncResponseHandler;
import com.nike.riposte.client.asynchttp.ning.RequestBuilderWrapper;
import com.nike.riposte.server.config.AppInfo;
import com.nike.riposte.server.config.impl.AppInfoImpl;

import com.ning.http.client.Response;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.netty.handler.codec.http.HttpMethod;

import static com.nike.riposte.util.AwsUtil.AMAZON_METADATA_DOCUMENT_URL;
import static com.nike.riposte.util.AwsUtil.AMAZON_METADATA_INSTANCE_ID_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link AwsUtil}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class AwsUtilTest {

    private AsyncHttpClientHelper asyncClientMock;

    private RequestBuilderWrapper regionRequestBuilderWrapperMock;
    private CompletableFuture<String> regionCoreFuture;

    private RequestBuilderWrapper awsInstanceIdRequestBuilderWrapperMock;
    private CompletableFuture<String> awsInstanceIdCoreFuture;

    private Response responseMockForAwsMetadataDoc;
    private Response responseMockForAwsInstanceId;

    private static final String CUSTOM_REGION = "us-west-" + UUID.randomUUID().toString();
    private static final String EXAMPLE_AWS_METADATA_DOC_RESULT = "{\n"
                                                                  + "   \"devpayProductCodes\" : null,\n"
                                                                  + "   \"privateIp\" : \"123.45.67.89\",\n"
                                                                  + "   \"availabilityZone\" : \"us-west-2b\",\n"
                                                                  + "   \"version\" : \"2010-08-31\",\n"
                                                                  + "   \"accountId\" : \"111222333444\",\n"
                                                                  + "   \"instanceId\" : \"i-aaa11b2c\",\n"
                                                                  + "   \"billingProducts\" : null,\n"
                                                                  + "   \"imageId\" : \"ami-1111a222\",\n"
                                                                  + "   \"instanceType\" : \"m3.medium\",\n"
                                                                  + "   \"kernelId\" : null,\n"
                                                                  + "   \"ramdiskId\" : null,\n"
                                                                  + "   \"architecture\" : \"x86_64\",\n"
                                                                  + "   \"pendingTime\" : \"2015-04-06T19:49:49Z\",\n"
                                                                  + "   \"region\" : \"" + CUSTOM_REGION + "\"\n"
                                                                  + "}";
    private static final String EXAMPLE_AWS_INSTANCE_ID_RESULT = "i-" + UUID.randomUUID().toString();

    private void setAppIdAndEnvironemntSystemProperties(String appId, String environment) {
        if (appId == null)
            System.clearProperty("@appId");
        else
            System.setProperty("@appId", appId);

        if (environment == null)
            System.clearProperty("@environment");
        else
            System.setProperty("@environment", environment);
    }

    @Before
    public void beforeMethod() throws IOException {
        setAppIdAndEnvironemntSystemProperties(null, null);

        asyncClientMock = mock(AsyncHttpClientHelper.class);

        // General-purpose setup
        responseMockForAwsMetadataDoc = mock(Response.class);
        doReturn(EXAMPLE_AWS_METADATA_DOC_RESULT).when(responseMockForAwsMetadataDoc).getResponseBody();

        responseMockForAwsInstanceId = mock(Response.class);
        doReturn(EXAMPLE_AWS_INSTANCE_ID_RESULT).when(responseMockForAwsInstanceId).getResponseBody();

        // Setup region call
        regionRequestBuilderWrapperMock = mock(RequestBuilderWrapper.class);
        regionCoreFuture = new CompletableFuture<>();

        doReturn(regionRequestBuilderWrapperMock)
            .when(asyncClientMock)
            .getRequestBuilder(AMAZON_METADATA_DOCUMENT_URL, HttpMethod.GET);
        doAnswer(invocation -> {
            AsyncResponseHandler<String> handler = (AsyncResponseHandler<String>) invocation.getArguments()[1];
            try {
                regionCoreFuture.complete(handler.handleResponse(responseMockForAwsMetadataDoc));
            }
            catch (Throwable t) {
                regionCoreFuture.completeExceptionally(t);
            }
            return regionCoreFuture;
        }).when(asyncClientMock)
          .executeAsyncHttpRequest(eq(regionRequestBuilderWrapperMock), any(AsyncResponseHandler.class));

        // Setup AWS instance ID call
        awsInstanceIdRequestBuilderWrapperMock = mock(RequestBuilderWrapper.class);
        awsInstanceIdCoreFuture = new CompletableFuture<>();

        doReturn(awsInstanceIdRequestBuilderWrapperMock)
            .when(asyncClientMock)
            .getRequestBuilder(AMAZON_METADATA_INSTANCE_ID_URL, HttpMethod.GET);
        doAnswer(invocation -> {
            AsyncResponseHandler<String> handler = (AsyncResponseHandler<String>) invocation.getArguments()[1];
            try {
                awsInstanceIdCoreFuture.complete(handler.handleResponse(responseMockForAwsInstanceId));
            }
            catch (Throwable t) {
                awsInstanceIdCoreFuture.completeExceptionally(t);
            }
            return awsInstanceIdCoreFuture;
        }).when(asyncClientMock)
          .executeAsyncHttpRequest(eq(awsInstanceIdRequestBuilderWrapperMock), any(AsyncResponseHandler.class));
    }

    @After
    public void afterMethod() {
        setAppIdAndEnvironemntSystemProperties(null, null);
    }

    @Test
    public void code_coverage_hoops() {
        // jump!
        new AwsUtil();
    }

    private Pair<CompletableFuture<String>, AsyncResponseHandler<String>> executeGetAwsRegionAndExtractHandler() {
        CompletableFuture<String> cf = AwsUtil.getAwsRegion(asyncClientMock);

        verify(asyncClientMock).getRequestBuilder(AMAZON_METADATA_DOCUMENT_URL, HttpMethod.GET);
        ArgumentCaptor<AsyncResponseHandler> handlerArgCaptor = ArgumentCaptor.forClass(AsyncResponseHandler.class);
        verify(asyncClientMock).executeAsyncHttpRequest(eq(regionRequestBuilderWrapperMock),
                                                        handlerArgCaptor.capture());

        AsyncResponseHandler<String> handler = handlerArgCaptor.getValue();

        return Pair.of(cf, handler);
    }

    @Test
    public void getAwsRegion_makes_async_request_to_aws_and_returns_the_resulting_region() throws Throwable {
        // when
        Pair<CompletableFuture<String>, AsyncResponseHandler<String>> resultAndHandler =
            executeGetAwsRegionAndExtractHandler();

        // then
        assertThat(resultAndHandler.getLeft()).isCompleted();
        String regionResult = resultAndHandler.getLeft().join();
        assertThat(regionResult).isEqualTo(CUSTOM_REGION);
        assertThat(resultAndHandler.getRight().handleResponse(responseMockForAwsMetadataDoc)).isEqualTo(CUSTOM_REGION);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void getAwsRegion_returns_AppInfo_UNKNOWN_VALUE_if_response_from_aws_does_not_contain_region_value(
        boolean useJunkJson
    ) throws Throwable {
        // given
        String result = (useJunkJson) ? "i am not parseable as json" : "{\"notregion\":\"stillnotregion\"}";
        doReturn(result).when(responseMockForAwsMetadataDoc).getResponseBody();

        // when
        Pair<CompletableFuture<String>, AsyncResponseHandler<String>> resultAndHandler =
            executeGetAwsRegionAndExtractHandler();

        // then
        assertThat(resultAndHandler.getLeft()).isCompleted();
        String regionResult = resultAndHandler.getLeft().join();
        assertThat(regionResult).isEqualTo(AppInfo.UNKNOWN_VALUE);
        assertThat(resultAndHandler.getRight().handleResponse(responseMockForAwsMetadataDoc))
            .isEqualTo(AppInfo.UNKNOWN_VALUE);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void getAwsRegion_returns_completable_future_with_fallback_handling_logic(boolean completeExceptionally) {
        // given
        // Don't complete the core future, just return it, so we can complete it how we want.
        doReturn(regionCoreFuture).when(asyncClientMock)
                                  .executeAsyncHttpRequest(eq(regionRequestBuilderWrapperMock),
                                                           any(AsyncResponseHandler.class));
        Pair<CompletableFuture<String>, AsyncResponseHandler<String>> resultAndHandler =
            executeGetAwsRegionAndExtractHandler();
        assertThat(regionCoreFuture).isNotDone();
        assertThat(resultAndHandler.getLeft()).isNotDone();

        // when
        if (completeExceptionally)
            regionCoreFuture.completeExceptionally(new RuntimeException("kaboom"));
        else
            regionCoreFuture.complete(null);

        // then
        // In either case, we should get AppInfo.UNKNOWN_VALUE as the final result from the future with fallback logic.
        assertThat(resultAndHandler.getLeft()).isCompleted();
        assertThat(resultAndHandler.getLeft().join()).isEqualTo(AppInfo.UNKNOWN_VALUE);
    }

    private Pair<CompletableFuture<String>, AsyncResponseHandler<String>> executeGetAwsInstanceIdAndExtractHandler() {
        CompletableFuture<String> cf = AwsUtil.getAwsInstanceId(asyncClientMock);

        verify(asyncClientMock).getRequestBuilder(AMAZON_METADATA_INSTANCE_ID_URL, HttpMethod.GET);
        ArgumentCaptor<AsyncResponseHandler> handlerArgCaptor = ArgumentCaptor.forClass(AsyncResponseHandler.class);
        verify(asyncClientMock).executeAsyncHttpRequest(eq(awsInstanceIdRequestBuilderWrapperMock),
                                                        handlerArgCaptor.capture());

        AsyncResponseHandler<String> handler = handlerArgCaptor.getValue();

        return Pair.of(cf, handler);
    }

    @Test
    public void getAwsInstanceId_makes_async_request_to_aws_and_returns_the_resulting_instance_id() throws Throwable {
        // when
        Pair<CompletableFuture<String>, AsyncResponseHandler<String>> resultAndHandler =
            executeGetAwsInstanceIdAndExtractHandler();

        // then
        assertThat(resultAndHandler.getLeft()).isCompleted();
        String instanceIdResult = resultAndHandler.getLeft().join();
        assertThat(instanceIdResult).isEqualTo(EXAMPLE_AWS_INSTANCE_ID_RESULT);
        assertThat(resultAndHandler.getRight().handleResponse(responseMockForAwsInstanceId))
            .isEqualTo(EXAMPLE_AWS_INSTANCE_ID_RESULT);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void getAwsInstanceId_returns_completable_future_with_fallback_handling_logic(
        boolean completeExceptionally) {
        // given
        // Don't complete the core future, just return it, so we can complete it how we want.
        doReturn(awsInstanceIdCoreFuture)
            .when(asyncClientMock)
            .executeAsyncHttpRequest(eq(awsInstanceIdRequestBuilderWrapperMock), any(AsyncResponseHandler.class));
        Pair<CompletableFuture<String>, AsyncResponseHandler<String>> resultAndHandler =
            executeGetAwsInstanceIdAndExtractHandler();
        assertThat(awsInstanceIdCoreFuture).isNotDone();
        assertThat(resultAndHandler.getLeft()).isNotDone();

        // when
        if (completeExceptionally)
            awsInstanceIdCoreFuture.completeExceptionally(new RuntimeException("kaboom"));
        else
            awsInstanceIdCoreFuture.complete(null);

        // then
        // In either case, we should get AppInfo.UNKNOWN_VALUE as the final result from the future with fallback logic.
        assertThat(resultAndHandler.getLeft()).isCompleted();
        assertThat(resultAndHandler.getLeft().join()).isEqualTo(AppInfo.UNKNOWN_VALUE);
    }

    @Test
    public void getAppInfoFutureWithAwsInfo_with_all_args_uses_data_from_getAwsRegion_and_getAwsInstanceId_to_build_result() {
        // given
        String appId = "appid-" + UUID.randomUUID().toString();
        String environment = "environment-" + UUID.randomUUID().toString();
        String expectedDataCenter = AwsUtil.getAwsRegion(asyncClientMock).join();
        String expectedInstanceId = AwsUtil.getAwsInstanceId(asyncClientMock).join();

        // when
        AppInfo result = AwsUtil.getAppInfoFutureWithAwsInfo(appId, environment, asyncClientMock).join();

        // then
        assertThat(result.appId()).isEqualTo(appId);
        assertThat(result.environment()).isEqualTo(environment);
        assertThat(result.dataCenter()).isEqualTo(expectedDataCenter);
        assertThat(result.instanceId()).isEqualTo(expectedInstanceId);
    }

    @Test
    public void getAppInfoFutureWithAwsInfo_with_all_args_uses_InetAddress_local_hostname_if_getAwsInstanceId_returns_unknown()
        throws IOException {
        // given
        String appId = "appid-" + UUID.randomUUID().toString();
        String environment = "environment-" + UUID.randomUUID().toString();
        String expectedDataCenter = AwsUtil.getAwsRegion(asyncClientMock).join();
        doReturn(null).when(responseMockForAwsInstanceId).getResponseBody();
        String expectedInstanceId = InetAddress.getLocalHost().getHostName();

        // when
        AppInfo result = AwsUtil.getAppInfoFutureWithAwsInfo(appId, environment, asyncClientMock).join();

        // then
        assertThat(result.appId()).isEqualTo(appId);
        assertThat(result.environment()).isEqualTo(environment);
        assertThat(result.dataCenter()).isEqualTo(expectedDataCenter);
        assertThat(result.instanceId()).isEqualTo(expectedInstanceId);
    }

    @DataProvider(value = {
        "local",
        "compiletimetest"
    })
    @Test
    public void getAppInfoFutureWithAwsInfo_with_all_args_returns_AppInfoImpl_createLocalInstance_if_environment_is_local_or_compiletimetest(
        String environment
    ) {
        // given
        String appId = "appid-" + UUID.randomUUID().toString();
        AppInfo expectedResult = AppInfoImpl.createLocalInstance(appId);

        // when
        AppInfo result = AwsUtil.getAppInfoFutureWithAwsInfo(appId, environment, asyncClientMock).join();

        // then
        assertThat(result.appId()).isEqualTo(expectedResult.appId()).isEqualTo(appId);
        assertThat(result.environment()).isEqualTo(expectedResult.environment());
        assertThat(result.dataCenter()).isEqualTo(expectedResult.dataCenter());
        assertThat(result.instanceId()).isEqualTo(expectedResult.instanceId());
    }

    @Test
    public void getAppInfoFutureWithAwsInfo_with_minimal_args_delegates_to_kitchen_sink_overload_method() {
        // given
        String appId = "appid-" + UUID.randomUUID().toString();
        String environment = "environment-" + UUID.randomUUID().toString();
        String expectedDataCenter = AwsUtil.getAwsRegion(asyncClientMock).join();
        String expectedInstanceId = AwsUtil.getAwsInstanceId(asyncClientMock).join();
        setAppIdAndEnvironemntSystemProperties(appId, environment);

        // when
        AppInfo result = AwsUtil.getAppInfoFutureWithAwsInfo(asyncClientMock).join();

        // then
        assertThat(result.appId()).isEqualTo(appId);
        assertThat(result.environment()).isEqualTo(environment);
        assertThat(result.dataCenter()).isEqualTo(expectedDataCenter);
        assertThat(result.instanceId()).isEqualTo(expectedInstanceId);
    }

    @Test
    public void getAppInfoFutureWithAwsInfo_with_minimal_args_throws_IllegalStateException_if_appId_is_missing() {
        // given
        setAppIdAndEnvironemntSystemProperties(null, UUID.randomUUID().toString());

        // when
        Throwable ex = catchThrowable(() -> AwsUtil.getAppInfoFutureWithAwsInfo(asyncClientMock));

        // then
        assertThat(ex).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void getAppInfoFutureWithAwsInfo_with_minimal_args_throws_IllegalStateException_if_environment_is_missing() {
        // given
        setAppIdAndEnvironemntSystemProperties(UUID.randomUUID().toString(), null);

        // when
        Throwable ex = catchThrowable(() -> AwsUtil.getAppInfoFutureWithAwsInfo(asyncClientMock));

        // then
        assertThat(ex).isInstanceOf(IllegalStateException.class);
    }
}