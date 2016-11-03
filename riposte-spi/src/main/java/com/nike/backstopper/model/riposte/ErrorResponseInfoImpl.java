package com.nike.backstopper.model.riposte;

import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.error.handler.ErrorResponseInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base implementation of {@link ErrorResponseInfo} for use with Riposte projects.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class ErrorResponseInfoImpl implements ErrorResponseInfo {

    /**
     * The response body content for the error that should be sent to the user.
     */
    public final ErrorResponseBody errorResponseBody;
    /**
     * The HTTP status code that should be returned in the response to the user. This is not automatically registered on
     * the framework's response - you should set this yourself on the response after you call an error handler.
     */
    public final int httpStatusCode;
    /**
     * Extra headers that were generated during error handling (e.g. error_uid) that should be added as headers to the
     * response sent to the user. These are not automatically registered on the framework's response - you should set
     * these yourself on the response after you call an error handler. This will never be null - it will be an empty map
     * if there are no headers to add.
     */
    public final Map<String, List<String>> headersToAddToResponse = new HashMap<>();

    public ErrorResponseInfoImpl(ErrorResponseBody errorResponseBody, int httpStatusCode,
                                 Map<String, List<String>> headersToAddToResponse) {
        this.errorResponseBody = errorResponseBody;
        this.httpStatusCode = httpStatusCode;
        if (headersToAddToResponse != null)
            this.headersToAddToResponse.putAll(headersToAddToResponse);
    }

    public ErrorResponseInfoImpl(
        com.nike.backstopper.handler.ErrorResponseInfo<ErrorResponseBody> backstopperErrorResponseInfo) {
        this(backstopperErrorResponseInfo.frameworkRepresentationObj, backstopperErrorResponseInfo.httpStatusCode,
             backstopperErrorResponseInfo.headersToAddToResponse);
    }

    @Override
    public ErrorResponseBody getErrorResponseBody() {
        return errorResponseBody;
    }

    @Override
    public int getErrorHttpStatusCode() {
        return httpStatusCode;
    }

    @Override
    public Map<String, List<String>> getExtraHeadersToAddToResponse() {
        return headersToAddToResponse;
    }
}
