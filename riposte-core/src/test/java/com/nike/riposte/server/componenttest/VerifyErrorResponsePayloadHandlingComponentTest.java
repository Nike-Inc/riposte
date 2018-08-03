package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectSpecificErrorCodeRange;
import com.nike.backstopper.apierror.testutil.ProjectApiErrorsForTesting;
import com.nike.backstopper.handler.ApiExceptionHandlerUtils;
import com.nike.backstopper.handler.RequestInfoForLogging;
import com.nike.backstopper.handler.riposte.RiposteUnhandledExceptionHandler;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import io.restassured.response.ExtractableResponse;

import static com.nike.backstopper.apierror.testutil.BarebonesCoreApiErrorForTesting.GENERIC_SERVICE_ERROR;
import static com.nike.riposte.server.testutils.ComponentTestUtils.verifyErrorReceived;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class VerifyErrorResponsePayloadHandlingComponentTest {
    private static final String BASE_URI = "http://127.0.0.1";
    private static Server server;
    private static ServerConfig serverConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        serverConfig = new ErrorResponsePayloadTestingServerConfig();
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void should_return_blank_payload_when_ErrorResponseBody_bodyToSerialize_is_null() {
        ExtractableResponse response =
            given()
                .baseUri(BASE_URI)
                .port(serverConfig.endpointsPort())
                .basePath(BlankPayloadErrorContractEndpoint.MATCHING_PATH)
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        assertThat(response.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR.code());
        assertThat(response.asString()).isEmpty();
        assertThat(response.header("error_uid")).startsWith(EXPECTED_CUSTOM_ERROR_UID_RESPONSE_HEADER_PREFIX);
    }

    @Test
    public void should_return_delegate_payload_when_ErrorResponseBody_bodyToSerialize_is_delegate() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String desiredErrorMessage = UUID.randomUUID().toString();

        ExtractableResponse response =
                given()
                    .baseUri(BASE_URI)
                    .port(serverConfig.endpointsPort())
                    .basePath(DelegatedErrorContractEndpoint.MATCHING_PATH)
                    .header(DelegatedErrorContractEndpoint.DESIRED_ERROR_MESSAGE_HEADER_KEY, desiredErrorMessage)
                    .log().all()
                .when()
                    .get()
                .then()
                    .log().headers()
                    .extract();

        assertThat(response.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR.code());

        DelegatedErrorContract errorContract = mapper.readValue(response.asString(), DelegatedErrorContract.class);
        assertThat(errorContract.message).isEqualTo(desiredErrorMessage);

        assertThat(response.header("error_uid")).startsWith(EXPECTED_CUSTOM_ERROR_UID_RESPONSE_HEADER_PREFIX);
    }

    @Test
    public void should_return_string_payload_when_ErrorResponseBody_bodyToSerialize_is_string() throws IOException {
        String payload = "<html><body prop=\"value\" anotherprop=\"value2\">Internal Server Error</body></html>";

        ExtractableResponse response =
                given()
                    .baseUri(BASE_URI)
                    .port(serverConfig.endpointsPort())
                    .basePath(StringErrorContractEndpoint.MATCHING_PATH)
                    .header(StringErrorContractEndpoint.DESIRED_ERROR_PAYLOAD_HEADER_KEY, payload)
                    .log().all()
                .when()
                    .get()
                .then()
                    .log().headers()
                    .extract();

        assertThat(response.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR.code());
        assertThat(response.response().body().print()).isEqualTo(payload);
    }

    @Test
    public void should_return_default_payload_when_default_error_handling_occurs() throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        ExtractableResponse response =
            given()
                .baseUri(BASE_URI)
                .port(serverConfig.endpointsPort())
                .basePath(DefaultErrorContractEndpoint.MATCHING_PATH)
                .log().all()
            .when()
                .get()
            .then()
                .log().headers()
                .extract();

        verifyErrorReceived(response.asString(), INTERNAL_SERVER_ERROR.code(), GENERIC_SERVICE_ERROR);
        DefaultErrorContractDTO errorContract = mapper.readValue(response.asString(), DefaultErrorContractDTO.class);
        String errorUidResponseHeader = response.header("error_uid");
        assertThat(errorUidResponseHeader).isNotEmpty();
        assertThat(errorContract.error_id).isEqualTo(errorUidResponseHeader);
        UUID errorUidAsUuid = UUID.fromString(errorUidResponseHeader);
        assertThat(errorUidAsUuid).isNotNull();
    }

    private static final String EXPECTED_CUSTOM_ERROR_UID_RESPONSE_HEADER_PREFIX =
        "error-uid-prefix-" + UUID.randomUUID().toString() + "-";

    static class BlankPayloadErrorContractEndpoint extends StandardEndpoint<Void, Void> {

        static final String MATCHING_PATH = "/blankPayloadErrorContractEndpoint";

        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<Void> request,
                                                             Executor longRunningTaskExecutor,
                                                             ChannelHandlerContext ctx) {
            throw new BlankPayloadErrorContractException();
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }

    static class DelegatedErrorContractEndpoint extends StandardEndpoint<Void, Void> {

        static final String MATCHING_PATH = "/delegatedErrorContractEndpoint";
        static final String DESIRED_ERROR_MESSAGE_HEADER_KEY = "desired-error-message";

        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<Void> request,
                                                             Executor longRunningTaskExecutor,
                                                             ChannelHandlerContext ctx) {
            throw new DelegatedErrorContractException(request.getHeaders().get(DESIRED_ERROR_MESSAGE_HEADER_KEY));
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }

    static class StringErrorContractEndpoint extends StandardEndpoint<Void, Void> {

        static final String MATCHING_PATH = "/stringErrorContractEndpoint";
        static final String DESIRED_ERROR_PAYLOAD_HEADER_KEY = "desired-error-payload";

        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<Void> request,
                                                            Executor longRunningTaskExecutor,
                                                            ChannelHandlerContext ctx) {
            throw new StringErrorContractException(request.getHeaders().get(DESIRED_ERROR_PAYLOAD_HEADER_KEY));
        }

        @Override
        public Matcher requestMatcher() { return Matcher.match(MATCHING_PATH, HttpMethod.GET); }
    }

    static class DefaultErrorContractEndpoint extends StandardEndpoint<Void, Void> {

        static final String MATCHING_PATH = "/defaultErrorContractEndpoint";

        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<Void> request,
                                                             Executor longRunningTaskExecutor,
                                                             ChannelHandlerContext ctx) {
            throw new RuntimeException("kaboom");
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }

    private static class DelegatedErrorContractException extends RuntimeException {
        String message;

        DelegatedErrorContractException(final String message) {
            this.message = message;
        }
    }

    private static class StringErrorContractException extends RuntimeException {
        String message;

        StringErrorContractException(final String message) {
            this.message = message;
        }
    }

    private static class BlankPayloadErrorContractException extends RuntimeException {}

    private static class DelegatedErrorContract {
        public final String message;

        // Needed for jackson deserialization
        @SuppressWarnings("unused")
        private DelegatedErrorContract() {
            this(null);
        }

        DelegatedErrorContract(final String message) {
            this.message = message;
        }
    }

    public static class ErrorResponsePayloadTestingServerConfig implements ServerConfig {
        private final Collection<Endpoint<?>> endpoints = Arrays.asList(
            new DelegatedErrorContractEndpoint(),
            new StringErrorContractEndpoint(),
            new BlankPayloadErrorContractEndpoint(),
            new DefaultErrorContractEndpoint()
        );
        private final ProjectApiErrors projectApiErrors = ProjectApiErrorsForTesting.withProjectSpecificData(
            null, ProjectSpecificErrorCodeRange.ALLOW_ALL_ERROR_CODES
        );
        private final ApiExceptionHandlerUtils exceptionHandlerUtils = ApiExceptionHandlerUtils.DEFAULT_IMPL;
        private final int port;

        ErrorResponsePayloadTestingServerConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
        }

        @Override
        public RiposteUnhandledErrorHandler riposteUnhandledErrorHandler() {
            return new CustomRiposteUnhandledErrorHandler(projectApiErrors, exceptionHandlerUtils);
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

    static class CustomRiposteUnhandledErrorHandler extends RiposteUnhandledExceptionHandler {
        CustomRiposteUnhandledErrorHandler(
            ProjectApiErrors projectApiErrors, ApiExceptionHandlerUtils utils) {
            super(projectApiErrors, utils);
        }

        @Override
        protected ErrorResponseBody prepareFrameworkRepresentation(
            final DefaultErrorContractDTO errorContractDTO,
            final int httpStatusCode, final Collection<ApiError> rawFilteredApiErrors,
            final Throwable originalException, final RequestInfoForLogging request
        ) {
            Throwable cause = originalException.getCause();

            if (cause instanceof BlankPayloadErrorContractException) {
                return handleBlankErrorContract();
            }

            if (cause instanceof DelegatedErrorContractException) {
                return handleDelegatedErrorContract((DelegatedErrorContractException) originalException.getCause());
            }

            if (cause instanceof StringErrorContractException) {
                return handleStringErrorContract((StringErrorContractException) originalException.getCause());
            }

            return super.prepareFrameworkRepresentation(errorContractDTO, httpStatusCode, rawFilteredApiErrors,
                                                        originalException, request);
        }

        ErrorResponseBody handleBlankErrorContract() {
            String errorId = EXPECTED_CUSTOM_ERROR_UID_RESPONSE_HEADER_PREFIX + UUID.randomUUID().toString();

            return new ErrorResponseBody() {
                @Override
                public String errorId() {
                    return errorId;
                }

                @Override
                public Object bodyToSerialize() {
                    return null;
                }
            };
        }

        ErrorResponseBody handleDelegatedErrorContract(DelegatedErrorContractException originalException) {
            String errorId = EXPECTED_CUSTOM_ERROR_UID_RESPONSE_HEADER_PREFIX + UUID.randomUUID().toString();
            
            return new ErrorResponseBody() {
                @Override
                public String errorId() {
                    return errorId;
                }

                @Override
                public Object bodyToSerialize() {
                    return new DelegatedErrorContract(originalException.message);
                }
            };
        }

        ErrorResponseBody handleStringErrorContract(StringErrorContractException originalException) {
            String errorId = EXPECTED_CUSTOM_ERROR_UID_RESPONSE_HEADER_PREFIX + UUID.randomUUID().toString();

            return new ErrorResponseBody() {
                @Override
                public String errorId() { return errorId; }

                @Override
                public Object bodyToSerialize() { return originalException.message; }
            };
        }
    }

}
