package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.validation.RequestSecurityValidator;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;

/**
 * Performs security validation and requires a non-null {@link #securityValidator} implementation.
 * <p>
 *      NOTE: This may set a security context attribute {@link RequestSecurityValidator#REQUEST_SECURITY_ATTRIBUTE_KEY}
 *      via {@link RequestInfo#addRequestAttribute(String, Object)}, depending on the behavior of
 *      {@link RequestSecurityValidator#validateSecureRequestForEndpoint(RequestInfo, Endpoint)}.
 * </p>
 * <p>
 *      {@link RequestSecurityValidator#isFastEnoughToRunOnNettyWorkerThread()} should tell us whether or not it is fast
 *      enough to run directly on the Netty worker thread. If that method returns false then the security validation
 *      will be executed asynchronously on a {@link CompletableFuture} running in a separate {@link Executor} as a
 *      future. That future is added to {@link HttpProcessingState#addPreEndpointExecutionWorkChainSegment(Function)} so
 *      that the endpoint handler can make sure the security future completes successfully before the endpoint execution
 *      happens. If the security future throws an exception then the endpoint should not be executed.
 * </p>
 *
 * This should be placed right after the request routing handler.
 */
public class SecurityValidationHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final Executor DEFAULT_ASYNC_VALIDATION_EXECUTOR =
        Executors.newWorkStealingPool(Runtime.getRuntime().availableProcessors() * 2);

    private final RequestSecurityValidator securityValidator;
    private final Executor securityValidationExecutor;

    public SecurityValidationHandler(RequestSecurityValidator securityValidator) {
        this.securityValidator = securityValidator;
        this.securityValidationExecutor =
            (securityValidator == null || securityValidator.isFastEnoughToRunOnNettyWorkerThread())
            ? null
            : DEFAULT_ASYNC_VALIDATION_EXECUTOR;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) {
        if (shouldHandleDoChannelReadMessage(msg)) {
            HttpProcessingState httpProcessingState = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            if (httpProcessingState != null) {
                Collection<Endpoint<?>> endpointsToValidate = securityValidator.endpointsToValidate();
                if (endpointsToValidate != null
                    && endpointsToValidate.contains(httpProcessingState.getEndpointForExecution())) {

                    if (logger.isDebugEnabled()) {
                        runnableWithTracingAndMdc(() -> logger.debug("Verify security context"), ctx).run();
                    }

                    if (securityValidator.isFastEnoughToRunOnNettyWorkerThread()) {
                        // The security validator is fast enough to run right here on the Netty worker thread,
                        //      so make a synchronous call.
                        runnableWithTracingAndMdc(
                            () -> securityValidator.validateSecureRequestForEndpoint(
                                httpProcessingState.getRequestInfo(), httpProcessingState.getEndpointForExecution()
                            ),
                            ctx
                        ).run();
                    }
                    else {
                        // The security validator is too slow, so it needs to run asynchronously off the Netty worker
                        //      thread. Add the security validator execution to the pre-endpoint-execution-work-chain
                        //      as a CompletableFuture.
                        httpProcessingState.addPreEndpointExecutionWorkChainSegment(
                            aVoid -> CompletableFuture.runAsync(
                                runnableWithTracingAndMdc(
                                    () -> securityValidator.validateSecureRequestForEndpoint(
                                        httpProcessingState.getRequestInfo(),
                                        httpProcessingState.getEndpointForExecution()
                                    ), ctx
                                ),
                                securityValidationExecutor
                            )
                        );
                    }

                }
                else {
                    if (logger.isDebugEnabled()) {
                        runnableWithTracingAndMdc(() -> logger.debug("No security validation necessary."), ctx).run();
                    }
                }
            }
            else {
                runnableWithTracingAndMdc(
                    () -> logger.warn("HttpProcessingState is null. This shouldn't happen."),
                    ctx
                ).run();
            }
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @SuppressWarnings("WeakerAccess")
    protected boolean shouldHandleDoChannelReadMessage(Object msg) {
        return (msg instanceof HttpRequest) && (securityValidator != null);
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // To save on extraneous linking/unlinking, we'll do it as-necessary in this class.
        return false;
    }
}
