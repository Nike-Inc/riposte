package com.nike.backstopper.handler.riposte;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.handler.ApiExceptionHandlerBase;
import com.nike.backstopper.handler.ApiExceptionHandlerUtils;
import com.nike.backstopper.handler.RequestInfoForLogging;
import com.nike.backstopper.handler.UnexpectedMajorExceptionHandlingError;
import com.nike.backstopper.handler.listener.ApiExceptionHandlerListener;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.backstopper.model.riposte.ErrorResponseBodyImpl;
import com.nike.backstopper.model.riposte.ErrorResponseInfoImpl;
import com.nike.riposte.server.error.exception.UnexpectedMajorErrorHandlingError;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.error.handler.ErrorResponseInfo;
import com.nike.riposte.server.error.handler.RiposteErrorHandler;
import com.nike.riposte.server.http.RequestInfo;

import java.util.Collection;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Adapter that lets {@link ApiExceptionHandlerBase} act as a {@link RiposteErrorHandler}.
 *
 * @author Nic Munroe
 */
@Singleton
public class RiposteApiExceptionHandler extends ApiExceptionHandlerBase<ErrorResponseBody>
    implements RiposteErrorHandler {

    @Inject
    public RiposteApiExceptionHandler(ProjectApiErrors projectApiErrors,
                                      List<ApiExceptionHandlerListener> apiExceptionHandlerListenerList,
                                      ApiExceptionHandlerUtils utils) {
        super(projectApiErrors, apiExceptionHandlerListenerList, utils);
    }

    @Override
    public ErrorResponseInfo maybeHandleError(Throwable error, RequestInfo<?> requestInfo)
        throws UnexpectedMajorErrorHandlingError {
        try {
            com.nike.backstopper.handler.ErrorResponseInfo<ErrorResponseBody> backstopperErrorResponseInfo =
                maybeHandleException(error, new RequestInfoForLoggingRiposteAdapter(requestInfo));

            if (backstopperErrorResponseInfo == null)
                return null;

            return new ErrorResponseInfoImpl(backstopperErrorResponseInfo);
        }
        catch (UnexpectedMajorExceptionHandlingError ex) {
            throw new UnexpectedMajorErrorHandlingError("Wrapping the actual cause", ex);
        }
    }

    @Override
    protected ErrorResponseBody prepareFrameworkRepresentation(DefaultErrorContractDTO errorContractDTO,
                                                               int httpStatusCode,
                                                               Collection<ApiError> rawFilteredApiErrors,
                                                               Throwable originalException,
                                                               RequestInfoForLogging request) {
        return new ErrorResponseBodyImpl(errorContractDTO);
    }

}
