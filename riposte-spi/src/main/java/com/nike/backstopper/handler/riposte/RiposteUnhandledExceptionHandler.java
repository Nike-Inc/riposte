package com.nike.backstopper.handler.riposte;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.handler.ApiExceptionHandlerUtils;
import com.nike.backstopper.handler.RequestInfoForLogging;
import com.nike.backstopper.handler.UnhandledExceptionHandlerBase;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.backstopper.model.riposte.ErrorResponseBodyImpl;
import com.nike.backstopper.model.riposte.ErrorResponseInfoImpl;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.error.handler.ErrorResponseInfo;
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler;
import com.nike.riposte.server.http.RequestInfo;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Adapter that lets {@link UnhandledExceptionHandlerBase} act as a {@link RiposteUnhandledErrorHandler}.
 *
 * @author Nic Munroe
 */
@Singleton
@SuppressWarnings("WeakerAccess")
public class RiposteUnhandledExceptionHandler extends UnhandledExceptionHandlerBase<ErrorResponseBody>
                                              implements RiposteUnhandledErrorHandler {

    protected final Set<ApiError> singletonGenericServiceError;
    protected final int genericServiceErrorHttpStatusCode;

    @Inject
    public RiposteUnhandledExceptionHandler(
        @NotNull ProjectApiErrors projectApiErrors,
        @NotNull ApiExceptionHandlerUtils utils
    ) {
        super(projectApiErrors, utils);
        singletonGenericServiceError = Collections.singleton(projectApiErrors.getGenericServiceError());
        genericServiceErrorHttpStatusCode = projectApiErrors.getGenericServiceError().getHttpStatusCode();
    }

    @Override
    protected @NotNull ErrorResponseBody prepareFrameworkRepresentation(
        @NotNull DefaultErrorContractDTO errorContractDTO,
        int httpStatusCode,
        @NotNull Collection<ApiError> rawFilteredApiErrors,
        @NotNull Throwable originalException,
        @NotNull RequestInfoForLogging request
    ) {
        return new ErrorResponseBodyImpl(errorContractDTO);
    }

    @Override
    protected com.nike.backstopper.handler.ErrorResponseInfo<ErrorResponseBody> generateLastDitchFallbackErrorResponseInfo(
        @NotNull Throwable ex,
        @NotNull RequestInfoForLogging request,
        @NotNull String errorUid,
        @NotNull Map<String, List<String>> headersForResponseWithErrorUid
    ) {
        return new com.nike.backstopper.handler.ErrorResponseInfo<>(
            genericServiceErrorHttpStatusCode,
            new ErrorResponseBodyImpl(errorUid, singletonGenericServiceError),
            headersForResponseWithErrorUid
        );
    }

    @Override
    public @NotNull ErrorResponseInfo handleError(@NotNull Throwable error, @NotNull RequestInfo<?> requestInfo) {

        com.nike.backstopper.handler.ErrorResponseInfo<ErrorResponseBody> backstopperErrorResponseInfo =
            handleException(error, new RequestInfoForLoggingRiposteAdapter(requestInfo));

        return new ErrorResponseInfoImpl(backstopperErrorResponseInfo);
    }

}
