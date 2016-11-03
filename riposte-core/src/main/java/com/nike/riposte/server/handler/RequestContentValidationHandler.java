package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.validation.RequestValidator;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.LastHttpContent;

import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Looks at the current channel state's {@link HttpProcessingState#getEndpointForExecution()} and {@link
 * HttpProcessingState#getRequestInfo()} to see if (1) the endpoint wants the incoming request content validated, and
 * (2) the incoming request's {@link RequestInfo#getContent()} is populated. If both those things are true then {@link
 * #validationService} will be run on the request's content, which will throw an appropriate exception with validation
 * violation details. If {@link #validationService} is null then no validation will be performed.
 * <p/>
 * NOTE: Both the payload deserialization and validation may be done asynchronously if {@link
 * Endpoint#shouldValidateAsynchronously(RequestInfo)} returns true. If asynchronous processing is requested then the
 * deserialization and validation will be done via {@link CompletableFuture} running in a separate {@link Executor}.
 * That future is added to {@link HttpProcessingState#addPreEndpointExecutionWorkChainSegment(Function)} so that the
 * endpoint handler can make sure the future completes successfully before the endpoint execution happens. If the future
 * throws an exception then the endpoint should not be executed.
 * <p/>
 * This must come after {@link com.nike.riposte.server.handler.RequestContentDeserializerHandler} in the pipeline to
 * make sure that the {@link com.nike.riposte.server.http.RequestInfo#getContent()} has had a chance to be populated.
 *
 * @author Nic Munroe
 */
public class RequestContentValidationHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private static final Executor ASYNC_VALIDATION_EXECUTOR =
        Executors.newWorkStealingPool(Runtime.getRuntime().availableProcessors() * 2);

    private final RequestValidator validationService;

    public RequestContentValidationHandler(RequestValidator validationService) {
        if (validationService == null) {
            throw new IllegalArgumentException(
                "validationService cannot be null. If you don't have a validationService to pass in, don't register "
                + "this handler in the pipeline"
            );
        }

        this.validationService = validationService;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LastHttpContent) {
            HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            Endpoint<?> endpoint = state.getEndpointForExecution();
            RequestInfo<?> requestInfo = state.getRequestInfo();
            if (endpoint != null
                && endpoint.isValidateRequestContent(requestInfo)
                && requestInfo.isCompleteRequestWithAllChunks() // Request must be complete
                && requestInfo.isContentDeserializerSetup()     // Content deserialization must be possible
                && requestInfo.getRawContentLengthInBytes() > 0 // Must have something to validate - TODO: This last rule for non-empty content might change depending on what we do about auto-validating null content.
                ) {
                if (endpoint.shouldValidateAsynchronously(requestInfo)) {
                    // The endpoint has requested asynchronous validation, so split it off into the
                    //      pre-endpoint-execution-work-chain.
                    state.addPreEndpointExecutionWorkChainSegment(aVoid -> CompletableFuture.runAsync(
                        () -> executeValidation(requestInfo, endpoint, ctx),
                        ASYNC_VALIDATION_EXECUTOR)
                    );
                }
                else {
                    // This request can be validated synchronously, so do it now.
                    executeValidation(requestInfo, endpoint, ctx);
                }
            }
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // To save on extraneous linking/unlinking, we'll do it as-necessary in this class.
        return false;
    }

    @SuppressWarnings("WeakerAccess")
    protected void executeValidation(RequestInfo<?> requestInfo, Endpoint<?> endpoint, ChannelHandlerContext ctx) {
        runnableWithTracingAndMdc(() -> {
            // NOTE: The requestInfo.getContent() call should be here in this method because this is the first time
            //      getContent() is called and will deserialize the payload here. For very large payloads it may take a
            //      while to deserialize, and if we're validating asynchronously we should be deserializing
            //      asynchronously as well.
            // TODO: How do we want to deal with choosing whether null content is considered invalid? Boolean flag on
            //       the endpoint and throw custom exception here if endpoint says null not allowed but content is null?
            //       Error handlers would need to be updated as well.
            if (requestInfo.getContent() != null) {
                // We have an endpoint and validation is requested and the content has been deserialized.
                //      Perform the validation.
                Class<?>[] validationGroups = endpoint.validationGroups(requestInfo);
                if (validationGroups == null)
                    validationService.validateRequestContent(requestInfo);
                else
                    validationService.validateRequestContent(requestInfo, validationGroups);
            }
        }, ctx).run();
    }
}
