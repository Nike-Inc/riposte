package com.nike.backstopper.handler.riposte.listener.impl;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.apierror.SortedApiErrorSet;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.apierror.testutil.ProjectApiErrorsForTesting;
import com.nike.backstopper.handler.listener.ApiExceptionHandlerListenerResult;
import com.nike.fastbreak.exception.CircuitBreakerException;
import com.nike.fastbreak.exception.CircuitBreakerOpenException;
import com.nike.fastbreak.exception.CircuitBreakerTimeoutException;
import com.nike.riposte.server.error.exception.DownstreamChannelClosedUnexpectedlyException;
import com.nike.riposte.server.error.exception.DownstreamIdleChannelTimeoutException;
import com.nike.riposte.server.error.exception.Forbidden403Exception;
import com.nike.riposte.server.error.exception.HostnameResolutionException;
import com.nike.riposte.server.error.exception.InvalidCharsetInContentTypeHeaderException;
import com.nike.riposte.server.error.exception.MethodNotAllowed405Exception;
import com.nike.riposte.server.error.exception.MultipleMatchingEndpointsException;
import com.nike.riposte.server.error.exception.NativeIoExceptionWrapper;
import com.nike.riposte.server.error.exception.NonblockingEndpointCompletableFutureTimedOut;
import com.nike.riposte.server.error.exception.PathNotFound404Exception;
import com.nike.riposte.server.error.exception.PathParameterMatchingException;
import com.nike.riposte.server.error.exception.RequestContentDeserializationException;
import com.nike.riposte.server.error.exception.TooManyOpenChannelsException;
import com.nike.riposte.server.error.exception.Unauthorized401Exception;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import com.fasterxml.jackson.core.type.TypeReference;

import org.assertj.core.api.Assertions;
import org.junit.Test;

import java.util.Collections;

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
        doReturn(temporaryError).when(projectErrorsMock).getTemporaryServiceProblemApiError();

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

    @Test
    public void shouldHandleTooLongFrameException() {
        verifyExceptionHandled(new TooLongFrameException(), singletonError(testProjectApiErrors.getMalformedRequestApiError()));
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
    public void circuit_breaker_exceptions_should_have_correct_names() {
        assertThat(listener.CIRCUIT_BREAKER_GENERIC_API_ERROR.getName()).isEqualTo("CIRCUIT_BREAKER");
        assertThat(listener.CIRCUIT_BREAKER_OPEN_API_ERROR.getName()).isEqualTo("CIRCUIT_BREAKER_OPEN");
        assertThat(listener.CIRCUIT_BREAKER_TIMEOUT_API_ERROR.getName()).isEqualTo("CIRCUIT_BREAKER_TIMEOUT");
    }

}