package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.apierror.sample.SampleCoreApiError;
import com.nike.backstopper.exception.ApiException;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.internal.util.Pair;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.http.filter.RequestAndResponseFilter;
import com.nike.riposte.server.http.filter.ShortCircuitingRequestAndResponseFilter;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.restassured.response.ExtractableResponse;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

import static com.jayway.restassured.RestAssured.given;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.FirstFilterNormal.FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.FirstFilterNormal.FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.FirstFilterNormal.FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.FirstFilterNormal.FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.FirstFilterNormal.FIRST_FILTER_ONLY_RESPONSE_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.FirstFilterNormal.FIRST_FILTER_ONLY_RESPONSE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.FirstFilterNormal.FIRST_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.FirstFilterNormal.FIRST_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.FirstFilterNormal.FIRST_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.FirstFilterNormal.FIRST_FILTER_RESPONSE_OVERRIDE_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_ONLY_RESPONSE_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_ONLY_RESPONSE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.SecondFilterShortCircuiting.SECOND_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_ONLY_RESPONSE_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_ONLY_RESPONSE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_VALUE;
import static com.nike.riposte.server.componenttest.VerifyRequestAndResponseFilteringComponentTest.ThirdFilterNormal.THIRD_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link RequestAndResponseFilter} functionality.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class VerifyRequestAndResponseFilteringComponentTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static Server server;
    private static ServerConfig serverConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        serverConfig = new RequestAndResponseFilterTestConfig();
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
    }

    @DataProvider(value = {
        "false",
        "true"
    }, splitBy = "\\|")
    @Test
    public void verify_basic_endpoint_works_normally(boolean forceError) throws IOException, InterruptedException {

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(BasicEndpoint.MATCHING_PATH)
                .header(BasicEndpoint.FORCE_ERROR_HEADER_KEY, String.valueOf(forceError))
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        if (forceError)
            verifyErrorReceived(response, BasicEndpoint.FORCED_ERROR);
        else
            assertThat(response.asString()).isEqualTo(BasicEndpoint.RESPONSE_PAYLOAD);
    }

    private void verifyErrorReceived(ExtractableResponse response, ApiError expectedApiError) throws IOException {
        assertThat(response.statusCode()).isEqualTo(expectedApiError.getHttpStatusCode());
        DefaultErrorContractDTO responseAsError = objectMapper.readValue(response.asString(), DefaultErrorContractDTO.class);
        assertThat(responseAsError.errors).hasSize(1);
        assertThat(responseAsError.errors.get(0).code).isEqualTo(expectedApiError.getErrorCode());
        assertThat(responseAsError.errors.get(0).message).isEqualTo(expectedApiError.getMessage());
        assertThat(responseAsError.errors.get(0).metadata).isEqualTo(expectedApiError.getMetadata());
    }

    @DataProvider(value = {
        "false",
        "true"
    }, splitBy = "\\|")
    @Test
    public void verify_filters_work_for_non_short_circuit_calls_for_endpoints_and_errors(boolean forceError) throws IOException, InterruptedException {

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(BasicEndpoint.MATCHING_PATH)
                .header(BasicEndpoint.FORCE_ERROR_HEADER_KEY, String.valueOf(forceError))
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        // Should have hit the endpoint
        if (forceError)
            verifyErrorReceived(response, BasicEndpoint.FORCED_ERROR);
        else
            assertThat(response.asString()).isEqualTo(BasicEndpoint.RESPONSE_PAYLOAD);

        // All the filter-specific request and response headers should be present.
        assertThat(response.headers().getValues(FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE));
        assertThat(response.headers().getValues(FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE));
        assertThat(response.headers().getValues(FIRST_FILTER_ONLY_RESPONSE_HEADER_KEY))
            .isEqualTo(singletonList(FIRST_FILTER_ONLY_RESPONSE_HEADER_VALUE));

        assertThat(response.headers().getValues(SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE));
        assertThat(response.headers().getValues(SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE));
        assertThat(response.headers().getValues(SECOND_FILTER_ONLY_RESPONSE_HEADER_KEY))
            .isEqualTo(singletonList(SECOND_FILTER_ONLY_RESPONSE_HEADER_VALUE));

        assertThat(response.headers().getValues(THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE));
        assertThat(response.headers().getValues(THIRD_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(THIRD_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE));
        assertThat(response.headers().getValues(THIRD_FILTER_ONLY_RESPONSE_HEADER_KEY))
            .isEqualTo(singletonList(THIRD_FILTER_ONLY_RESPONSE_HEADER_VALUE));

        // The override request and response headers should be correct - last filter wins - so third filter for the request and first filter for the response
        assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_KEY))
            .isEqualTo(singletonList(THIRD_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE));
        assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_KEY))
            .isEqualTo(singletonList(THIRD_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_VALUE));
        assertThat(response.headers().getValues(COMMON_FILTER_RESPONSE_OVERRIDE_HEADER_KEY))
            .isEqualTo(singletonList(FIRST_FILTER_RESPONSE_OVERRIDE_HEADER_KEY));

        // The cumulative request and response headers should be correct (contain all values from all filters)
        assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_KEY))
            .isEqualTo(Arrays.asList(FIRST_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE,
                                     SECOND_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE,
                                     THIRD_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE));
        assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_KEY))
            .isEqualTo(Arrays.asList(FIRST_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE,
                                     SECOND_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE,
                                     THIRD_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE));
        assertThat(response.headers().getValues(COMMON_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY))
            .isEqualTo(Arrays.asList(THIRD_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY,
                                     SECOND_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY,
                                     FIRST_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY));
    }

    @DataProvider(value = {
        "false",
        "true"
    }, splitBy = "\\|")
    @Test
    public void verify_filters_work_for_first_chunk_short_circuit_calls(boolean attempt404Path) throws IOException, InterruptedException {

        String basePath = (attempt404Path) ? "/foobardoesnotexist" : BasicEndpoint.MATCHING_PATH;

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(basePath)
                .header(SecondFilterShortCircuiting.SHOULD_SHORT_CIRCUIT_FIRST_CHUNK, "true")
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        // Should *not* have hit the endpoint - should have short circuited on the first chunk, even if we tried to hit a 404 not found path.
        assertThat(response.asString()).isEqualTo(SecondFilterShortCircuiting.SHORT_CIRCUIT_FIRST_CHUNK_RESPONSE_PAYLOAD);

        // Some of the filter-specific request and response headers should be present - the ones added after the short circuit should not be present.
        assertThat(response.headers().getValues(FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE));
        assertThat(response.header(FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY)).isNull();
        assertThat(response.headers().getValues(FIRST_FILTER_ONLY_RESPONSE_HEADER_KEY))
            .isEqualTo(singletonList(FIRST_FILTER_ONLY_RESPONSE_HEADER_VALUE));

        assertThat(response.headers().getValues(SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE));
        assertThat(response.header(SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY)).isNull();
        assertThat(response.headers().getValues(SECOND_FILTER_ONLY_RESPONSE_HEADER_KEY))
            .isEqualTo(singletonList(SECOND_FILTER_ONLY_RESPONSE_HEADER_VALUE));

        assertThat(response.header(THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY)).isNull();
        assertThat(response.header(THIRD_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY)).isNull();
        assertThat(response.headers().getValues(THIRD_FILTER_ONLY_RESPONSE_HEADER_KEY))
            .isEqualTo(singletonList(THIRD_FILTER_ONLY_RESPONSE_HEADER_VALUE));

        // The override request and response headers should be correct based on when the short circuit occurred
        assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_KEY))
            .isEqualTo(singletonList(SECOND_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE));
        assertThat(response.header(COMMON_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_KEY)).isNull();
        assertThat(response.headers().getValues(COMMON_FILTER_RESPONSE_OVERRIDE_HEADER_KEY))
            .isEqualTo(singletonList(FIRST_FILTER_RESPONSE_OVERRIDE_HEADER_KEY));

        // The cumulative request and response headers should be correct based on when the short circuit occurred
        assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_KEY))
            .isEqualTo(Arrays.asList(FIRST_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE,
                                     SECOND_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE));
        assertThat(response.header(COMMON_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_KEY)).isNull();
        assertThat(response.headers().getValues(COMMON_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY))
            .isEqualTo(Arrays.asList(THIRD_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY,
                                     SECOND_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY,
                                     FIRST_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY));
    }

    @DataProvider(value = {
        "false",
        "true"
    }, splitBy = "\\|")
    @Test
    public void verify_filters_work_for_last_chunk_short_circuit_calls(boolean hit404Path) throws IOException, InterruptedException {

        String basePath = (hit404Path) ? "/foobardoesnotexist" : BasicEndpoint.MATCHING_PATH;

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(basePath)
                .header(SecondFilterShortCircuiting.SHOULD_SHORT_CIRCUIT_LAST_CHUNK, "true")
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        // Should *not* have hit the endpoint:
        //      * Should have short circuited by the filter on the last chunk for valid path, OR
        //      * Should have thrown a 404 after the first chunk was fully filtered for an invalid 404 path.
        if (hit404Path)
            verifyErrorReceived(response, SampleCoreApiError.NOT_FOUND);
        else
            assertThat(response.asString()).isEqualTo(SecondFilterShortCircuiting.SHORT_CIRCUIT_LAST_CHUNK_RESPONSE_PAYLOAD);

        // Some of the filter-specific request and response headers should be present - the ones added after the short circuit should not be present.
        assertThat(response.headers().getValues(FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE));
        if (hit404Path) {
            // 404 prevents last chunk filters from running because the 404 exception is thrown after the first chunk is processed but before the last chunk.
            assertThat(response.headers().getValues(FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY)).isEmpty();
        }
        else {
            assertThat(response.headers().getValues(FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY))
                .isEqualTo(singletonList(FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE));
        }
        assertThat(response.headers().getValues(FIRST_FILTER_ONLY_RESPONSE_HEADER_KEY))
            .isEqualTo(singletonList(FIRST_FILTER_ONLY_RESPONSE_HEADER_VALUE));

        assertThat(response.headers().getValues(SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE));
        if (hit404Path) {
            // 404 prevents last chunk filters from running because the 404 exception is thrown after the first chunk is processed but before the last chunk.
            assertThat(response.headers().getValues(SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY)).isEmpty();
        }
        else {
            assertThat(response.headers().getValues(SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY))
                .isEqualTo(singletonList(SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE));
        }
        assertThat(response.headers().getValues(SECOND_FILTER_ONLY_RESPONSE_HEADER_KEY))
            .isEqualTo(singletonList(SECOND_FILTER_ONLY_RESPONSE_HEADER_VALUE));

        assertThat(response.headers().getValues(THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY))
            .isEqualTo(singletonList(THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE));
        assertThat(response.header(THIRD_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY)).isNull();
        assertThat(response.headers().getValues(THIRD_FILTER_ONLY_RESPONSE_HEADER_KEY))
            .isEqualTo(singletonList(THIRD_FILTER_ONLY_RESPONSE_HEADER_VALUE));

        // The override request and response headers should be correct based on when the short circuit occurred
        assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_KEY))
            .isEqualTo(singletonList(THIRD_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE));
        if (hit404Path) {
            // 404 prevents last chunk filters from running because the 404 exception is thrown after the first chunk is processed but before the last chunk.
            assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_KEY)).isEmpty();
        }
        else {
            assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_KEY))
                .isEqualTo(singletonList(SECOND_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_VALUE));
        }
        assertThat(response.headers().getValues(COMMON_FILTER_RESPONSE_OVERRIDE_HEADER_KEY))
            .isEqualTo(singletonList(FIRST_FILTER_RESPONSE_OVERRIDE_HEADER_KEY));

        // The cumulative request and response headers should be correct based on when the short circuit occurred
        assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_KEY))
            .isEqualTo(Arrays.asList(FIRST_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE,
                                     SECOND_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE,
                                     THIRD_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE));
        if (hit404Path) {
            // 404 prevents last chunk filters from running because the 404 exception is thrown after the first chunk is processed but before the last chunk.
            assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_KEY)).isEmpty();
        }
        else {
            assertThat(response.headers().getValues(COMMON_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_KEY))
                .isEqualTo(Arrays.asList(FIRST_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE,
                                         SECOND_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE));
        }
        assertThat(response.headers().getValues(COMMON_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY))
            .isEqualTo(Arrays.asList(THIRD_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY,
                                     SECOND_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY,
                                     FIRST_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY));
    }

    public static class RequestAndResponseFilterTestConfig implements ServerConfig {
        private final int port;
        private final List<RequestAndResponseFilter> filters = Arrays.asList(new FirstFilterNormal(), new SecondFilterShortCircuiting(), new ThirdFilterNormal());
        private final Collection<Endpoint<?>> endpoints = singleton(new BasicEndpoint());

        public RequestAndResponseFilterTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public List<RequestAndResponseFilter> requestAndResponseFilters() {
            return filters;
        }

        @Override
        public int endpointsPort() {
            return port;
        }
    }

    private static class BasicEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/basicEndpoint";
        public static final String RESPONSE_PAYLOAD = "basic-endpoint-" + UUID.randomUUID().toString();
        public static final String FORCE_ERROR_HEADER_KEY = "force-error";
        public static final ApiError FORCED_ERROR = new ApiErrorBase("FORCED_ERROR", 42, "forced error", 542);

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            if ("true".equals(request.getHeaders().get(FORCE_ERROR_HEADER_KEY)))
                throw ApiException.newBuilder().withApiErrors(FORCED_ERROR).build();

            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }

    public static final String COMMON_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_KEY = "req-header-common-override-first-chunk";
    public static final String COMMON_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_KEY = "req-header-common-override-last-chunk";

    public static final String COMMON_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_KEY = "req-header-common-cumulative-first-chunk";
    public static final String COMMON_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_KEY = "req-header-common-cumulative-last-chunk";

    public static final String COMMON_FILTER_RESPONSE_OVERRIDE_HEADER_KEY = "response-header-common-override";
    public static final String COMMON_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY = "response-header-common-cumulative";

    protected static void addRequestHeadersToResponseIfNotAlreadyDone(RequestInfo<?> requestInfo, ResponseInfo<?> responseInfo) {
        String alreadyAddedHeaderKey = "added-request-headers-to-response";
        if (!"true".equals(responseInfo.getHeaders().get(alreadyAddedHeaderKey))) {
            requestInfo.getHeaders().names().forEach(name -> responseInfo.getHeaders().add(name, requestInfo.getHeaders().getAll(name)));
            responseInfo.getHeaders().set(alreadyAddedHeaderKey, "true");
        }
    }

    protected static class FirstFilterNormal implements RequestAndResponseFilter {
        public static final String FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY = "req-header-first-filter-only-first-chunk";
        public static final String FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY = "req-header-first-filter-only-last-chunk";
        public static final String FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String FIRST_FILTER_ONLY_RESPONSE_HEADER_KEY = "response-header-first-filter-only";
        public static final String FIRST_FILTER_ONLY_RESPONSE_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String FIRST_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE = UUID.randomUUID().toString();
        public static final String FIRST_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String FIRST_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE = UUID.randomUUID().toString();
        public static final String FIRST_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String FIRST_FILTER_RESPONSE_OVERRIDE_HEADER_KEY = UUID.randomUUID().toString();
        public static final String FIRST_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY = UUID.randomUUID().toString();

        @Override
        public <T> RequestInfo<T> filterRequestFirstChunkNoPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
            currentRequestInfo.getHeaders().set(FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY, FIRST_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE);
            currentRequestInfo.getHeaders().set(COMMON_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_KEY, FIRST_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE);
            currentRequestInfo.getHeaders().add(COMMON_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_KEY, FIRST_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE);

            return currentRequestInfo;
        }

        @Override
        public <T> RequestInfo<T> filterRequestLastChunkWithFullPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
            currentRequestInfo.getHeaders().set(FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY, FIRST_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE);
            currentRequestInfo.getHeaders().set(COMMON_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_KEY, FIRST_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_VALUE);
            currentRequestInfo.getHeaders().add(COMMON_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_KEY, FIRST_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE);

            return currentRequestInfo;
        }

        @Override
        public <T> ResponseInfo<T> filterResponse(ResponseInfo<T> currentResponseInfo, RequestInfo<?> requestInfo, ChannelHandlerContext ctx) {
            addRequestHeadersToResponseIfNotAlreadyDone(requestInfo, currentResponseInfo);

            currentResponseInfo.getHeaders().set(FIRST_FILTER_ONLY_RESPONSE_HEADER_KEY, FIRST_FILTER_ONLY_RESPONSE_HEADER_VALUE);
            currentResponseInfo.getHeaders().set(COMMON_FILTER_RESPONSE_OVERRIDE_HEADER_KEY, FIRST_FILTER_RESPONSE_OVERRIDE_HEADER_KEY);
            currentResponseInfo.getHeaders().add(COMMON_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY, FIRST_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY);

            return currentResponseInfo;
        }
    }

    protected static class SecondFilterShortCircuiting implements ShortCircuitingRequestAndResponseFilter {
        public static final String SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY = "req-header-second-filter-only-first-chunk";
        public static final String SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY = "req-header-second-filter-only-last-chunk";
        public static final String SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String SECOND_FILTER_ONLY_RESPONSE_HEADER_KEY = "response-header-second-filter-only";
        public static final String SECOND_FILTER_ONLY_RESPONSE_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String SECOND_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE = UUID.randomUUID().toString();
        public static final String SECOND_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String SECOND_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE = UUID.randomUUID().toString();
        public static final String SECOND_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String SECOND_FILTER_RESPONSE_OVERRIDE_HEADER_KEY = UUID.randomUUID().toString();
        public static final String SECOND_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY = UUID.randomUUID().toString();

        public static final String SHOULD_SHORT_CIRCUIT_FIRST_CHUNK = "short-circuit-first-chunk";
        public static final String SHOULD_SHORT_CIRCUIT_LAST_CHUNK = "short-circuit-second-chunk";

        public static final String SHORT_CIRCUIT_FIRST_CHUNK_RESPONSE_PAYLOAD = "short-circuit-first-chunk-" + UUID.randomUUID().toString();
        public static final String SHORT_CIRCUIT_LAST_CHUNK_RESPONSE_PAYLOAD = "short-circuit-last-chunk-" + UUID.randomUUID().toString();

        @Override
        public <T> Pair<RequestInfo<T>, Optional<ResponseInfo<?>>> filterRequestFirstChunkWithOptionalShortCircuitResponse(
            RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx
        ) {
            currentRequestInfo.getHeaders().set(SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY, SECOND_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE);
            currentRequestInfo.getHeaders().set(COMMON_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_KEY, SECOND_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE);
            currentRequestInfo.getHeaders().add(COMMON_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_KEY, SECOND_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE);

            boolean shouldShortCircuit = "true".equals(currentRequestInfo.getHeaders().get(SHOULD_SHORT_CIRCUIT_FIRST_CHUNK));
            ResponseInfo<?> response = (shouldShortCircuit)
                                       ? ResponseInfo.newBuilder(SHORT_CIRCUIT_FIRST_CHUNK_RESPONSE_PAYLOAD).build()
                                       : null;

            return Pair.of(currentRequestInfo, Optional.ofNullable(response));
        }

        @Override
        public <T> Pair<RequestInfo<T>, Optional<ResponseInfo<?>>> filterRequestLastChunkWithOptionalShortCircuitResponse(
            RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx
        ) {
            currentRequestInfo.getHeaders().set(SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY, SECOND_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE);
            currentRequestInfo.getHeaders().set(COMMON_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_KEY, SECOND_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_VALUE);
            currentRequestInfo.getHeaders().add(COMMON_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_KEY, SECOND_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE);

            boolean shouldShortCircuit = "true".equals(currentRequestInfo.getHeaders().get(SHOULD_SHORT_CIRCUIT_LAST_CHUNK));
            ResponseInfo<?> response = (shouldShortCircuit)
                                       ? ResponseInfo.newBuilder(SHORT_CIRCUIT_LAST_CHUNK_RESPONSE_PAYLOAD).build()
                                       : null;

            return Pair.of(currentRequestInfo, Optional.ofNullable(response));
        }

        @Override
        public <T> ResponseInfo<T> filterResponse(ResponseInfo<T> currentResponseInfo, RequestInfo<?> requestInfo, ChannelHandlerContext ctx) {
            addRequestHeadersToResponseIfNotAlreadyDone(requestInfo, currentResponseInfo);

            currentResponseInfo.getHeaders().set(SECOND_FILTER_ONLY_RESPONSE_HEADER_KEY, SECOND_FILTER_ONLY_RESPONSE_HEADER_VALUE);
            currentResponseInfo.getHeaders().set(COMMON_FILTER_RESPONSE_OVERRIDE_HEADER_KEY, SECOND_FILTER_RESPONSE_OVERRIDE_HEADER_KEY);
            currentResponseInfo.getHeaders().add(COMMON_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY, SECOND_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY);
            return currentResponseInfo;
        }
    }

    protected static class ThirdFilterNormal implements RequestAndResponseFilter {
        public static final String THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY = "req-header-third-filter-only-first-chunk";
        public static final String THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String THIRD_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY = "req-header-third-filter-only-last-chunk";
        public static final String THIRD_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String THIRD_FILTER_ONLY_RESPONSE_HEADER_KEY = "response-header-third-filter-only";
        public static final String THIRD_FILTER_ONLY_RESPONSE_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String THIRD_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE = UUID.randomUUID().toString();
        public static final String THIRD_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String THIRD_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE = UUID.randomUUID().toString();
        public static final String THIRD_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE = UUID.randomUUID().toString();

        public static final String THIRD_FILTER_RESPONSE_OVERRIDE_HEADER_KEY = UUID.randomUUID().toString();
        public static final String THIRD_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY = UUID.randomUUID().toString();

        @Override
        public <T> RequestInfo<T> filterRequestFirstChunkNoPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
            currentRequestInfo.getHeaders().set(THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_KEY, THIRD_FILTER_ONLY_FIRST_CHUNK_REQ_HEADER_VALUE);
            currentRequestInfo.getHeaders().set(COMMON_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_KEY, THIRD_FILTER_REQUEST_FIRST_CHUNK_OVERRIDE_HEADER_VALUE);
            currentRequestInfo.getHeaders().add(COMMON_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_KEY, THIRD_FILTER_REQUEST_FIRST_CHUNK_CUMULATIVE_HEADER_VALUE);

            return currentRequestInfo;
        }

        @Override
        public <T> RequestInfo<T> filterRequestLastChunkWithFullPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
            currentRequestInfo.getHeaders().set(THIRD_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_KEY, THIRD_FILTER_ONLY_LAST_CHUNK_REQ_HEADER_VALUE);
            currentRequestInfo.getHeaders().set(COMMON_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_KEY, THIRD_FILTER_REQUEST_LAST_CHUNK_OVERRIDE_HEADER_VALUE);
            currentRequestInfo.getHeaders().add(COMMON_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_KEY, THIRD_FILTER_REQUEST_LAST_CHUNK_CUMULATIVE_HEADER_VALUE);

            return currentRequestInfo;
        }

        @Override
        public <T> ResponseInfo<T> filterResponse(ResponseInfo<T> currentResponseInfo, RequestInfo<?> requestInfo, ChannelHandlerContext ctx) {
            addRequestHeadersToResponseIfNotAlreadyDone(requestInfo, currentResponseInfo);

            currentResponseInfo.getHeaders().set(THIRD_FILTER_ONLY_RESPONSE_HEADER_KEY, THIRD_FILTER_ONLY_RESPONSE_HEADER_VALUE);
            currentResponseInfo.getHeaders().set(COMMON_FILTER_RESPONSE_OVERRIDE_HEADER_KEY, THIRD_FILTER_RESPONSE_OVERRIDE_HEADER_KEY);
            currentResponseInfo.getHeaders().add(COMMON_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY, THIRD_FILTER_RESPONSE_CUMULATIVE_HEADER_KEY);
            return currentResponseInfo;
        }
    }
}
