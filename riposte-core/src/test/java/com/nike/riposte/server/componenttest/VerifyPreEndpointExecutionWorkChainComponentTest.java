package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.apierror.sample.SampleCoreApiError;
import com.nike.backstopper.exception.ApiException;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.error.validation.RequestSecurityValidator;
import com.nike.riposte.server.error.validation.RequestValidator;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.restassured.response.ExtractableResponse;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class VerifyPreEndpointExecutionWorkChainComponentTest {

    private static Server server;
    private static ServerConfig serverConfig;
    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;
    private static Server nonWorkChainServer;
    private static ServerConfig nonWorkChainServerConfig;
    private static final List<String> securityValidationThreadNames = new ArrayList<>();
    private static final List<String> contentDeserializationThreadNames = new ArrayList<>();
    private static final List<String> contentValidationThreadNames = new ArrayList<>();
    private static final List<String> endpointThreadNames = new ArrayList<>();
    private static final String BLOW_UP_IN_SECURITY_VALIDATOR_HEADER_KEY = "blowUpInSecurityValidator";
    private static final String BLOW_UP_IN_CONTENT_DESERIALIZER_HEADER_KEY = "blowUpInContentDeserializer";
    private static final String BLOW_UP_IN_CONTENT_VALIDATOR_HEADER_KEY = "blowUpInContentValidator";
    private static final String PROXY_ROUTER_DESTINATION_PORT_HEADER_KEY = "destinationPortHeaderKey";
    private static final ApiError SECURITY_VALIDATOR_API_ERROR = new ApiErrorBase("SECURITY_VALIDATOR_ERROR", 1, "blew up in security validator", 401);
    private static final ApiError CONTENT_VALIDATOR_API_ERROR = new ApiErrorBase("CONTENT_VALIDATOR_ERROR", 3, "blew up in content validator", 400);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeClass
    public static void setUpClass() throws Exception {
        serverConfig = new PreEndpointExecutionWorkChainTestConfig(false);
        server = new Server(serverConfig);
        server.startup();

        downstreamServerConfig = new PreEndpointExecutionWorkChainTestConfig(false);
        downstreamServer = new Server(downstreamServerConfig);
        downstreamServer.startup();

        nonWorkChainServerConfig = new PreEndpointExecutionWorkChainTestConfig(true);
        nonWorkChainServer = new Server(nonWorkChainServerConfig);
        nonWorkChainServer.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
    }

    @Before
    public void beforeMethod() {
        securityValidationThreadNames.clear();
        contentDeserializationThreadNames.clear();
        contentValidationThreadNames.clear();
        endpointThreadNames.clear();
    }

    // NORMAL ENDPOINT TESTS WITH ASYNC WORK CHAIN EXECUTION ============================================
    @Test
    public void verify_work_chain_executed_for_successful_call() throws IOException, InterruptedException {
        String fooValue = UUID.randomUUID().toString();
        String postData = objectMapper.writeValueAsString(new PostObj(fooValue));

        String responseString =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(WorkChainEndpoint.WORK_CHAIN_MATCHING_PATH)
                .body(postData)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract().asString();

        Thread.sleep(10);

        assertThat(responseString).isEqualTo("work chain passed, post obj foo: " + fooValue);
        verifyAsyncWorkerChainExecution(1, 1, 1, false, true);
    }

    @Test
    public void verify_work_chain_stops_at_security_validator_if_security_validator_blows_up() throws IOException, InterruptedException {
        String fooValue = UUID.randomUUID().toString();
        String postData = objectMapper.writeValueAsString(new PostObj(fooValue));

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(WorkChainEndpoint.WORK_CHAIN_MATCHING_PATH)
                .header(BLOW_UP_IN_SECURITY_VALIDATOR_HEADER_KEY, "true")
                .body(postData)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .extract();

        Thread.sleep(10);

        verifyErrorReceived(response, SECURITY_VALIDATOR_API_ERROR);
        verifyAsyncWorkerChainExecution(1, 0, 0, false, false);
    }

    @Test
    public void verify_work_chain_stops_at_content_deserialization_if_content_deserializer_blows_up() throws IOException, InterruptedException {
        String fooValue = UUID.randomUUID().toString();
        String postData = objectMapper.writeValueAsString(new PostObj(fooValue));

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(WorkChainEndpoint.WORK_CHAIN_MATCHING_PATH)
                .header(BLOW_UP_IN_CONTENT_DESERIALIZER_HEADER_KEY, "true")
                .body(postData)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .extract();

        Thread.sleep(10);

        verifyErrorReceived(response, SampleCoreApiError.MALFORMED_REQUEST);
        verifyAsyncWorkerChainExecution(1, 1, 0, false, false);
    }

    @Test
    public void verify_work_chain_stops_at_content_validator_if_content_validator_blows_up() throws IOException, InterruptedException {
        String fooValue = UUID.randomUUID().toString();
        String postData = objectMapper.writeValueAsString(new PostObj(fooValue));

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(WorkChainEndpoint.WORK_CHAIN_MATCHING_PATH)
                .header(BLOW_UP_IN_CONTENT_VALIDATOR_HEADER_KEY, "true")
                .body(postData)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .extract();

        Thread.sleep(10);

        verifyErrorReceived(response, CONTENT_VALIDATOR_API_ERROR);
        verifyAsyncWorkerChainExecution(1, 1, 1, false, false);
    }

    // PROXY ENDPOINT TESTS ============================================
    @Test
    public void verify_work_chain_executed_for_successful_proxy_call() throws IOException, InterruptedException {
        String fooValue = UUID.randomUUID().toString();
        String postData = objectMapper.writeValueAsString(new PostObj(fooValue));

        String responseString =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(WorkChainProxyEndpoint.PROXY_MATCHING_PATH)
                .header(PROXY_ROUTER_DESTINATION_PORT_HEADER_KEY, String.valueOf(downstreamServerConfig.endpointsPort()))
                .body(postData)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract().asString();

        Thread.sleep(10);

        assertThat(responseString).isEqualTo("work chain passed, post obj foo: " + fooValue);
        // Since the proxy router endpoint doesn't try to deserialize or validate content,
        // we should only have 1 for content deserialization and validation executions
        verifyAsyncWorkerChainExecution(2, 1, 1, true, true);
    }

    @Test
    public void verify_proxy_work_chain_stops_at_security_validator_if_security_validator_blows_up() throws IOException, InterruptedException {
        String fooValue = UUID.randomUUID().toString();
        String postData = objectMapper.writeValueAsString(new PostObj(fooValue));

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(WorkChainProxyEndpoint.PROXY_MATCHING_PATH)
                .header(PROXY_ROUTER_DESTINATION_PORT_HEADER_KEY, String.valueOf(downstreamServerConfig.endpointsPort()))
                .header(BLOW_UP_IN_SECURITY_VALIDATOR_HEADER_KEY, "true")
                .body(postData)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .extract();

        Thread.sleep(10);

        verifyErrorReceived(response, SECURITY_VALIDATOR_API_ERROR);
        verifyAsyncWorkerChainExecution(1, 0, 0, false, false);
    }

    @Test
    public void verify_proxy_work_chain_stops_at_downstream_content_deserialization_if_content_deserializer_blows_up() throws IOException, InterruptedException {
        String fooValue = UUID.randomUUID().toString();
        String postData = objectMapper.writeValueAsString(new PostObj(fooValue));

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(WorkChainProxyEndpoint.PROXY_MATCHING_PATH)
                .header(PROXY_ROUTER_DESTINATION_PORT_HEADER_KEY, String.valueOf(downstreamServerConfig.endpointsPort()))
                .header(BLOW_UP_IN_CONTENT_DESERIALIZER_HEADER_KEY, "true")
                .body(postData)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .extract();

        Thread.sleep(10);

        verifyErrorReceived(response, SampleCoreApiError.MALFORMED_REQUEST);
        verifyAsyncWorkerChainExecution(2, 1, 0, true, false);
    }

    @Test
    public void verify_proxy_work_chain_stops_at_downstream_content_validation_if_content_validator_blows_up() throws IOException, InterruptedException {
        String fooValue = UUID.randomUUID().toString();
        String postData = objectMapper.writeValueAsString(new PostObj(fooValue));

        ExtractableResponse response =
            given()
                .baseUri("http://127.0.0.1")
                .port(serverConfig.endpointsPort())
                .basePath(WorkChainProxyEndpoint.PROXY_MATCHING_PATH)
                .header(PROXY_ROUTER_DESTINATION_PORT_HEADER_KEY, String.valueOf(downstreamServerConfig.endpointsPort()))
                .header(BLOW_UP_IN_CONTENT_VALIDATOR_HEADER_KEY, "true")
                .body(postData)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .extract();

        Thread.sleep(10);

        verifyErrorReceived(response, CONTENT_VALIDATOR_API_ERROR);
        verifyAsyncWorkerChainExecution(2, 1, 1, true, false);
    }

    // NORMAL ENDPOINT TESTS WITH SYNCHRONOUS NON WORK CHAIN EXECUTION ============================================
    @Test
    public void verify_happy_path_for_synchronous_non_work_chain_successful_call() throws IOException, InterruptedException {
        String fooValue = UUID.randomUUID().toString();
        String postData = objectMapper.writeValueAsString(new PostObj(fooValue));

        String responseString =
            given()
                .baseUri("http://127.0.0.1")
                .port(nonWorkChainServerConfig.endpointsPort())
                .basePath(NonWorkChainEndpoint.NON_WORK_CHAIN_MATCHING_PATH)
                .body(postData)
                .log().all()
            .when()
                .post()
            .then()
                .log().all()
                .statusCode(200)
                .extract().asString();

        Thread.sleep(10);

        assertThat(responseString).isEqualTo("non work chain passed, post obj foo: " + fooValue);
        assertThat(securityValidationThreadNames).hasSize(1);
        assertThat(contentDeserializationThreadNames).hasSize(1);
        assertThat(contentValidationThreadNames).hasSize(1);
        assertThat(endpointThreadNames).hasSize(1);
        assertThat(securityValidationThreadNames.get(0)).contains("EventLoopGroup");
        assertThat(contentDeserializationThreadNames.get(0)).contains("EventLoopGroup");
        assertThat(contentValidationThreadNames.get(0)).contains("EventLoopGroup");
        assertThat(endpointThreadNames.get(0)).contains("EventLoopGroup");
        assertThat(endpointThreadNames.get(0)).endsWith("-NONWORKCHAIN-ENDPOINT");
    }

    private void verifyAsyncWorkerChainExecution(int expectedSecurityValidationCount, int expectedContentDeserializationCount,
                                                 int expectedContentValidationCount, boolean expectProxyEndpointExecution,
                                                 boolean expectNormalEndpointExecution) {

        assertThat(securityValidationThreadNames).hasSize(expectedSecurityValidationCount);
        securityValidationThreadNames.forEach(tn -> assertThat(tn).startsWith("ForkJoinPool"));

        assertThat(contentDeserializationThreadNames).hasSize(expectedContentDeserializationCount);
        contentDeserializationThreadNames.forEach(tn -> assertThat(tn).startsWith("ForkJoinPool"));

        assertThat(contentValidationThreadNames).hasSize(expectedContentValidationCount);
        contentValidationThreadNames.forEach(tn -> assertThat(tn).startsWith("ForkJoinPool"));

        if (expectNormalEndpointExecution && expectProxyEndpointExecution) {
            assertThat(endpointThreadNames).hasSize(2);
            assertThat(endpointThreadNames.get(0)).endsWith("-PROXY-ENDPOINT");
            assertThat(endpointThreadNames.get(1)).endsWith("-NORMAL-ENDPOINT");
        }
        else if (expectProxyEndpointExecution) {
            assertThat(endpointThreadNames).hasSize(1);
            assertThat(endpointThreadNames.get(0)).endsWith("-PROXY-ENDPOINT");
        }
        else if (expectNormalEndpointExecution) {
            assertThat(endpointThreadNames).hasSize(1);
            assertThat(endpointThreadNames.get(0)).endsWith("-NORMAL-ENDPOINT");
        }
        else
            assertThat(endpointThreadNames).isEmpty();
    }

    private void verifyErrorReceived(ExtractableResponse response, ApiError expectedApiError) throws IOException {
        assertThat(response.statusCode()).isEqualTo(expectedApiError.getHttpStatusCode());
        DefaultErrorContractDTO responseAsError = objectMapper.readValue(response.asString(), DefaultErrorContractDTO.class);
        assertThat(responseAsError.errors).hasSize(1);
        assertThat(responseAsError.errors.get(0).code).isEqualTo(expectedApiError.getErrorCode());
        assertThat(responseAsError.errors.get(0).message).isEqualTo(expectedApiError.getMessage());
    }

    private static RequestValidator customContentValidatorForWorkChainNotification() {
        return new RequestValidator() {
            @Override
            public void validateRequestContent(RequestInfo<?> request) {
                contentValidationThreadNames.add(Thread.currentThread().getName());
                if ("true".equals(request.getHeaders().get(BLOW_UP_IN_CONTENT_VALIDATOR_HEADER_KEY)))
                    throw new ApiException(CONTENT_VALIDATOR_API_ERROR);
            }

            @Override
            public void validateRequestContent(RequestInfo<?> request, Class<?>... validationGroups) {
                contentValidationThreadNames.add(Thread.currentThread().getName());
                if ("true".equals(request.getHeaders().get(BLOW_UP_IN_CONTENT_VALIDATOR_HEADER_KEY)))
                    throw new ApiException(CONTENT_VALIDATOR_API_ERROR);
            }
        };
    }

    private static RequestSecurityValidator customSecurityValidatorForWorkChainNotification(
        Collection<Endpoint<?>> endpointsToValidate, boolean disableWorkChain) {
        return new RequestSecurityValidator() {
            @Override
            public void validateSecureRequestForEndpoint(RequestInfo<?> requestInfo, Endpoint<?> endpoint) {
                securityValidationThreadNames.add(Thread.currentThread().getName());

                if ("true".equals(requestInfo.getHeaders().get(BLOW_UP_IN_SECURITY_VALIDATOR_HEADER_KEY)))
                    throw new ApiException(SECURITY_VALIDATOR_API_ERROR);
            }

            @Override
            public Collection<Endpoint<?>> endpointsToValidate() {
                return endpointsToValidate;
            }

            @Override
            public boolean isFastEnoughToRunOnNettyWorkerThread() {
                return disableWorkChain;
            }
        };
    }

    private static ObjectMapper customRequestContentDeserializerForWorkChainNotification(RequestInfo<?> request) {
        return new ObjectMapper() {
            @Override
            public <T> T readValue(byte[] src, TypeReference valueTypeRef) throws IOException {
                contentDeserializationThreadNames.add(Thread.currentThread().getName());
                if ("true".equals(request.getHeaders().get(BLOW_UP_IN_CONTENT_DESERIALIZER_HEADER_KEY)))
                    throw new ApiException(SampleCoreApiError.MALFORMED_REQUEST);

                return super.readValue(src, valueTypeRef);
            }
        };
    }

    public static class PreEndpointExecutionWorkChainTestConfig implements ServerConfig {
        private final int port;
        private final RequestValidator customContentValidatorForWorkChainNotification;
        private final RequestSecurityValidator customSecurityValidatorForWorkChainNotification;
        private final Collection<Endpoint<?>> endpoints;

        public PreEndpointExecutionWorkChainTestConfig(boolean disableWorkChain) {
            endpoints = Arrays.asList(
                new WorkChainEndpoint(),
                new WorkChainProxyEndpoint(),
                new NonWorkChainEndpoint()
            );
            this.customContentValidatorForWorkChainNotification = customContentValidatorForWorkChainNotification();
            this.customSecurityValidatorForWorkChainNotification = customSecurityValidatorForWorkChainNotification(endpoints, disableWorkChain);

            try {
                this.port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }

        @Override
        public RequestValidator requestContentValidationService() {
            return customContentValidatorForWorkChainNotification;
        }

        @Override
        public RequestSecurityValidator requestSecurityValidator() {
            return customSecurityValidatorForWorkChainNotification;
        }
    }

    public static class WorkChainEndpoint extends StandardEndpoint<PostObj, String> {

        public static String WORK_CHAIN_MATCHING_PATH = "/workChain";

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<PostObj> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            endpointThreadNames.add(Thread.currentThread().getName() + "-NORMAL-ENDPOINT");
            return CompletableFuture.completedFuture(ResponseInfo.newBuilder("work chain passed, post obj foo: " + request.getContent().foo).build());
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(WORK_CHAIN_MATCHING_PATH, HttpMethod.POST);
        }

        // Force asynchronous deserialization/validation
        @Override
        public boolean shouldValidateAsynchronously(RequestInfo<?> request) {
            return true;
        }

        // Use a deserializer that will keep track of when it was called and on what thread.
        @Override
        public ObjectMapper customRequestContentDeserializer(RequestInfo<?> request) {
            return customRequestContentDeserializerForWorkChainNotification(request);
        }
    }

    public static class NonWorkChainEndpoint extends StandardEndpoint<PostObj, String> {

        public static String NON_WORK_CHAIN_MATCHING_PATH = "/nonWorkChain";

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<PostObj> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            endpointThreadNames.add(Thread.currentThread().getName() + "-NONWORKCHAIN-ENDPOINT");
            return CompletableFuture.completedFuture(ResponseInfo.newBuilder("non work chain passed, post obj foo: " + request.getContent().foo).build());
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(NON_WORK_CHAIN_MATCHING_PATH, HttpMethod.POST);
        }

        // Force synchronous deserialization/validation
        @Override
        public boolean shouldValidateAsynchronously(RequestInfo<?> request) {
            return false;
        }

        // Use a deserializer that will keep track of when it was called and on what thread.
        @Override
        public ObjectMapper customRequestContentDeserializer(RequestInfo<?> request) {
            return customRequestContentDeserializerForWorkChainNotification(request);
        }
    }

    public static class WorkChainProxyEndpoint extends ProxyRouterEndpoint {

        public static String PROXY_MATCHING_PATH = "/workChainProxy";

        @Override
        public CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(RequestInfo<?> request,
                                                                                                     Executor longRunningTaskExecutor,
                                                                                                     ChannelHandlerContext ctx) {
            endpointThreadNames.add(Thread.currentThread().getName() + "-PROXY-ENDPOINT");
            int destinationPort = Integer.parseInt(request.getHeaders().get(PROXY_ROUTER_DESTINATION_PORT_HEADER_KEY));
            return CompletableFuture.completedFuture(
                new DownstreamRequestFirstChunkInfo("localhost", destinationPort, false,
                                                    generateSimplePassthroughRequest(request, WorkChainEndpoint.WORK_CHAIN_MATCHING_PATH, request.getMethod(), ctx))
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(PROXY_MATCHING_PATH, HttpMethod.POST);
        }

        // Force asynchronous deserialization/validation (won't actually matter since proxy endpoints don't do content deserializaiton or validation)
        @Override
        public boolean shouldValidateAsynchronously(RequestInfo request) {
            return true;
        }

        // Use a deserializer that will keep track of when it was called and on what thread.
        @Override
        public ObjectMapper customRequestContentDeserializer(RequestInfo request) {
            return customRequestContentDeserializerForWorkChainNotification(request);
        }
    }

    public static class PostObj {
        public final String foo;

        @SuppressWarnings("unused")
        private PostObj() { this(null); }

        public PostObj(String foo) {
            this.foo = foo;
        }
    }
}
