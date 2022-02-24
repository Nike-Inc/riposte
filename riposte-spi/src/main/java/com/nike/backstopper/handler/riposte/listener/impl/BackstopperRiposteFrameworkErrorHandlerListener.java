package com.nike.backstopper.handler.riposte.listener.impl;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.apierror.ApiErrorWithMetadata;
import com.nike.backstopper.apierror.SortedApiErrorSet;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.handler.ApiExceptionHandlerUtils;
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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;

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

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public final @NotNull ApiError CIRCUIT_BREAKER_GENERIC_API_ERROR;
    public final @NotNull ApiError CIRCUIT_BREAKER_OPEN_API_ERROR;
    public final @NotNull ApiError CIRCUIT_BREAKER_TIMEOUT_API_ERROR;

    protected final String TOO_LONG_FRAME_LINE_METADATA_MESSAGE =
        "The request contained a HTTP line that was longer than the maximum allowed";
    protected final String TOO_LONG_FRAME_HEADER_METADATA_MESSAGE =
        "The combined size of the request's HTTP headers was more than the maximum allowed";

    protected final @NotNull ApiError TOO_LONG_FRAME_LINE_API_ERROR_BASE;
    protected final @NotNull ApiError TOO_LONG_FRAME_HEADER_API_ERROR_BASE;

    protected final @NotNull ProjectApiErrors projectApiErrors;

    @Inject
    public BackstopperRiposteFrameworkErrorHandlerListener(@NotNull ProjectApiErrors projectApiErrors) {
        //noinspection ConstantConditions
        if (projectApiErrors == null)
            throw new IllegalArgumentException("ProjectApiErrors cannot be null");

        this.projectApiErrors = projectApiErrors;

        CIRCUIT_BREAKER_GENERIC_API_ERROR =
            new ApiErrorBase(projectApiErrors.getTemporaryServiceProblemApiError(), "CIRCUIT_BREAKER");
        CIRCUIT_BREAKER_OPEN_API_ERROR =
            new ApiErrorBase(projectApiErrors.getTemporaryServiceProblemApiError(), "CIRCUIT_BREAKER_OPEN");
        CIRCUIT_BREAKER_TIMEOUT_API_ERROR =
            new ApiErrorBase(projectApiErrors.getTemporaryServiceProblemApiError(), "CIRCUIT_BREAKER_TIMEOUT");

        ApiError malformedReqError = projectApiErrors.getMalformedRequestApiError();
        // Too long line can keep generic malformed request ApiError error code, message, and HTTP status code (400),
        //      but we'll give it a new name for the logs and some cause metadata so the caller knows what went wrong.
        TOO_LONG_FRAME_LINE_API_ERROR_BASE = new ApiErrorWithMetadata(
            new ApiErrorBase(malformedReqError, "TOO_LONG_HTTP_LINE"),
            Pair.of("cause", TOO_LONG_FRAME_LINE_METADATA_MESSAGE)
        );
        // Too long headers should keep the error code and message of malformed request ApiError, but use 431 HTTP
        //      status code (see https://tools.ietf.org/html/rfc6585#page-4), and we'll give it a new name for the logs
        //      and some cause metadata so the caller knows what went wrong.
        TOO_LONG_FRAME_HEADER_API_ERROR_BASE = new ApiErrorWithMetadata(
            new ApiErrorBase(
                "TOO_LONG_HEADERS", malformedReqError.getErrorCode(), malformedReqError.getMessage(),
                431, malformedReqError.getMetadata()
            ),
            Pair.of("cause", TOO_LONG_FRAME_HEADER_METADATA_MESSAGE)
        );
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
            ApiError errorToUse = (ex instanceof TooLongFrameException)
                                  ? generateTooLongFrameApiError((TooLongFrameException)ex)
                                  : projectApiErrors.getMalformedRequestApiError();
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(errorToUse),
                withBaseExceptionMessage(
                    ex,
                    Pair.of("decoder_exception", "true")
                )
            );
        }

        if (ex instanceof RequestTooBigException) {
            // TODO: RequestTooBigException should result in a 413 Payload Too Large error instead of generic 400 malformed request.
            //       For now, we can at least let the caller know why it failed via error metadata.
            ApiError errorToUse = new ApiErrorWithMetadata(
                projectApiErrors.getMalformedRequestApiError(),
                Pair.of("cause", "The request exceeded the maximum payload size allowed")
            );
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(errorToUse),
                withBaseExceptionMessage(
                    ex,
                    Pair.of("decoder_exception", "true")
                )
            );
        }

        if (ex instanceof HostnameResolutionException) {
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getTemporaryServiceProblemApiError()),
                withBaseExceptionMessage(ex)
            );
        }

        if (ex instanceof NativeIoExceptionWrapper) {
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getTemporaryServiceProblemApiError()),
                singletonList(causeDetailsForLogs(ex))
            );
        }

        if (ex instanceof RequestContentDeserializationException) {
            RequestContentDeserializationException theEx = (RequestContentDeserializationException) ex;
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getMalformedRequestApiError()),
                Arrays.asList(
                    Pair.of("method", theEx.httpMethod),
                    Pair.of("request_path", theEx.requestPath),
                    Pair.of("desired_object_type", theEx.desiredObjectType.getType().toString()),
                    causeDetailsForLogs(ex)
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
            List<Pair<String, String>> extraDetails = withBaseExceptionMessage(
                ex,
                Pair.of("incoming_request_path", theEx.requestPath)
            );
            extraDetails.addAll((theEx).extraDetailsForLogging);
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getUnauthorizedApiError()),
                extraDetails
            );
        }
        
        if (ex instanceof Forbidden403Exception) {
            Forbidden403Exception theEx = (Forbidden403Exception) ex;
            List<Pair<String, String>> extraDetails = withBaseExceptionMessage(
                ex,
                Pair.of("incoming_request_path", theEx.requestPath)
            );
            extraDetails.addAll((theEx).extraDetailsForLogging);
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(projectApiErrors.getForbiddenApiError()),
                extraDetails
            );
        }

        if (ex instanceof MissingRequiredContentException) {
            MissingRequiredContentException theEx = (MissingRequiredContentException) ex;
            return ApiExceptionHandlerListenerResult.handleResponse(
                    singletonError(projectApiErrors.getMissingExpectedContentApiError()),
                    Arrays.asList(Pair.of("incoming_request_path", theEx.path),
                            Pair.of("incoming_request_method", theEx.method),
                            Pair.of("endpoint_class_name", theEx.endpointClassName)
                    )
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
            return ApiExceptionHandlerListenerResult.handleResponse(
                singletonError(
                    new ApiErrorWithMetadata(projectApiErrors.getMalformedRequestApiError(),
                                             Pair.of("cause", "Unfinished/invalid HTTP request"))
                ),
                withBaseExceptionMessage(
                    ex,
                    Pair.of("incomplete_http_call_timeout_millis", String.valueOf(theEx.timeoutMillis))
                )
            );
        }

        if (ex instanceof InvalidHttpRequestException) {
            InvalidHttpRequestException theEx = (InvalidHttpRequestException)ex;
            Throwable cause = theEx.getCause();

            ApiError apiErrorToUse = (cause instanceof TooLongFrameException)
                                     ? generateTooLongFrameApiError((TooLongFrameException)cause)
                                     : new ApiErrorWithMetadata(projectApiErrors.getMalformedRequestApiError(),
                                                                Pair.of("cause", "Invalid HTTP request"));

            return ApiExceptionHandlerListenerResult.handleResponse(
                    singletonError(apiErrorToUse),
                    withBaseExceptionMessage(
                        ex,
                        causeDetailsForLogs(theEx)
                    )
            );
        }

        return ApiExceptionHandlerListenerResult.ignoreResponse();
    }

    @SafeVarargs
    protected final @NotNull List<Pair<String, String>> withBaseExceptionMessage(
        @NotNull Throwable ex, @Nullable Pair<String, String>... extraLogMessages
    ) {
        List<Pair<String, String>> logPairs = new ArrayList<>();
        ApiExceptionHandlerUtils.DEFAULT_IMPL.addBaseExceptionMessageToExtraDetailsForLogging(ex, logPairs);
        if (extraLogMessages != null) {
            logPairs.addAll(Arrays.asList(extraLogMessages));
        }
        return logPairs;
    }

    protected final @NotNull Pair<String, String> causeDetailsForLogs(@NotNull Throwable orig) {
        Throwable cause = orig.getCause();
        String causeDetails = (cause == null) ? "NO_CAUSE" : cause.toString();
        return Pair.of("exception_cause_details",
                       ApiExceptionHandlerUtils.DEFAULT_IMPL.quotesToApostrophes(causeDetails));
    }

    protected @NotNull ApiError generateTooLongFrameApiError(@NotNull TooLongFrameException ex) {
        String exMessage = String.valueOf(ex.getMessage());
        Integer tooLongFrameMaxSize = extractTooLongFrameMaxSizeFromExceptionMessage(ex);
        Map<String, Object> maxSizeMetadata = new HashMap<>();
        if (tooLongFrameMaxSize != null) {
            maxSizeMetadata.put("max_length_allowed", tooLongFrameMaxSize);
        }

        // If we detect it's complaining about HTTP header size then throw the ApiError that maps to a
        //      431 HTTP status code.
        if (exMessage.startsWith("HTTP header is larger than")) {
            return new ApiErrorWithMetadata(
                TOO_LONG_FRAME_HEADER_API_ERROR_BASE,
                maxSizeMetadata
            );
        }

        // It wasn't complaining about HTTP header size (or we didn't detect it for some reason). Return the
        //      generic "too long line" ApiError that maps to a 400.
        return new ApiErrorWithMetadata(
            TOO_LONG_FRAME_LINE_API_ERROR_BASE,
            maxSizeMetadata
        );
    }

    private @Nullable Integer extractTooLongFrameMaxSizeFromExceptionMessage(@NotNull TooLongFrameException ex) {
        String exMessage = ex.getMessage();
        
        if (exMessage == null || !exMessage.endsWith(" bytes.")) {
            return null;
        }

        try {
            String[] messageWords = exMessage.split(" ");
            String maxSizeWord = messageWords[messageWords.length - 2];

            return Integer.parseInt(maxSizeWord);
        }
        catch(Throwable t) {
            // Couldn't parse it for some reason.
            logger.debug("Unable to parse max size from TooLongFrameException. ex_message={}", exMessage, t);
            return null;
        }
    }

    protected @NotNull SortedApiErrorSet singletonError(@NotNull ApiError apiError) {
        return new SortedApiErrorSet(Collections.singleton(apiError));
    }

    protected @NotNull ApiError getApiErrorForCircuitBreakerException(@NotNull CircuitBreakerException cbe) {
        if (cbe instanceof CircuitBreakerOpenException)
            return CIRCUIT_BREAKER_OPEN_API_ERROR;
        else if (cbe instanceof CircuitBreakerTimeoutException)
            return CIRCUIT_BREAKER_TIMEOUT_API_ERROR;

        return CIRCUIT_BREAKER_GENERIC_API_ERROR;
    }

}
