package com.nike.backstopper.handler.riposte.listener.impl;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.apierror.ApiErrorWithMetadata;
import com.nike.backstopper.apierror.SortedApiErrorSet;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.handler.listener.ApiExceptionHandlerListener;
import com.nike.backstopper.handler.listener.ApiExceptionHandlerListenerResult;
import com.nike.fastbreak.exception.CircuitBreakerException;
import com.nike.fastbreak.exception.CircuitBreakerOpenException;
import com.nike.fastbreak.exception.CircuitBreakerTimeoutException;
import com.nike.internal.util.Pair;
import com.nike.internal.util.StringUtils;
import com.nike.riposte.server.error.exception.DownstreamChannelClosedUnexpectedlyException;
import com.nike.riposte.server.error.exception.DownstreamIdleChannelTimeoutException;
import com.nike.riposte.server.error.exception.Forbidden403Exception;
import com.nike.riposte.server.error.exception.HostnameResolutionException;
import com.nike.riposte.server.error.exception.IncompleteHttpCallTimeoutException;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.netty.handler.codec.DecoderException;

import static java.util.Collections.singletonList;

/**
 * Backstopper {@link ApiExceptionHandlerListener} that knows how to deal with exceptions thrown by Netty and/or
 * Riposte.
 *
 * @author Nic Munroe
 */
@Singleton
@SuppressWarnings("WeakerAccess")
public class BackstopperRiposteFrameworkErrorHandlerListener implements ApiExceptionHandlerListener {

    public final ApiError CIRCUIT_BREAKER_GENERIC_API_ERROR;
    public final ApiError CIRCUIT_BREAKER_OPEN_API_ERROR;
    public final ApiError CIRCUIT_BREAKER_TIMEOUT_API_ERROR;

    protected final ProjectApiErrors projectApiErrors;

    @Inject
    public BackstopperRiposteFrameworkErrorHandlerListener(ProjectApiErrors projectApiErrors) {
        if (projectApiErrors == null)
            throw new IllegalArgumentException("ProjectApiErrors cannot be null");

        this.projectApiErrors = projectApiErrors;

        CIRCUIT_BREAKER_GENERIC_API_ERROR =
            new ApiErrorBase(projectApiErrors.getTemporaryServiceProblemApiError(), "CIRCUIT_BREAKER");
        CIRCUIT_BREAKER_OPEN_API_ERROR =
            new ApiErrorBase(projectApiErrors.getTemporaryServiceProblemApiError(), "CIRCUIT_BREAKER_OPEN");
        CIRCUIT_BREAKER_TIMEOUT_API_ERROR =
            new ApiErrorBase(projectApiErrors.getTemporaryServiceProblemApiError(), "CIRCUIT_BREAKER_TIMEOUT");
    }

    @Override
    public ApiExceptionHandlerListenerResult shouldHandleException(Throwable ex) {

        if (ex instanceof CircuitBreakerException) {
            CircuitBreakerException cbe = ((CircuitBreakerException) ex);

            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(getApiErrorForCircuitBreakerException(cbe)),
                singletonList(Pair.of("circuit_breaker_id", String.valueOf(cbe.circuitBreakerId)))
            );
        }

        if (ex instanceof NonblockingEndpointCompletableFutureTimedOut) {
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getTemporaryServiceProblemApiError()),
                singletonList(Pair.of(
                    "completable_future_timeout_value_millis",
                    String.valueOf(((NonblockingEndpointCompletableFutureTimedOut) ex).timeoutValueMillis)
                ))
            );
        }

        if (ex instanceof DownstreamIdleChannelTimeoutException) {
            DownstreamIdleChannelTimeoutException idleEx = (DownstreamIdleChannelTimeoutException) ex;
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getTemporaryServiceProblemApiError()),
                Arrays.asList(
                    Pair.of("async_downstream_call_timeout_value_millis", String.valueOf(idleEx.timeoutValueMillis)),
                    Pair.of("idle_channel_id", String.valueOf(idleEx.channelId))
                )
            );
        }

        if (ex instanceof DownstreamChannelClosedUnexpectedlyException) {
            DownstreamChannelClosedUnexpectedlyException dsClosedEx = (DownstreamChannelClosedUnexpectedlyException) ex;
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getTemporaryServiceProblemApiError()),
                singletonList(Pair.of("closed_channel_id", String.valueOf(dsClosedEx.channelId)))
            );
        }

        if (ex instanceof DecoderException) {
            // TODO: TooLongFrameException should result in a 413 Payload Too Large error instead of generic 400 malformed request.
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getMalformedRequestApiError()),
                Arrays.asList(
                    Pair.of("decoder_exception", "true"),
                    Pair.of("decoder_exception_message", ex.getMessage())
                )
            );
        }

        if (ex instanceof HostnameResolutionException) {
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getTemporaryServiceProblemApiError())
            );
        }

        if (ex instanceof NativeIoExceptionWrapper) {
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getTemporaryServiceProblemApiError())
            );
        }

        if (ex instanceof RequestContentDeserializationException) {
            RequestContentDeserializationException theEx = (RequestContentDeserializationException) ex;
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getMalformedRequestApiError()),
                Arrays.asList(
                    Pair.of("method", theEx.httpMethod),
                    Pair.of("request_path", theEx.requestPath),
                    Pair.of("desired_object_type", theEx.desiredObjectType.getType().toString())
                )
            );
        }

        if (ex instanceof PathNotFound404Exception) {
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getNotFoundApiError())
            );
        }

        if (ex instanceof MethodNotAllowed405Exception) {
            MethodNotAllowed405Exception theEx = (MethodNotAllowed405Exception) ex;
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getMethodNotAllowedApiError()),
                Arrays.asList(Pair.of("incoming_request_path", theEx.requestPath),
                              Pair.of("incoming_request_method", theEx.requestMethod))
            );
        }

        if (ex instanceof Unauthorized401Exception) {
            Unauthorized401Exception theEx = (Unauthorized401Exception) ex;
            List<Pair<String, String>> extraDetails = new ArrayList<>();
            extraDetails.add(Pair.of("message", ex.getMessage()));
            extraDetails.add(Pair.of("incoming_request_path", theEx.requestPath));
            extraDetails.add(Pair.of("authorization_header", theEx.authorizationHeader));
            extraDetails.addAll((theEx).extraDetailsForLogging);
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getUnauthorizedApiError()),
                extraDetails
            );
        }
        
        if (ex instanceof Forbidden403Exception) {
            Forbidden403Exception theEx = (Forbidden403Exception) ex;
            List<Pair<String, String>> extraDetails = new ArrayList<>();
            extraDetails.add(Pair.of("message", ex.getMessage()));
            extraDetails.add(Pair.of("incoming_request_path", theEx.requestPath));
            extraDetails.add(Pair.of("authorization_header", theEx.authorizationHeader));
            extraDetails.addAll((theEx).extraDetailsForLogging);
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getForbiddenApiError()),
                extraDetails
            );
        }

        if (ex instanceof MultipleMatchingEndpointsException) {
            MultipleMatchingEndpointsException theEx = (MultipleMatchingEndpointsException) ex;
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getGenericServiceError()),
                Arrays.asList(Pair.of("incoming_request_path", theEx.requestPath),
                              Pair.of("incoming_request_method", theEx.requestMethod),
                              Pair.of("matching_endpoints", StringUtils.join(theEx.matchingEndpointsDetails, ","))
                )
            );
        }

        if (ex instanceof PathParameterMatchingException) {
            PathParameterMatchingException theEx = (PathParameterMatchingException) ex;
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getGenericServiceError()),
                Arrays.asList(Pair.of("path_template", theEx.pathTemplate),
                              Pair.of("non_matching_uri_path", theEx.nonMatchingUriPath))
            );
        }

        if (ex instanceof InvalidCharsetInContentTypeHeaderException) {
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getUnsupportedMediaTypeApiError()),
                singletonList(Pair.of("invalid_content_type_header",
                                      ((InvalidCharsetInContentTypeHeaderException) ex).invalidContentTypeHeader)
                )
            );
        }

        if (ex instanceof TooManyOpenChannelsException) {
            TooManyOpenChannelsException theEx = (TooManyOpenChannelsException) ex;
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getTemporaryServiceProblemApiError()),
                Arrays.asList(
                    Pair.of("num_current_open_channels", String.valueOf(theEx.actualOpenChannelsCount)),
                    Pair.of("max_open_channels_limit", String.valueOf(theEx.maxOpenChannelsLimit))
                )
            );
        }

        if (ex instanceof IncompleteHttpCallTimeoutException) {
            IncompleteHttpCallTimeoutException theEx = (IncompleteHttpCallTimeoutException)ex;
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("cause", "Unfinished/invalid HTTP request");
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(
                    new ApiErrorWithMetadata(projectApiErrors.getMalformedRequestApiError(), metadata)
                ),
                Arrays.asList(Pair.of("incomplete_http_call_timeout_millis", String.valueOf(theEx.timeoutMillis)),
                              Pair.of("exception_message", theEx.getMessage()))
            );
        }

        return ApiExceptionHandlerListenerResult.ignoreResponse();
    }

    protected SortedApiErrorSet singletonError(ApiError apiError) {
        return new SortedApiErrorSet(Collections.singleton(apiError));
    }

    protected ApiError getApiErrorForCircuitBreakerException(CircuitBreakerException cbe) {
        if (cbe instanceof CircuitBreakerOpenException)
            return CIRCUIT_BREAKER_OPEN_API_ERROR;
        else if (cbe instanceof CircuitBreakerTimeoutException)
            return CIRCUIT_BREAKER_TIMEOUT_API_ERROR;

        return CIRCUIT_BREAKER_GENERIC_API_ERROR;
    }

}
