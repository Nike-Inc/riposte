package com.nike.riposte.server.handler;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.exception.MethodNotAllowed405Exception;
import com.nike.riposte.server.error.exception.MultipleMatchingEndpointsException;
import com.nike.riposte.server.error.exception.PathNotFound404Exception;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

import static com.nike.riposte.util.HttpUtils.getConfiguredMaxRequestSize;
import static com.nike.riposte.util.HttpUtils.isMaxRequestSizeValidationDisabled;

/**
 * Handles the logic of determining which {@link Endpoint} matches the {@link RequestInfo} in the channel's current
 * state ({@link com.nike.riposte.server.http.HttpProcessingState#getRequestInfo()}). It also performs some error
 * checking to make sure exactly 1 endpoint matches. See {@link #findSingleEndpointForExecution(RequestInfo)} for more
 * information on the error checking.
 * <p/>
 * This must come after {@link com.nike.riposte.server.handler.RequestInfoSetterHandler} in the pipeline.
 *
 * @author Nic Munroe
 */
public class RoutingHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Collection<Endpoint<?>> endpoints;
    private final int globalConfiguredMaxRequestSizeInBytes;

    public RoutingHandler(Collection<Endpoint<?>> endpoints, int globalMaxRequestSizeInBytes) {
        if (endpoints == null || endpoints.isEmpty())
            throw new IllegalArgumentException("endpoints cannot be empty");

        this.endpoints = endpoints;
        this.globalConfiguredMaxRequestSizeInBytes = globalMaxRequestSizeInBytes;
    }

    /**
     * @return The single {@link Endpoint} that matches and wants to handle the given request (and the path pattern that
     * the endpoint used when deciding it wanted to handle the request). This will throw a {@link
     * PathNotFound404Exception} if there are no matching endpoints. It will throw a {@link
     * MethodNotAllowed405Exception} if there's an endpoint that matches the path but not the HTTP method of the
     * request, and this will throw a {@link MultipleMatchingEndpointsException} if there are multiple endpoints that
     * fully match the path and HTTP method.
     */
    @SuppressWarnings("WeakerAccess")
    protected Pair<Endpoint<?>, String> findSingleEndpointForExecution(RequestInfo requestInfo) {
        boolean hasPathMatch = false;
        List<Endpoint<?>> fullyMatchingEndpoints = new ArrayList<>(1);
        String matchingPattern = "";

        for (Endpoint<?> endpoint : endpoints) {
            Optional<String> pattern = endpoint.requestMatcher().matchesPath(requestInfo);
            if (pattern.isPresent()) {
                hasPathMatch = true;
                if (endpoint.requestMatcher().matchesMethod(requestInfo)) {
                    fullyMatchingEndpoints.add(endpoint);
                    matchingPattern = pattern.get();
                }
            }
        }

        // If there's no endpoint that even matches the path then this is a 404 situation.
        if (!hasPathMatch) {
            throw new PathNotFound404Exception(
                "No matching endpoint found. requested_uri_path=" + requestInfo.getPath() + ", requested_method="
                + requestInfo.getMethod());
        }

        // We have at least one path match. fullyMatchingEndpoints will now tell us how many matched both path
        //      *and* HTTP method.

        // Do error checking.
        if (fullyMatchingEndpoints.isEmpty()) {
            // Not a 404 because we did have at least one endpoint that matched the path, but none matched both path and
            //      HTTP method so we throw a 405.
            throw new MethodNotAllowed405Exception(
                "Found path match for incoming request, but no endpoint matched both path and HTTP method",
                requestInfo.getPath(), String.valueOf(requestInfo.getMethod()));
        }

        if (fullyMatchingEndpoints.size() > 1) {
            // More than 1 endpoint matched. Also not ok.
            throw new MultipleMatchingEndpointsException(
                "Found multiple endpoints that matched the incoming request's path and HTTP method. This is not "
                + "allowed - your endpoints must be structured so that only one endpoint can match any given request",
                fullyMatchingEndpoints, requestInfo.getPath(), String.valueOf(requestInfo.getMethod())
            );
        }

        // At this point we know there's exactly 1 fully matching endpoint, so go ahead and return it.
        return Pair.of(fullyMatchingEndpoints.get(0), matchingPattern);
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            RequestInfo request = state.getRequestInfo();
            Pair<Endpoint<?>, String> endpointForExecution = findSingleEndpointForExecution(request);

            throwExceptionIfContentLengthHeaderIsLargerThanConfiguredMaxRequestSize((HttpRequest) msg, endpointForExecution.getLeft());

            request.setPathParamsBasedOnPathTemplate(endpointForExecution.getRight());

            state.setEndpointForExecution(endpointForExecution.getLeft(), endpointForExecution.getRight());
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    private void throwExceptionIfContentLengthHeaderIsLargerThanConfiguredMaxRequestSize(HttpRequest msg, Endpoint<?> endpoint) {
        int configuredMaxRequestSize = getConfiguredMaxRequestSize(endpoint, globalConfiguredMaxRequestSizeInBytes);

        if (!isMaxRequestSizeValidationDisabled(configuredMaxRequestSize)
                && HttpHeaders.isContentLengthSet(msg)
                && HttpHeaders.getContentLength(msg) > configuredMaxRequestSize) {
            throw new TooLongFrameException("Content-Length header value exceeded configured max request size of " + configuredMaxRequestSize);
        }
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // This class does not log, and nothing that happens in this class should cause logging to happen elsewhere.
        //      Therefore we should never bother with linking/unlinking tracing info to save on the extra processing.
        return false;
    }
}
