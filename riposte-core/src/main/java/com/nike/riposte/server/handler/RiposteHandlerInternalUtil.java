package com.nike.riposte.server.handler;

import com.nike.riposte.server.error.exception.InvalidHttpRequestException;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.util.TracingState;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;

import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Contains utility methods for Riposte handlers. This is intentionally package-private - it is not intended for general
 * usage.
 *
 * @author Nic Munroe
 */
class RiposteHandlerInternalUtil {

    static RiposteHandlerInternalUtil DEFAULT_IMPL = new RiposteHandlerInternalUtil();

    private static final Logger logger = LoggerFactory.getLogger(RiposteHandlerInternalUtil.class);

    @NotNull RequestInfo<?> createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(
        @NotNull HttpRequest httpRequest, @NotNull HttpProcessingState state
    ) {
        // If the HttpProcessingState already has a RequestInfo then we should just use that.
        RequestInfo<?> requestInfo = state.getRequestInfo();
        if (requestInfo != null) {
            return requestInfo;
        }

        // No RequestInfo has been created yet. Check for an invalid Netty HttpRequest. If it's invalid, then default
        //      to RequestInfoImpl.dummyInstanceForUnknownRequests(). Otherwise, generate a new RequestInfo based on
        //      the Netty HttpRequest.
        //      In either case, set the RequestInfo on our HttpProcessingState.
        Throwable decoderFailureCause = getDecoderFailure(httpRequest);

        if (decoderFailureCause == null) {
            // No decoder failure (so far), so create a new RequestInfoImpl based on the Netty HttpRequest object.
            try {
                requestInfo = new RequestInfoImpl<>(httpRequest);
            }
            catch (Throwable t) {
                // Something couldn't be parsed properly, likely an improperly escaped URL. We'll force-set a
                //      DecoderFailure on the Netty HttpRequest.
                decoderFailureCause = t;
                httpRequest.setDecoderResult(DecoderResult.failure(t));
            }
        }

        // Check for DecoderFailure again, since the new RequestInfoImpl might have blown up.
        if (decoderFailureCause != null) {
            // A decoder failure occurred, which means the original request is not valid HTTP. So we'll create a dummy
            //      RequestInfo and log a warning.
            requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
            Throwable decoderFailureCauseForLogging = decoderFailureCause;

            // Use the current tracing state if there's one on this thread, otherwise try grabbing tracing state
            //      from the HttpProcessingState.
            TracingState tracingStateForLogMsg =
                (Tracer.getInstance().getCurrentSpan() == null)
                ? new TracingState(state.getDistributedTraceStack(), state.getLoggerMdcContextMap())
                : TracingState.getCurrentThreadTracingState();

            // Log a warning about this message explaining why we're using a dummy RequestInfo.
            runnableWithTracingAndMdc(
                () -> logger.info(
                    "The Netty HttpRequest was invalid - defaulting to a synthetic RequestInfo indicating an error. "
                    + "This usually happens when the request cannot be decoded as a valid HTTP request "
                    + "(i.e. bad caller).",
                    new Exception(
                        "This exception is for logging only, to see who called this method. See this exception's "
                        + "cause for details on the HTTP object decoder failure.",
                        decoderFailureCauseForLogging
                    )
                ),
                tracingStateForLogMsg
            ).run();
        }

        state.setRequestInfo(requestInfo);

        return requestInfo;
    }

    void throwExceptionIfNotSuccessfullyDecoded(HttpObject httpObject) {
        Throwable decoderFailure = getDecoderFailure(httpObject);
        if (decoderFailure == null) {
            return;
        }

        throw new InvalidHttpRequestException(
            "Detected HttpObject that was not successfully decoded.", decoderFailure
        );
    }

    Throwable getDecoderFailure(HttpObject httpObject) {
        if (httpObject == null) {
            return null;
        }

        DecoderResult decoderResult = httpObject.decoderResult();
        if (decoderResult == null) {
            return null;
        }

        if (!decoderResult.isFailure()) {
            return null;
        }

        return decoderResult.cause();
    }

    @Nullable Span getOverallRequestSpan(@Nullable HttpProcessingState state) {
        if (state == null) {
            return null;
        }

        return state.getOverallRequestSpan();
    }
}
