package com.nike.riposte.server.componenttest;

import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.error.exception.Unauthorized401Exception;
import com.nike.riposte.server.error.validation.RequestSecurityValidator;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.http.filter.RequestAndResponseFilter;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;

import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.restassured.response.ExtractableResponse;

import static com.nike.riposte.server.componenttest.VerifyBeforeAndAfterSecurityRequestAndResponseFiltersComponentTest.TestRequestSecurityValidator.FORCE_SECURITY_ERROR_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyBeforeAndAfterSecurityRequestAndResponseFiltersComponentTest.TestRequestSecurityValidator.SECURITY_VALIDATOR_EXECUTED_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyBeforeAndAfterSecurityRequestAndResponseFiltersComponentTest.TestRequestSecurityValidator.SECURITY_VALIDATOR_THREW_ERROR_HEADER_KEY;
import static io.restassured.RestAssured.given;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(DataProviderRunner.class)
public class VerifyBeforeAndAfterSecurityRequestAndResponseFiltersComponentTest {

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

    @Test
    public void shouldExecuteBeforeAndAfterRequestAndResponseFilters() {
        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(BasicEndpoint.MATCHING_PATH)
                .header(FORCE_SECURITY_ERROR_HEADER_KEY, "false")
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        // Validate that the security validator was called and that it did not throw an exception.
        assertThat(response.statusCode())
                .isEqualTo(200);
        assertThat(response.header(SECURITY_VALIDATOR_EXECUTED_HEADER_KEY))
            .isEqualTo("true");
        assertThat(response.header(SECURITY_VALIDATOR_THREW_ERROR_HEADER_KEY))
            .isEqualTo("false");

        // Validate request filter methods were called.
        assertThat(response.header(BEFORE_SECURITY_FIRST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY))
                .isEqualTo("true");
        assertThat(response.header(BEFORE_SECURITY_LAST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY))
                .isEqualTo("true");
        assertThat(response.header(AFTER_SECURITY_FIRST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY))
                .isEqualTo("true");
        assertThat(response.header(AFTER_SECURITY_LAST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY))
                .isEqualTo("true");
        // Validate response filter method was called.
        assertThat(response.header(BEFORE_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_KEY))
                .isEqualTo(BEFORE_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_PAYLOAD);
        assertThat(response.header(AFTER_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_KEY))
                .isEqualTo(AFTER_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_PAYLOAD);
    }

    @Test
    public void shouldOnlyExecuteBeforeSecurityRequestFilterWhenSecurityErrorThrown() {
        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(BasicEndpoint.MATCHING_PATH)
                .header(FORCE_SECURITY_ERROR_HEADER_KEY, "true")
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        // Validate that the security validator was called and that it threw an exception.
        assertThat(response.statusCode())
                .isEqualTo(401);
        assertThat(response.header(SECURITY_VALIDATOR_EXECUTED_HEADER_KEY))
            .isEqualTo("true");
        assertThat(response.header(SECURITY_VALIDATOR_THREW_ERROR_HEADER_KEY))
            .isEqualTo("true");

        // Validate request filter methods were called.
        assertThat(response.header(BEFORE_SECURITY_FIRST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY))
                .isEqualTo("true");
        assertThat(response.header(BEFORE_SECURITY_LAST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY))
                .isEqualTo("false");
        assertThat(response.header(AFTER_SECURITY_FIRST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY))
                .isEqualTo("false");
        assertThat(response.header(AFTER_SECURITY_LAST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY))
                .isEqualTo("false");
        // Validate response filter method was called.
        assertThat(response.header(BEFORE_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_KEY))
            .isEqualTo(BEFORE_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_PAYLOAD);
        assertThat(response.header(AFTER_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_KEY))
            .isEqualTo(AFTER_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_PAYLOAD);
    }

    public static class RequestAndResponseFilterTestConfig implements ServerConfig {
        private final int port;
        private final List<RequestAndResponseFilter> filters = Arrays.asList(
            new ExecutionInfoRequestFilter(true,
                                           BEFORE_SECURITY_FIRST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY,
                                           BEFORE_SECURITY_LAST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY,
                                           BEFORE_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_KEY,
                                           BEFORE_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_PAYLOAD),
            new ExecutionInfoRequestFilter(false,
                                           AFTER_SECURITY_FIRST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY,
                                           AFTER_SECURITY_LAST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY,
                                           AFTER_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_KEY,
                                           AFTER_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_PAYLOAD)
        );
        private final Collection<Endpoint<?>> appEndpoints = singleton(new BasicEndpoint());

        public RequestAndResponseFilterTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
        }

        @Override
        public @NotNull Collection<@NotNull Endpoint<?>> appEndpoints() {
            return appEndpoints;
        }

        @Override
        public @Nullable List<@NotNull RequestAndResponseFilter> requestAndResponseFilters() {
            return filters;
        }

        @Override
        public int endpointsPort() {
            return port;
        }

        @Override
        public @Nullable RequestSecurityValidator requestSecurityValidator() {
            return new TestRequestSecurityValidator(appEndpoints);
        }
    }

    protected static class TestRequestSecurityValidator implements RequestSecurityValidator {

        private final Collection<Endpoint<?>> endpointsToValidate;
        public static final String FORCE_SECURITY_ERROR_HEADER_KEY = "force-security-error";
        public static final String SECURITY_VALIDATOR_EXECUTED_HEADER_KEY = "security-validator-executed";
        public static final String SECURITY_VALIDATOR_THREW_ERROR_HEADER_KEY = "security-validator-threw-error";

        public TestRequestSecurityValidator(Collection<Endpoint<?>> endpointsToValidate) {
            this.endpointsToValidate = endpointsToValidate;
        }

        @Override
        public void validateSecureRequestForEndpoint(
            @NotNull RequestInfo<?> requestInfo,
            @NotNull Endpoint<?> endpoint
        ) {
            requestInfo.addRequestAttribute(SECURITY_VALIDATOR_EXECUTED_HEADER_KEY, true);

            if ("true".equals(requestInfo.getHeaders().get(FORCE_SECURITY_ERROR_HEADER_KEY))) {
                requestInfo.addRequestAttribute(SECURITY_VALIDATOR_THREW_ERROR_HEADER_KEY, true);
                throw new Unauthorized401Exception("Forcing Security Error.", requestInfo.getPath(), null);
            }
            else
                requestInfo.addRequestAttribute(SECURITY_VALIDATOR_THREW_ERROR_HEADER_KEY, false);
        }

        @Override
        public @NotNull Collection<Endpoint<?>> endpointsToValidate() {
            return endpointsToValidate;
        }
    }

    private static class BasicEndpoint extends StandardEndpoint<Void, Void> {

        public static final String MATCHING_PATH = "/basicEndpoint";

        @Override
        public @NotNull CompletableFuture<ResponseInfo<Void>> execute(
            @NotNull RequestInfo<Void> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            return CompletableFuture.completedFuture(ResponseInfo.<Void>newBuilder().build());
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }

    }

    public static final String BEFORE_SECURITY_FIRST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY = "BEFORE-security-FIRST-chunk-filter-request-method-executed";
    public static final String BEFORE_SECURITY_LAST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY = "BEFORE-security-LAST-chunk-filter-request-method-executed";
    public static final String BEFORE_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_KEY = "BEFORE-security-RESPONSE-filter-method-executed";
    public static final String BEFORE_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_PAYLOAD = UUID.randomUUID().toString();

    public static final String AFTER_SECURITY_FIRST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY = "AFTER-security-FIRST-chunk-filter-request-method-executed";
    public static final String AFTER_SECURITY_LAST_CHUNK_FILTER_REQUEST_METHOD_EXECUTED_KEY = "AFTER-security-LAST-chunk-filter-request-method-executed";
    public static final String AFTER_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_KEY = "AFTER-security-RESPONSE-filter-method-executed";
    public static final String AFTER_SECURITY_RESPONSE_FILTER_METHOD_EXECUTED_PAYLOAD = UUID.randomUUID().toString();

    protected static class ExecutionInfoRequestFilter implements RequestAndResponseFilter {

        private final boolean shouldExecuteBeforeSecurityValidation;

        private final String firstChunkReqMethodExecutedKey;
        private final String lastChunkReqMethodExecutedKey;

        private final String responseMethodExecutedKey;
        private final String responseMethodExecutedPayload;

        public ExecutionInfoRequestFilter(boolean shouldExecuteBeforeSecurityValidation,
                                          String firstChunkReqMethodExecutedKey,
                                          String lastChunkReqMethodExecutedKey,
                                          String responseMethodExecutedKey,
                                          String responseMethodExecutedPayload) {
            this.shouldExecuteBeforeSecurityValidation = shouldExecuteBeforeSecurityValidation;
            this.firstChunkReqMethodExecutedKey = firstChunkReqMethodExecutedKey;
            this.lastChunkReqMethodExecutedKey = lastChunkReqMethodExecutedKey;
            this.responseMethodExecutedKey = responseMethodExecutedKey;
            this.responseMethodExecutedPayload = responseMethodExecutedPayload;
        }

        @Override
        public boolean shouldExecuteBeforeSecurityValidation() {
            return shouldExecuteBeforeSecurityValidation;
        }

        @Override
        public <T> @Nullable RequestInfo<T> filterRequestFirstChunkNoPayload(
            @NotNull RequestInfo<T> currentRequestInfo, @NotNull ChannelHandlerContext ctx
        ) {
            currentRequestInfo.getRequestAttributes().put(firstChunkReqMethodExecutedKey, true);
            return currentRequestInfo;
        }

        @Override
        public <T> @Nullable RequestInfo<T> filterRequestLastChunkWithFullPayload(
            @NotNull RequestInfo<T> currentRequestInfo, @NotNull ChannelHandlerContext ctx
        ) {
            currentRequestInfo.getRequestAttributes().put(lastChunkReqMethodExecutedKey, true);
            return currentRequestInfo;
        }

        @Override
        public <T> @Nullable ResponseInfo<T> filterResponse(
            @NotNull ResponseInfo<T> responseInfo,
            @NotNull RequestInfo<?> requestInfo,
            @NotNull ChannelHandlerContext ctx
        ) {
            // Indicate whether or not the first/last chunk request methods were executed for this filter
            //      so that the caller can assert based on what it expects.
            setHeader(responseInfo,
                      firstChunkReqMethodExecutedKey,
                      requestAttributeIsSetToTrue(requestInfo, firstChunkReqMethodExecutedKey));

            setHeader(responseInfo,
                      lastChunkReqMethodExecutedKey,
                      requestAttributeIsSetToTrue(requestInfo, lastChunkReqMethodExecutedKey));

            // Indicate whether or not the security validator was called, and whether or not it threw an exception.
            setHeader(responseInfo,
                      SECURITY_VALIDATOR_EXECUTED_HEADER_KEY,
                      requestAttributeIsSetToTrue(requestInfo, SECURITY_VALIDATOR_EXECUTED_HEADER_KEY));
            setHeader(responseInfo,
                      SECURITY_VALIDATOR_THREW_ERROR_HEADER_KEY,
                      requestAttributeIsSetToTrue(requestInfo, SECURITY_VALIDATOR_THREW_ERROR_HEADER_KEY));

            // Add a header so the caller knows the response method for this filter was executed.
            setHeader(responseInfo, responseMethodExecutedKey, responseMethodExecutedPayload);
            return responseInfo;
        }

    }

    private static boolean requestAttributeIsSetToTrue(RequestInfo<?> requestInfo, String attributeName) {
        return Boolean.TRUE.equals(requestInfo.getRequestAttributes().get(attributeName));
    }

    private static void setHeader(ResponseInfo<?> currentResponseInfo, String headerName, Object headerValue) {
        currentResponseInfo.getHeaders().set(headerName, headerValue);
    }
    
}
