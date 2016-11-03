package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.exception.ApiException;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;
import com.nike.riposte.util.MultiMatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.jayway.restassured.response.ExtractableResponse;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import static com.jayway.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies miscellaneous functionality.
 *
 * @author Nic Munroe
 */
public class VerifyMiscellaneousFunctionalityComponentTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static Server server;
    private static ServerConfig serverConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        serverConfig = new MiscellaneousFunctionalityTestConfig();
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
    }

    private void verifyErrorReceived(ExtractableResponse response, ApiError expectedApiError) throws IOException {
        assertThat(response.statusCode()).isEqualTo(expectedApiError.getHttpStatusCode());
        DefaultErrorContractDTO responseAsError = objectMapper.readValue(response.asString(), DefaultErrorContractDTO.class);
        assertThat(responseAsError.errors).hasSize(1);
        assertThat(responseAsError.errors.get(0).code).isEqualTo(expectedApiError.getErrorCode());
        assertThat(responseAsError.errors.get(0).message).isEqualTo(expectedApiError.getMessage());
        assertThat(responseAsError.errors.get(0).metadata).isEqualTo(expectedApiError.getMetadata());
    }

    @Test
    public void verify_empty_metadata_is_stripped_from_error_contract() throws IOException, InterruptedException {

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(EmptyMetadataErrorThrower.MATCHING_PATH)
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        verifyErrorReceived(response, EmptyMetadataErrorThrower.ERROR_NO_METADATA);
        assertThat(response.asString()).doesNotContain("metadata");
    }

    @Test
    public void verify_populated_metadata_is_returned_in_error_contract() throws IOException, InterruptedException {

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(WithMetadataErrorThrower.MATCHING_PATH)
                .log().all()
                .when()
                .get()
                .then()
                .log().headers()
                .extract();

        verifyErrorReceived(response, WithMetadataErrorThrower.ERROR_WITH_METADATA);
        assertThat(response.asString()).contains("\"metadata\":{");
    }

    @Test
    public void verify_multimatcher_checks_all_paths_for_matches() {
        {
            ExtractableResponse externalResponse =
                given()
                    .baseUri("http://127.0.0.1")
                    .port(serverConfig.endpointsPort())
                    .basePath("/mm/external/foo")
                    .log().all()
                .when()
                    .get()
                .then()
                    .log().headers()
                    .extract();

            assertThat(externalResponse.response().statusCode()).isEqualTo(200);
            assertThat(externalResponse.asString()).isEqualTo(MultiMatcherEndpoint.body);
        }

        {
            ExtractableResponse internalResponse =
                given()
                    .baseUri("http://127.0.0.1")
                    .port(serverConfig.endpointsPort())
                    .basePath("/mm/internal/bar")
                    .log().all()
                .when()
                    .get()
                .then()
                    .log().headers()
                    .extract();

            assertThat(internalResponse.response().statusCode()).isEqualTo(200);
            assertThat(internalResponse.asString()).isEqualTo(MultiMatcherEndpoint.body);
        }
    }

    @Test
    public void verify_order_matters_multimatcher_can_reach_all_paths_correctly() {
        {
            String importantPathParam = UUID.randomUUID().toString();
            ExtractableResponse internalResponse =
                given()
                    .baseUri("http://127.0.0.1")
                    .port(serverConfig.endpointsPort())
                    .basePath("/om/internal/foo/{importantPathParam}/bar/baz")
                    .pathParam("importantPathParam", importantPathParam)
                    .log().all()
                .when()
                    .get()
                .then()
                    .log().headers()
                    .extract();

            assertThat(internalResponse.response().statusCode()).isEqualTo(200);
            assertThat(internalResponse.asString())
                .isEqualTo(OrderMattersMultiMatcherEndpoint.generateResponseBodyForImportantPathParam(importantPathParam));
        }

        {
            String importantPathParam = UUID.randomUUID().toString();
            ExtractableResponse externalResponse =
                given()
                    .baseUri("http://127.0.0.1")
                    .port(serverConfig.endpointsPort())
                    .basePath("/om/foo/{importantPathParam}/bar/baz")
                    .pathParam("importantPathParam", importantPathParam)
                    .log().all()
                .when()
                    .get()
                .then()
                    .log().headers()
                    .extract();

            assertThat(externalResponse.response().statusCode()).isEqualTo(200);
            assertThat(externalResponse.asString())
                .isEqualTo(OrderMattersMultiMatcherEndpoint.generateResponseBodyForImportantPathParam(importantPathParam));
        }
    }

    @Test
    public void verify_invalid_order_multimatcher_cannot_reach_all_paths_correctly() {
        {
            String importantPathParam = UUID.randomUUID().toString();
            ExtractableResponse externalResponse =
                given()
                    .baseUri("http://127.0.0.1")
                    .port(serverConfig.endpointsPort())
                    .basePath("/invalidorder/foo/{importantPathParam}/bar/baz")
                    .pathParam("importantPathParam", importantPathParam)
                    .log().all()
                .when()
                    .get()
                .then()
                    .log().headers()
                    .extract();

            assertThat(externalResponse.response().statusCode()).isEqualTo(200);
            assertThat(externalResponse.asString())
                .isEqualTo(OrderMattersMultiMatcherEndpoint.generateResponseBodyForImportantPathParam(importantPathParam));
        }

        {
            // This path is not reachable because the external path catches it first since it's ordered first in the multimatcher
            String importantPathParam = UUID.randomUUID().toString();
            ExtractableResponse internalResponse =
                given()
                    .baseUri("http://127.0.0.1")
                    .port(serverConfig.endpointsPort())
                    .basePath("/invalidorder/internal/foo/{importantPathParam}/bar/baz")
                    .pathParam("importantPathParam", importantPathParam)
                    .log().all()
                    .when()
                    .get()
                    .then()
                    .log().headers()
                    .extract();

            assertThat(internalResponse.response().statusCode()).isEqualTo(200);
            // Since the external path caught this request, the path param it pulled should be "foo" rather than the actual importantPathParam value.
            assertThat(internalResponse.asString())
                .isEqualTo(OrderMattersMultiMatcherEndpoint.generateResponseBodyForImportantPathParam("foo"));
        }
    }

    @Test
    public void verify_proxy_router_response_modification_works_as_expected() throws IOException, InterruptedException {

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(ProxyRouterResponseModificationEndpoint.MATCHING_PATH)
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        ApiError unmodifiedError = EmptyMetadataErrorThrower.ERROR_NO_METADATA;
        assertThat(response.statusCode()).isEqualTo(ProxyRouterResponseModificationEndpoint.MODIFIED_HTTP_STATUS_RESPONSE_CODE);
        assertThat(response.header(ProxyRouterResponseModificationEndpoint.ORIG_HTTP_STATUS_CODE_RESPONSE_HEADER_KEY))
            .isEqualTo(String.valueOf(unmodifiedError.getHttpStatusCode()));
        ApiError expectedModifiedError = new ApiErrorBase(unmodifiedError.getName(), unmodifiedError.getErrorCode(), unmodifiedError.getMessage(),
                                                          ProxyRouterResponseModificationEndpoint.MODIFIED_HTTP_STATUS_RESPONSE_CODE);
        verifyErrorReceived(response, expectedModifiedError);
        assertThat(response.asString()).doesNotContain("metadata");
    }

    public static class MiscellaneousFunctionalityTestConfig implements ServerConfig {
        private final Collection<Endpoint<?>> endpoints;
        private final int port;

        public MiscellaneousFunctionalityTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = Arrays.asList(
                new EmptyMetadataErrorThrower(),
                new WithMetadataErrorThrower(),
                new MultiMatcherEndpoint(),
                new OrderMattersMultiMatcherEndpoint(),
                new InvalidOrderingMultiMatcherEndpoint(),
                new ProxyRouterResponseModificationEndpoint(port)
            );
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }
    }

    public static class MultiMatcherEndpoint extends StandardEndpoint<Void, Object> {
        public static final String INTERNAL_MATCHING_PATH = "/mm/internal/*";
        public static final String EXTERNAL_MATCHING_PATH = "/mm/external/*";

        public static String body = UUID.randomUUID().toString();

        @Override
        public CompletableFuture<ResponseInfo<Object>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(ResponseInfo.newBuilder().withContentForFullResponse(body).build());
        }

        @Override
        public Matcher requestMatcher() { return MultiMatcher.match(Arrays.asList(INTERNAL_MATCHING_PATH, EXTERNAL_MATCHING_PATH)); }
    }

    public static class OrderMattersMultiMatcherEndpoint extends StandardEndpoint<Void, Object> {
        public static final String INTERNAL_MATCHING_PATH = "/om/internal/*/{importantPathParam}/**";
        public static final String NON_INTERNAL_MATCHING_PATH = "/om/*/{importantPathParam}/**";

        @Override
        public CompletableFuture<ResponseInfo<Object>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder()
                            .withContentForFullResponse(generateResponseBodyForImportantPathParam(request.getPathParam("importantPathParam")))
                            .build()
            );
        }

        @Override
        public Matcher requestMatcher() { return MultiMatcher.match(Arrays.asList(INTERNAL_MATCHING_PATH, NON_INTERNAL_MATCHING_PATH)); }

        public static String generateResponseBodyForImportantPathParam(String importantPathParam) {
            return "The path param from the request was: " + importantPathParam;
        }
    }

    public static class InvalidOrderingMultiMatcherEndpoint extends OrderMattersMultiMatcherEndpoint {
        public static final String INTERNAL_MATCHING_PATH = "/invalidorder/internal/*/{importantPathParam}/**";
        public static final String NON_INTERNAL_MATCHING_PATH = "/invalidorder/*/{importantPathParam}/**";

        // Reverse the ordering from OrderMattersMultiMatcherEndpoint. This should make it so that INTERNAL_MATCHING_PATH is *never* reachable.
        @Override
        public Matcher requestMatcher() { return MultiMatcher.match(Arrays.asList(NON_INTERNAL_MATCHING_PATH, INTERNAL_MATCHING_PATH)); }
    }

    public static class EmptyMetadataErrorThrower extends StandardEndpoint<Void, Void> {

        public static final String MATCHING_PATH = "/emptyMetadata";
        public static final ApiError ERROR_NO_METADATA = new ApiErrorBase("NO_METADATA", 90000, "Blowup no MD", 400, null);

        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            throw new ApiException(ERROR_NO_METADATA);
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }

    public static class WithMetadataErrorThrower extends StandardEndpoint<Void, Void> {

        public static final String MATCHING_PATH = "/withMetadata";
        public static final ApiError ERROR_WITH_METADATA = new ApiErrorBase("WITH_METADATA", 90000, "Blowup with MD", 400,
                                                                            ImmutableMap.of("foo", "bar"));

        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            throw new ApiException(ERROR_WITH_METADATA);
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }

    public static class ProxyRouterResponseModificationEndpoint extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/proxyWithResponseModification";
        public static final int MODIFIED_HTTP_STATUS_RESPONSE_CODE = 942;
        public static final String ORIG_HTTP_STATUS_CODE_RESPONSE_HEADER_KEY = "origHttpStatusCode";

        private final int port;

        public ProxyRouterResponseModificationEndpoint(int port) {
            this.port = port;
        }

        @Override
        public CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(RequestInfo<?> request,
                                                                                                     Executor longRunningTaskExecutor,
                                                                                                     ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                new DownstreamRequestFirstChunkInfo(
                    "127.0.0.1", port, false,
                    generateSimplePassthroughRequest(request, EmptyMetadataErrorThrower.MATCHING_PATH, HttpMethod.GET, ctx)
                )
            );
        }

        @Override
        public void handleDownstreamResponseFirstChunk(HttpResponse downstreamResponseFirstChunk, RequestInfo<?> origRequestInfo) {
            downstreamResponseFirstChunk.headers().set(ORIG_HTTP_STATUS_CODE_RESPONSE_HEADER_KEY,
                                                       String.valueOf(downstreamResponseFirstChunk.getStatus().code()));
            downstreamResponseFirstChunk.setStatus(new HttpResponseStatus(MODIFIED_HTTP_STATUS_RESPONSE_CODE, "junk status code"));
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }
}
