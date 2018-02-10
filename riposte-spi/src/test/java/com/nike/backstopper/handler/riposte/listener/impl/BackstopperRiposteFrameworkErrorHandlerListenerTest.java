package com.nike.backstopper.handler.riposte.listener.impl;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.apierror.ApiErrorWithMetadata;
import com.nike.backstopper.apierror.SortedApiErrorSet;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.apierror.testutil.ProjectApiErrorsForTesting;
import com.nike.backstopper.handler.listener.ApiExceptionHandlerListenerResult;
import com.nike.fastbreak.exception.CircuitBreakerException;
import com.nike.fastbreak.exception.CircuitBreakerOpenException;
import com.nike.fastbreak.exception.CircuitBreakerTimeoutException;
import com.nike.internal.util.Pair;
import com.nike.riposte.server.error.exception.DownstreamChannelClosedUnexpectedlyException;
import com.nike.riposte.server.error.exception.DownstreamIdleChannelTimeoutException;
import com.nike.riposte.server.error.exception.Forbidden403Exception;
import com.nike.riposte.server.error.exception.HostnameResolutionException;
import com.nike.riposte.server.error.exception.IncompleteHttpCallTimeoutException;
import com.nike.riposte.server.error.exception.InvalidCharsetInContentTypeHeaderException;
import com.nike.riposte.server.error.exception.InvalidHttpRequestException;
import com.nike.riposte.server.error.exception.MethodNotAllowed405Exception;
import com.nike.riposte.server.error.exception.MissingRequiredContentException;
import com.nike.riposte.server.error.exception.MultipleMatchingEndpointsException;
import com.nike.riposte.server.error.exception.NativeIoExceptionWrapper;
import com.nike.riposte.server.error.exception.NonblockingEndpointCompletableFutureTimedOut;
import com.nike.riposte.server.error.exception.PathNotFound404Exception;
import com.nike.riposte.server.error.exception.PathParameterMatchingException;
import com.nike.riposte.server.error.exception.RequestContentDeserializationException;
import com.nike.riposte.server.error.exception.RequestTooBigException;
import com.nike.riposte.server.error.exception.TooManyOpenChannelsException;
import com.nike.riposte.server.error.exception.Unauthorized401Exception;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Unit test for {@link BackstopperRiposteFrameworkErrorHandlerListener}
 */
@RunWith(DataProviderRunner.class)
public class BackstopperRiposteFrameworkErrorHandlerListenerTest {

    private static final ProjectApiErrors testProjectApiErrors = ProjectApiErrorsForTesting.withProjectSpecificData(null, null);

    private BackstopperRiposteFrameworkErrorHandlerListener
        listener = new BackstopperRiposteFrameworkErrorHandlerListener(testProjectApiErrors);

    private void verifyExceptionHandled(Throwable ex, SortedApiErrorSet expectedErrors) {
        ApiExceptionHandlerListenerResult result = listener.shouldHandleException(ex);
        assertThat(result.shouldHandleResponse).isTrue();
        assertThat(result.errors).isEqualTo(expectedErrors);
    }

    private SortedApiErrorSet singletonError(ApiError error) {
        return new SortedApiErrorSet(Collections.singletonList(error));
    }

    @Test
    public void constructor_sets_projectApiErrors_to_passed_in_arg() {
        // given
        ProjectApiErrors projectErrorsMock = mock(ProjectApiErrors.class);
        ApiError temporaryError = new ApiErrorBase("temp_error_for_test", 42, "temporary error", 503);
        ApiError malformedReqError = new ApiErrorBase("malformed_error_for_test", 52, "malformed error", 400);
        doReturn(temporaryError).when(projectErrorsMock).getTemporaryServiceProblemApiError();
        doReturn(malformedReqError).when(projectErrorsMock).getMalformedRequestApiError();

        // when
        BackstopperRiposteFrameworkErrorHandlerListener
            impl = new BackstopperRiposteFrameworkErrorHandlerListener(projectErrorsMock);

        // then
        assertThat(impl.projectApiErrors).isSameAs(projectErrorsMock);
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_passed_null() {
        // when
        @SuppressWarnings("ConstantConditions")
        Throwable ex = catchThrowable(() -> new BackstopperRiposteFrameworkErrorHandlerListener(null));

        // then
        Assertions.assertThat(ex).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void shouldIgnoreUnhandledErrors() {
        ApiExceptionHandlerListenerResult result = listener.shouldHandleException(new Exception());
        assertThat(result.shouldHandleResponse).isFalse();
    }

    @Test
    public void should_handle_CircuitBreakerException() {
        verifyExceptionHandled(new CircuitBreakerOpenException("foo", "bar"),
                               singletonError(listener.CIRCUIT_BREAKER_OPEN_API_ERROR));
        verifyExceptionHandled(new CircuitBreakerTimeoutException("foo", "bar"),
                               singletonError(listener.CIRCUIT_BREAKER_TIMEOUT_API_ERROR));
        verifyExceptionHandled(new CircuitBreakerException("foo", "bar") {},
                               singletonError(listener.CIRCUIT_BREAKER_GENERIC_API_ERROR));
    }

    @Test
    public void shouldHandleNonblockingEndpointCompletableFutureTimedOut() {
        verifyExceptionHandled(new NonblockingEndpointCompletableFutureTimedOut(4242), singletonError(testProjectApiErrors.getTemporaryServiceProblemApiError()));
    }

    @Test
    public void shouldHandleAsyncDownstreamCallTimedOut() {
        verifyExceptionHandled(new DownstreamIdleChannelTimeoutException(4242, null), singletonError(testProjectApiErrors.getTemporaryServiceProblemApiError()));
    }

    @Test
    public void should_handle_DownstreamChannelClosedUnexpectedlyException() {
        verifyExceptionHandled(new DownstreamChannelClosedUnexpectedlyException(null), singletonError(testProjectApiErrors.getTemporaryServiceProblemApiError()));
    }

    private enum TooLongFrameExceptionScenario {
        NORMAL_TOO_LONG_HEADERS_ERROR(
            new TooLongFrameException("HTTP header is larger than 42 bytes."),
            42,
            l -> l.TOO_LONG_FRAME_HEADER_API_ERROR_BASE
        ),
        NORMAL_TOO_LONG_LINE_ERROR(
            new TooLongFrameException("An HTTP line is larger than 59 bytes."),
            59,
            l -> l.TOO_LONG_FRAME_LINE_API_ERROR_BASE
        ),
        NULL_EXCEPTION_MESSAGE(
            new TooLongFrameException(),
            null,
            l -> l.TOO_LONG_FRAME_LINE_API_ERROR_BASE
        ),
        EXCEPTION_MESSAGE_DOES_NOT_END_IN_BYTES(
            new TooLongFrameException("123 baz"),
            null,
            l -> l.TOO_LONG_FRAME_LINE_API_ERROR_BASE
        ),
        HEADER_EXCEPTION_MESSAGE_MAX_SIZE_NOT_AN_INTEGER(
            new TooLongFrameException("HTTP header is larger than foo bytes."),
            null,
            l -> l.TOO_LONG_FRAME_HEADER_API_ERROR_BASE
        ),
        LINE_EXCEPTION_MESSAGE_MAX_SIZE_NOT_AN_INTEGER(
            new TooLongFrameException("An HTTP line is larger than foo bytes."),
            null,
            l -> l.TOO_LONG_FRAME_LINE_API_ERROR_BASE
        );

        public final TooLongFrameException exception;
        private final Integer expectedMaxSizeMetadataValue;
        private final Function<BackstopperRiposteFrameworkErrorHandlerListener, ApiError> baseErrorRetriever;

        TooLongFrameExceptionScenario(TooLongFrameException exception, Integer expectedMaxSizeMetadataValue,
                                      Function<BackstopperRiposteFrameworkErrorHandlerListener, ApiError> baseErrorRetriever) {
            this.exception = exception;
            this.expectedMaxSizeMetadataValue = expectedMaxSizeMetadataValue;
            this.baseErrorRetriever = baseErrorRetriever;
        }

        public ApiError expectedApiError(BackstopperRiposteFrameworkErrorHandlerListener listener) {
            ApiError base = baseErrorRetriever.apply(listener);
            Map<String, Object> maxSizeMetadata = new HashMap<>();
            if (expectedMaxSizeMetadataValue != null) {
                maxSizeMetadata.put("max_length_allowed", expectedMaxSizeMetadataValue);
            }
            return new ApiErrorWithMetadata(base, maxSizeMetadata);
        }
    }

    @DataProvider(value = {
        "NORMAL_TOO_LONG_HEADERS_ERROR                      |   true",
        "NORMAL_TOO_LONG_HEADERS_ERROR                      |   false",
        "NORMAL_TOO_LONG_LINE_ERROR                         |   true",
        "NORMAL_TOO_LONG_LINE_ERROR                         |   false",
        "NULL_EXCEPTION_MESSAGE                             |   true",
        "NULL_EXCEPTION_MESSAGE                             |   false",
        "EXCEPTION_MESSAGE_DOES_NOT_END_IN_BYTES            |   true",
        "EXCEPTION_MESSAGE_DOES_NOT_END_IN_BYTES            |   false",
        "HEADER_EXCEPTION_MESSAGE_MAX_SIZE_NOT_AN_INTEGER   |   true",
        "HEADER_EXCEPTION_MESSAGE_MAX_SIZE_NOT_AN_INTEGER   |   false",
        "LINE_EXCEPTION_MESSAGE_MAX_SIZE_NOT_AN_INTEGER     |   true",
        "LINE_EXCEPTION_MESSAGE_MAX_SIZE_NOT_AN_INTEGER     |   false"
    }, splitBy = "\\|")
    @Test
    public void should_handle_TooLongFrameException_as_expected(
        TooLongFrameExceptionScenario scenario, boolean wrappedInInvalidHttpRequestException
    ) {
        Throwable ex = (wrappedInInvalidHttpRequestException)
                       ? new InvalidHttpRequestException("foo", scenario.exception)
                       : scenario.exception;
        verifyExceptionHandled(
            ex,
            singletonError(scenario.expectedApiError(listener))
        );
    }

    @Test
    public void shouldHandleRequestContentDeserializationException() {
        RequestInfo requestInfo = new RequestInfoImpl(null, HttpMethod.PATCH, null, null, null, null, null, null, null, false, true, false);
        verifyExceptionHandled(new RequestContentDeserializationException("intentional boom", null, requestInfo, new TypeReference<Object>() { }), singletonError(
                testProjectApiErrors.getMalformedRequestApiError()));
    }

    @Test
    public void shouldHandlePathNotFound404Exception() {
        verifyExceptionHandled(new PathNotFound404Exception("intentional boom"), singletonError(testProjectApiErrors.getNotFoundApiError()));
    }

    @Test
    public void shouldHandleMethodNotAllowed405Exception() {
        verifyExceptionHandled(new MethodNotAllowed405Exception("intentional boom", null, null), singletonError(testProjectApiErrors.getMethodNotAllowedApiError()));
    }

    @Test
    public void should_handle_Unauthorized401Exception() {
        verifyExceptionHandled(new Unauthorized401Exception("foo", "/bar", "blah"), singletonError(testProjectApiErrors.getUnauthorizedApiError()));
    }

    @Test
    public void should_handle_Forbidden403Exception() {
        verifyExceptionHandled(new Forbidden403Exception("foo", "/bar", "blah"), singletonError(testProjectApiErrors.getForbiddenApiError()));
    }

    @Test
    public void shouldHandleMultipleMatchingEndpointsException() {
        verifyExceptionHandled(new MultipleMatchingEndpointsException("intentional boom", Collections.emptyList(), null, null), singletonError(testProjectApiErrors.getGenericServiceError()));
    }

    @Test
    public void shouldHandlePathParameterMatchingException() {
        verifyExceptionHandled(new PathParameterMatchingException("intentional boom", null, null), singletonError(testProjectApiErrors.getGenericServiceError()));
    }

    @Test
    public void shouldHandleInvalidCharsetInContentTypeHeaderException() {
        verifyExceptionHandled(new InvalidCharsetInContentTypeHeaderException("intentional boom", null, null), singletonError(testProjectApiErrors.getUnsupportedMediaTypeApiError()));
    }

    @Test
    public void shouldHandleHostnameResolutionException() {
        verifyExceptionHandled(new HostnameResolutionException("foo", null), singletonError(testProjectApiErrors.getTemporaryServiceProblemApiError()));
    }

    @Test
    public void shouldHandleNativeIoExceptionWrapper() {
        verifyExceptionHandled(new NativeIoExceptionWrapper("foo", null), singletonError(testProjectApiErrors.getTemporaryServiceProblemApiError()));
    }

    @Test
    public void should_handle_TooManyOpenChannelsException() {
        verifyExceptionHandled(new TooManyOpenChannelsException(43, 42), singletonError(testProjectApiErrors.getTemporaryServiceProblemApiError()));
    }

    @Test
    public void shouldHandleErrorDataDecoderException() {
        verifyExceptionHandled(new ErrorDataDecoderException(), singletonError(testProjectApiErrors.getMalformedRequestApiError()));
    }

    @Test
    public void should_handle_IncompleteHttpCallTimeoutException() {
        verifyExceptionHandled(new IncompleteHttpCallTimeoutException(4242), singletonError(
            new ApiErrorWithMetadata(testProjectApiErrors.getMalformedRequestApiError(),
                                     Pair.of("cause", "Unfinished/invalid HTTP request"))
        ));
    }

    @Test
    public void circuit_breaker_exceptions_should_have_correct_names() {
        assertThat(listener.CIRCUIT_BREAKER_GENERIC_API_ERROR.getName()).isEqualTo("CIRCUIT_BREAKER");
        assertThat(listener.CIRCUIT_BREAKER_OPEN_API_ERROR.getName()).isEqualTo("CIRCUIT_BREAKER_OPEN");
        assertThat(listener.CIRCUIT_BREAKER_TIMEOUT_API_ERROR.getName()).isEqualTo("CIRCUIT_BREAKER_TIMEOUT");
    }

    @Test
    public void shouldHandleInvalidHttpRequestExceptionWithNullCause() {
        ApiExceptionHandlerListenerResult result = listener.shouldHandleException(new InvalidHttpRequestException("message", null));
        assertThat(result.shouldHandleResponse).isTrue();
        assertThat(result.errors).isEqualTo(singletonError(
            new ApiErrorWithMetadata(testProjectApiErrors.getMalformedRequestApiError(),
                                     Pair.of("cause", "Invalid HTTP request"))
        ));

        assertThat(result.extraDetailsForLogging.get(0).getLeft()).isEqualTo("exception_message");
        assertThat(result.extraDetailsForLogging.get(0).getRight()).isEqualTo("message");

        assertThat(result.extraDetailsForLogging.get(1).getLeft()).isEqualTo("exception_cause_details");
        assertThat(result.extraDetailsForLogging.get(1).getRight()).isEqualTo("NO_CAUSE");
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void shouldHandleInvalidHttpRequestExceptionWithNonNullCause(boolean useTooLongFrameExceptionAsCause) {
        // given
        Throwable cause = (useTooLongFrameExceptionAsCause)
                          ? new TooLongFrameException("TooLongFrameException occurred")
                          : new RuntimeException("runtime exception");
        String expectedCauseMetadataMessage = (useTooLongFrameExceptionAsCause)
                                              ? listener.TOO_LONG_FRAME_LINE_METADATA_MESSAGE
                                              : "Invalid HTTP request";
        String outerExceptionMessage = "message - " + UUID.randomUUID().toString();

        ApiError expectedApiErrorBase = (useTooLongFrameExceptionAsCause)
                                        ? listener.TOO_LONG_FRAME_LINE_API_ERROR_BASE
                                        : testProjectApiErrors.getMalformedRequestApiError();

        // when
        ApiExceptionHandlerListenerResult result = listener.shouldHandleException(
            new InvalidHttpRequestException(outerExceptionMessage, cause)
        );

        // then
        assertThat(result.shouldHandleResponse).isTrue();
        assertThat(result.errors).isEqualTo(singletonError(
            new ApiErrorWithMetadata(expectedApiErrorBase,
                                     Pair.of("cause", expectedCauseMetadataMessage))
        ));

        assertThat(result.extraDetailsForLogging.get(0).getLeft()).isEqualTo("exception_message");
        assertThat(result.extraDetailsForLogging.get(0).getRight()).isEqualTo(outerExceptionMessage);

        assertThat(result.extraDetailsForLogging.get(1).getLeft()).isEqualTo("exception_cause_details");
        assertThat(result.extraDetailsForLogging.get(1).getRight()).isEqualTo(cause.toString());
    }

    @Test
    public void should_handle_RequestTooBigException() {
        // given
        String exMsg = UUID.randomUUID().toString();
        RequestTooBigException ex = new RequestTooBigException(exMsg);

        // when
        ApiExceptionHandlerListenerResult result = listener.shouldHandleException(ex);

        // then
        assertThat(result.shouldHandleResponse).isTrue();
        assertThat(result.errors).isEqualTo(singletonError(
            new ApiErrorWithMetadata(testProjectApiErrors.getMalformedRequestApiError(),
                                     Pair.of("cause", "The request exceeded the maximum payload size allowed"))
        ));

        assertThat(result.extraDetailsForLogging.get(0).getLeft()).isEqualTo("exception_message");
        assertThat(result.extraDetailsForLogging.get(0).getRight()).isEqualTo(exMsg);

        assertThat(result.extraDetailsForLogging.get(1).getLeft()).isEqualTo("decoder_exception");
        assertThat(result.extraDetailsForLogging.get(1).getRight()).isEqualTo("true");
    }

    @Test
    public void should_handle_RequestMissingContentException() {
        // given
        MissingRequiredContentException ex = new MissingRequiredContentException("/path", "POST", "TestEndpoint");

        // when
        ApiExceptionHandlerListenerResult result = listener.shouldHandleException(ex);

        // then
        assertThat(result.shouldHandleResponse).isTrue();
        assertThat(result.errors).isEqualTo(singletonError(testProjectApiErrors.getMissingExpectedContentApiError()));

        assertThat(result.extraDetailsForLogging.get(0).getLeft()).isEqualTo("incoming_request_path");
        assertThat(result.extraDetailsForLogging.get(0).getRight()).isEqualTo("/path");

        assertThat(result.extraDetailsForLogging.get(1).getLeft()).isEqualTo("incoming_request_method");
        assertThat(result.extraDetailsForLogging.get(1).getRight()).isEqualTo("POST");

        assertThat(result.extraDetailsForLogging.get(2).getLeft()).isEqualTo("endpoint_class_name");
        assertThat(result.extraDetailsForLogging.get(2).getRight()).startsWith("TestEndpoint");
    }

}