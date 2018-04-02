package com.nike.riposte.server.componenttest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectSpecificErrorCodeRange;
import com.nike.backstopper.apierror.sample.SampleProjectApiErrorsBase;
import com.nike.backstopper.handler.ApiExceptionHandlerUtils;
import com.nike.backstopper.handler.RequestInfoForLogging;
import com.nike.backstopper.handler.riposte.RiposteApiExceptionHandler;
import com.nike.backstopper.handler.riposte.RiposteUnhandledExceptionHandler;
import com.nike.backstopper.handler.riposte.config.BackstopperRiposteConfigHelper;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.error.handler.RiposteErrorHandler;
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.restassured.response.ExtractableResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.nike.riposte.server.componenttest.VerifyRequestSizeValidationComponentTest.RequestSizeValidationConfig.GLOBAL_MAX_REQUEST_SIZE;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class VerifyErrorResponsePayloadHandlingComponentTest {
    private static final String DESIRED_ERROR_MESSAGE_HEADER_KEY = "desired-error-message";

    private static final String BASE_URI = "http://127.0.0.1";
    private static Server server;
    private static ServerConfig serverConfig;
    private static ObjectMapper objectMapper;
    private int incompleteCallTimeoutMillis = 2000;

    @BeforeClass
    public static void setUpClass() throws Exception {
        objectMapper = new ObjectMapper();
        serverConfig = new RequestSizeValidationConfig();
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void should_return_blank_payload_when_ErrorResponseBody_bodyToSerialize_is_null() throws IOException {
        ExtractableResponse response =
                given()
                        .baseUri(BASE_URI)
                        .port(serverConfig.endpointsPort())
                        .basePath(ErrorEndpoint.MATCHING_PATH)
                        .log().all()
                        .when()
                        .get()
                        .then()
                        .log().headers()
                        .extract();

        assertThat(response.statusCode()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
        assertThat(response.asString()).isEmpty();
        assertThat(response.header("error_uid")).isNotEmpty();
    }

    @Test
    public void should_return_delegate_payload_when_ErrorResponseBody_bodyToSerialize_is_delegate() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String uuid = UUID.randomUUID().toString();

        ExtractableResponse response =
                given()
                        .baseUri(BASE_URI)
                        .port(serverConfig.endpointsPort())
                        .basePath(ErrorEndpoint.MATCHING_PATH)
                        .header(DESIRED_ERROR_MESSAGE_HEADER_KEY, uuid)
                        .log().all()
                        .when()
                        .get()
                        .then()
                        .log().headers()
                        .extract();

        assertThat(response.statusCode()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR.code());

        ErrorContract errorContract = mapper.readValue(response.asString(), ErrorContract.class);
        assertThat(errorContract.message).isEqualTo(uuid);

        assertThat(response.header("error_uid")).isNotEmpty();
    }

    private static class ErrorEndpoint extends StandardEndpoint<Void, Void> {

        public static final String MATCHING_PATH = "/errorEndpoint";

        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            throw new TestException(request.getHeaders().get(DESIRED_ERROR_MESSAGE_HEADER_KEY));
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }

    private static class TestException extends RuntimeException {
        String message;

        public TestException(final String message) {
            this.message = message;
        }
    }

    private static class ErrorContract {
        public final String message;
        public ErrorContract() {
            this(null);
        }

        public ErrorContract(final String message) {
            this.message = message;
        }
    }

    public static class RequestSizeValidationConfig implements ServerConfig {
        private final Collection<Endpoint<?>> endpoints = Arrays.asList(new ErrorEndpoint());
        private final ProjectApiErrors apiErrors = new SampleProjectApiErrorsBase() {
            @Override
            protected List<ApiError> getProjectSpecificApiErrors() {
                return null;
            }

            @Override
            protected ProjectSpecificErrorCodeRange getProjectSpecificErrorCodeRange() {
                return ProjectSpecificErrorCodeRange.ALLOW_ALL_ERROR_CODES;
            }
        };
        private final ApiExceptionHandlerUtils exceptionHandlerUtils = ApiExceptionHandlerUtils.DEFAULT_IMPL;
        private final int port;

        public RequestSizeValidationConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
        }

        @Override
        public RiposteUnhandledErrorHandler riposteUnhandledErrorHandler() {
            return new RiposteUnhandledExceptionHandler(apiErrors, exceptionHandlerUtils) {
                @Override
                protected ErrorResponseBody prepareFrameworkRepresentation(
                        final DefaultErrorContractDTO errorContractDTO,
                        final int httpStatusCode, final Collection<ApiError> rawFilteredApiErrors,
                        final Throwable originalException, final RequestInfoForLogging request) {
                    if (originalException.getCause() instanceof TestException) {
                        return handleCustomErrorContract((TestException) originalException.getCause());
                    }
                    return super.prepareFrameworkRepresentation(errorContractDTO, httpStatusCode, rawFilteredApiErrors,
                            originalException, request);
                }
            };
        }

        @Override
        public RiposteErrorHandler riposteErrorHandler() {
            return new RiposteApiExceptionHandler(apiErrors,
                    BackstopperRiposteConfigHelper.defaultHandlerListeners(apiErrors, exceptionHandlerUtils),
                    exceptionHandlerUtils) {
                @Override
                protected ErrorResponseBody prepareFrameworkRepresentation(final DefaultErrorContractDTO errorContractDTO, final int httpStatusCode, final Collection<ApiError> rawFilteredApiErrors, final Throwable originalException, final RequestInfoForLogging request) {
                    if (originalException.getCause() instanceof TestException) {
                        return handleCustomErrorContract((TestException) originalException.getCause());
                    }
                    return super.prepareFrameworkRepresentation(errorContractDTO, httpStatusCode, rawFilteredApiErrors, originalException, request);
                }
            };
        }

        public ErrorResponseBody handleCustomErrorContract(TestException originalException) {

            String errorId = UUID.randomUUID().toString();
            String message = originalException.message;
            return new ErrorResponseBody() {
                @Override
                public String errorId() {
                    return errorId;
                }

                @Override
                public Object bodyToSerialize() {
                    if (message == null) {
                        return null;
                    }
                    return new ErrorContract(message);
                }
            };
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

}
