package com.nike.riposte.server.handler;

import com.nike.riposte.server.error.exception.InvalidHttpRequestException;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;

/**
 * Contains utility methods for Riposte handlers. This is intentionally package-private - it is not intended for general
 * usage.
 *
 * @author Nic Munroe
 */
class RiposteHandlerInternalUtil {

    static RiposteHandlerInternalUtil DEFAULT_IMPL = new RiposteHandlerInternalUtil();

    RequestInfo<?> createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(
        HttpRequest httpRequest, HttpProcessingState state
    ) {
        // If the HttpProcessingState already has a RequestInfo then we should just use that.
        RequestInfo<?> requestInfo = state.getRequestInfo();
        if (requestInfo != null) {
            return requestInfo;
        }

        // No RequestInfo has been created yet. Check for an invalid Netty HttpRequest, and assuming it's good then
        //      generate a new RequestInfo from it and set the RequestInfo on our HttpProcessingState.
        throwExceptionIfNotSuccessfullyDecoded(httpRequest);
        requestInfo = new RequestInfoImpl<>(httpRequest);
        state.setRequestInfo(requestInfo);

        return requestInfo;
    }

    void throwExceptionIfNotSuccessfullyDecoded(HttpObject httpObject) {
        if (httpObject.decoderResult() != null && httpObject.decoderResult().isFailure()) {
            throw new InvalidHttpRequestException("Detected HttpObject that was not successfully decoded.",
                                                  httpObject.decoderResult().cause());
        }
    }

}
