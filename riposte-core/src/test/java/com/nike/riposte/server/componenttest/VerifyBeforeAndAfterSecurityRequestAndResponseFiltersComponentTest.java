package com.nike.riposte.server.componenttest;

import com.jayway.restassured.response.ExtractableResponse;
import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.exception.ApiException;
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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
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

import static com.jayway.restassured.RestAssured.given;
import static com.nike.riposte.server.componenttest.VerifyBeforeAndAfterSecurityRequestAndResponseFiltersComponentTest.AfterSecurityRequestFilter.*;
import static com.nike.riposte.server.componenttest.VerifyBeforeAndAfterSecurityRequestAndResponseFiltersComponentTest.BeforeSecurityRequestFilter.*;
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
                        .header("force-security-error", "false")
                        .log().all()
                        .when()
                        .get()
                        .then()
                        .log().headers()
                        .extract();

        assertThat(response.statusCode())
                .isEqualTo(200);
        //validate request was called
        assertThat(response.header(BEFORE_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED))
                .isEqualTo("true");
        assertThat(response.header(BEFORE_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED))
                .isEqualTo("true");
        assertThat(response.header(AFTER_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED))
                .isEqualTo("true");
        assertThat(response.header(AFTER_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED))
                .isEqualTo("true");
        //validate response filters
        assertThat(response.header(BEFORE_SECURITY_FILTER_RESPONSE_HEADER_KEY))
                .isEqualTo(BEFORE_SECURITY_FILTER_RESPONSE_HEADER_VALUE);
        assertThat(response.header(AFTER_SECURITY_FILTER_RESPONSE_HEADER_KEY))
                .isEqualTo(AFTER_SECURITY_FILTER_RESPONSE_HEADER_VALUE);
    }

    @Test
    public void shouldOnlyExecuteBeforeSecurityRequestFilterWhenSecurityErrorThrown() {
        ExtractableResponse response =
                given()
                        .baseUri("http://127.0.0.1")
                        .port(serverConfig.endpointsPort())
                        .basePath(BasicEndpoint.MATCHING_PATH)
                        .header("force-security-error", "true")
                        .log().all()
                        .when()
                        .get()
                        .then()
                        .log().headers()
                        .extract();

        assertThat(response.statusCode())
                .isEqualTo(401);

        //validate request filters
        assertThat(response.header(BEFORE_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED))
                .isEqualTo("true");
        assertThat(response.header(BEFORE_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED))
                .isEqualTo("false");
        assertThat(response.header(AFTER_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED))
                .isEqualTo("false");
        assertThat(response.header(AFTER_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED))
                .isEqualTo("false");
        //validate both response filters ran
        assertThat(response.header(BEFORE_SECURITY_FILTER_RESPONSE_HEADER_KEY))
                .isEqualTo(BEFORE_SECURITY_FILTER_RESPONSE_HEADER_VALUE);
        assertThat(response.header(AFTER_SECURITY_FILTER_RESPONSE_HEADER_KEY))
                .isEqualTo(AFTER_SECURITY_FILTER_RESPONSE_HEADER_VALUE);
    }

    public static class RequestAndResponseFilterTestConfig implements ServerConfig {
        private final int port;
        private final List<RequestAndResponseFilter> filters = Arrays.asList(new BeforeSecurityRequestFilter(), new AfterSecurityRequestFilter());
        private final Collection<Endpoint<?>> appEndpoints = singleton(new BasicEndpoint());

        public RequestAndResponseFilterTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return appEndpoints;
        }

        @Override
        public List<RequestAndResponseFilter> requestAndResponseFilters() {
            return filters;
        }

        @Override
        public int endpointsPort() {
            return port;
        }

        @Override
        public RequestSecurityValidator requestSecurityValidator() {
            return new TestRequestSecurityValidator(appEndpoints);
        }
    }

    protected static class TestRequestSecurityValidator implements RequestSecurityValidator {

        private final Collection<Endpoint<?>> endpointsToValidate;

        public TestRequestSecurityValidator(Collection<Endpoint<?>> endpointsToValidate) {
            this.endpointsToValidate = endpointsToValidate;
        }

        @Override
        public void validateSecureRequestForEndpoint(RequestInfo<?> requestInfo, Endpoint<?> endpoint) {
            if ("true".equals(requestInfo.getHeaders().get("force-security-error"))) {
                throw new Unauthorized401Exception("Forcing Security Error.", requestInfo.getPath(), null);
            }
        }

        @Override
        public Collection<Endpoint<?>> endpointsToValidate() {
            return endpointsToValidate;
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

    protected static class BeforeSecurityRequestFilter implements RequestAndResponseFilter {

        public static final String BEFORE_SECURITY_FILTER_FIRST_CHUNK_REQ_HEADER_KEY = "req-header-BEFORE-SECURITY-filter-first-chunk";
        public static final String BEFORE_SECURITY_FILTER_FIRST_CHUNK_REQ_HEADER_VALUE = UUID.randomUUID().toString();
        public static final String BEFORE_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED = "BEFORE_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED";

        public static final String BEFORE_SECURITY_FILTER_LAST_CHUNK_REQ_HEADER_KEY = "req-header-BEFORE-SECURITY-filter-last-chunk";
        public static final String BEFORE_SECURITY_FILTER_LAST_CHUNK_REQ_HEADER_VALUE = UUID.randomUUID().toString();
        public static final String BEFORE_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED = "BEFORE_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED";

        public static final String BEFORE_SECURITY_FILTER_RESPONSE_HEADER_KEY = "response-header-BEFORE-SECURITY-filter";
        public static final String BEFORE_SECURITY_FILTER_RESPONSE_HEADER_VALUE = UUID.randomUUID().toString();

        @Override
        public <T> RequestInfo<T> filterRequestFirstChunkNoPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
            currentRequestInfo.getRequestAttributes().put(BEFORE_SECURITY_FILTER_FIRST_CHUNK_REQ_HEADER_KEY, BEFORE_SECURITY_FILTER_FIRST_CHUNK_REQ_HEADER_VALUE);
            return currentRequestInfo;
        }

        @Override
        public <T> RequestInfo<T> filterRequestLastChunkWithFullPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
            currentRequestInfo.getRequestAttributes().put(BEFORE_SECURITY_FILTER_LAST_CHUNK_REQ_HEADER_KEY, BEFORE_SECURITY_FILTER_LAST_CHUNK_REQ_HEADER_VALUE);
            return currentRequestInfo;
        }

        @Override
        public <T> ResponseInfo<T> filterResponse(ResponseInfo<T> responseInfo, RequestInfo<?> requestInfo, ChannelHandlerContext ctx) {
            //assert request methods were called
            if (requestAttributeExists(requestInfo, BEFORE_SECURITY_FILTER_FIRST_CHUNK_REQ_HEADER_KEY)) {
                setHeader(responseInfo, BEFORE_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED, "true");
            } else {
                setHeader(responseInfo, BEFORE_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED, "false");
            }
            if (requestAttributeExists(requestInfo, BEFORE_SECURITY_FILTER_LAST_CHUNK_REQ_HEADER_KEY)) {
                setHeader(responseInfo, BEFORE_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED, "true");
            } else {
                setHeader(responseInfo, BEFORE_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED, "false");
            }
            //set value to assert from client
            setHeader(responseInfo, BEFORE_SECURITY_FILTER_RESPONSE_HEADER_KEY, BEFORE_SECURITY_FILTER_RESPONSE_HEADER_VALUE);
            return responseInfo;
        }

    }

    protected static class AfterSecurityRequestFilter implements RequestAndResponseFilter {

        public static final String AFTER_SECURITY_FILTER_FIRST_CHUNK_REQ_HEADER_KEY = "req-header-AFTER-SECURITY-filter-first-chunk";
        public static final String AFTER_SECURITY_FILTER_FIRST_CHUNK_REQ_HEADER_VALUE = UUID.randomUUID().toString();
        public static final String AFTER_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED = "AFTER_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED";

        public static final String AFTER_SECURITY_FILTER_LAST_CHUNK_REQ_HEADER_KEY = "req-header-AFTER-SECURITY-filter-last-chunk";
        public static final String AFTER_SECURITY_FILTER_LAST_CHUNK_REQ_HEADER_VALUE = UUID.randomUUID().toString();
        public static final String AFTER_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED = "AFTER_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED";

        public static final String AFTER_SECURITY_FILTER_RESPONSE_HEADER_KEY = "response-header-AFTER-SECURITY-filter";
        public static final String AFTER_SECURITY_FILTER_RESPONSE_HEADER_VALUE = UUID.randomUUID().toString();

        @Override
        public <T> RequestInfo<T> filterRequestFirstChunkNoPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
            currentRequestInfo.getRequestAttributes().put(AFTER_SECURITY_FILTER_FIRST_CHUNK_REQ_HEADER_KEY, AFTER_SECURITY_FILTER_FIRST_CHUNK_REQ_HEADER_VALUE);
            return currentRequestInfo;
        }

        @Override
        public <T> RequestInfo<T> filterRequestLastChunkWithFullPayload(RequestInfo<T> currentRequestInfo, ChannelHandlerContext ctx) {
            currentRequestInfo.getRequestAttributes().put(AFTER_SECURITY_FILTER_LAST_CHUNK_REQ_HEADER_KEY, AFTER_SECURITY_FILTER_LAST_CHUNK_REQ_HEADER_VALUE);
            return currentRequestInfo;
        }

        @Override
        public <T> ResponseInfo<T> filterResponse(ResponseInfo<T> responseInfo, RequestInfo<?> requestInfo, ChannelHandlerContext ctx) {
            //assert request methods were called
            if (requestAttributeExists(requestInfo, AFTER_SECURITY_FILTER_FIRST_CHUNK_REQ_HEADER_KEY)) {
                setHeader(responseInfo, AFTER_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED, "true");
            } else {
                setHeader(responseInfo, AFTER_SECURITY_FILTER_REQUEST_FIRST_CHUNK_EXECUTED, "false");
            }
            if (requestAttributeExists(requestInfo, AFTER_SECURITY_FILTER_LAST_CHUNK_REQ_HEADER_KEY)) {
                setHeader(responseInfo, AFTER_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED, "true");
            } else {
                setHeader(responseInfo, AFTER_SECURITY_FILTER_REQUEST_LAST_CHUNK_EXECUTED, "false");
            }
            //set value to assert from client
            setHeader(responseInfo, AFTER_SECURITY_FILTER_RESPONSE_HEADER_KEY, AFTER_SECURITY_FILTER_RESPONSE_HEADER_VALUE);
            return responseInfo;
        }

        @Override
        public boolean shouldExecuteBeforeSecurityValidation() {
            return false;
        }
    }

    private static boolean requestAttributeExists(RequestInfo<?> requestInfo, String attributeName) {
        return requestInfo.getRequestAttributes().get(attributeName) != null;
    }

    private static void setHeader(ResponseInfo<?> currentResponseInfo, String headerName, String headerValue) {
        currentResponseInfo.getHeaders().set(headerName, headerValue);
    }
    
}
