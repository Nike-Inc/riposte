package com.nike.riposte.server.handler;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.ServerSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.error.exception.MethodNotAllowed405Exception;
import com.nike.riposte.server.error.exception.MultipleMatchingEndpointsException;
import com.nike.riposte.server.error.exception.PathNotFound404Exception;
import com.nike.riposte.server.error.exception.RequestTooBigException;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.wingtips.Span;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import io.netty.channel.ChannelHandlerContext;
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
 * This must come before {@link SmartHttpContentDecompressor} in the pipeline so that it can turn off auto-decompression
 * for endpoints that aren't supposed to do auto-decompression (e.g. {@link
 * com.nike.riposte.server.http.ProxyRouterEndpoint}s). Consequently it must also come before {@link
 * RequestInfoSetterHandler}, which means we have to do the creation of the {@link RequestInfo} from the incoming
 * Netty {@link HttpRequest} message and set it on {@link HttpProcessingState} if the state didn't already have a
 * {@link RequestInfo}.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class RoutingHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    protected final @NotNull ServerSpanNamingAndTaggingStrategy<Span> spanNamingAndTaggingStrategy;
    protected final RiposteHandlerInternalUtil handlerUtils = RiposteHandlerInternalUtil.DEFAULT_IMPL;
    protected final Collection<Endpoint<?>> endpoints;
    protected final int globalConfiguredMaxRequestSizeInBytes;

    public RoutingHandler(
        Collection<Endpoint<?>> endpoints,
        int globalMaxRequestSizeInBytes,
        @NotNull DistributedTracingConfig<Span> distributedTracingConfig
    ) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints cannot be empty");
        }

        //noinspection ConstantConditions
        if (distributedTracingConfig == null) {
            throw new IllegalArgumentException("distributedTracingConfig cannot be null");
        }
        this.endpoints = endpoints;
        this.globalConfiguredMaxRequestSizeInBytes = globalMaxRequestSizeInBytes;
        this.spanNamingAndTaggingStrategy = distributedTracingConfig.getServerSpanNamingAndTaggingStrategy();
    }

    /**
     * @return The single {@link Endpoint} that matches and wants to handle the given request (and the path pattern that
     * the endpoint used when deciding it wanted to handle the request). This will throw a {@link
     * PathNotFound404Exception} if there are no matching endpoints. It will throw a {@link
     * MethodNotAllowed405Exception} if there's an endpoint that matches the path but not the HTTP method of the
     * request, and this will throw a {@link MultipleMatchingEndpointsException} if there are multiple endpoints that
     * fully match the path and HTTP method.
     */
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
            HttpRequest nettyRequest = (HttpRequest)msg;

            HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            RequestInfo request = handlerUtils.createRequestInfoFromNettyHttpRequestAndHandleStateSetupIfNecessary(
                nettyRequest,
                state
            );

            // If the Netty HttpRequest is invalid, we shouldn't do any endpoint routing.
            handlerUtils.throwExceptionIfNotSuccessfullyDecoded(nettyRequest);

            // The HttpRequest is valid, so continue with the endpoint routing.
            Pair<Endpoint<?>, String> endpointForExecution = findSingleEndpointForExecution(request);

            request.setPathParamsBasedOnPathTemplate(endpointForExecution.getRight());
            state.setEndpointForExecution(endpointForExecution.getLeft(), endpointForExecution.getRight());

            handleSpanNameUpdateForRequestWithPathTemplate(nettyRequest, request, state);

            throwExceptionIfContentLengthHeaderIsLargerThanConfiguredMaxRequestSize(
                nettyRequest, endpointForExecution.getLeft()
            );
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    protected void handleSpanNameUpdateForRequestWithPathTemplate(
        @NotNull HttpRequest nettyRequest,
        @NotNull RequestInfo riposteRequestInfo,
        @NotNull HttpProcessingState state
    ) {
        // Change the span name based on what the strategy wants now that the path template has been set on RequestInfo.
        Span requestSpan = handlerUtils.getOverallRequestSpan(state);
        if (requestSpan != null) {
            String newSpanName = handlerUtils.determineOverallRequestSpanName(
                nettyRequest, riposteRequestInfo, spanNamingAndTaggingStrategy
            );
            // Don't do anything if the span name hasn't actually changed, to avoid wiping out any cached data the
            //      span may have already calculated.
            if (!newSpanName.equals(requestSpan.getSpanName())) {
                // Span name is different now, so change it.
                spanNamingAndTaggingStrategy.changeSpanName(requestSpan, newSpanName);
            }
        }
    }

    private void throwExceptionIfContentLengthHeaderIsLargerThanConfiguredMaxRequestSize(HttpRequest msg, Endpoint<?> endpoint) {
        int configuredMaxRequestSize = getConfiguredMaxRequestSize(endpoint, globalConfiguredMaxRequestSizeInBytes);

        if (!isMaxRequestSizeValidationDisabled(configuredMaxRequestSize)
                && HttpHeaders.isContentLengthSet(msg)
                && HttpHeaders.getContentLength(msg) > configuredMaxRequestSize) {
            throw new RequestTooBigException(
                "Content-Length header value exceeded configured max request size of " + configuredMaxRequestSize
            );
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
