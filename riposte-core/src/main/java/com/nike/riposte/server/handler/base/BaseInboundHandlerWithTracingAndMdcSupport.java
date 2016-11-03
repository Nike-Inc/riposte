package com.nike.riposte.server.handler.base;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.util.AsyncNettyHelper;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.stream.Collectors;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.EventExecutorGroup;

import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_CHANNEL_ACTIVE;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_CHANNEL_INACTIVE;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_CHANNEL_READ;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_CHANNEL_READ_COMPLETE;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_CHANNEL_REGISTERED;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_CHANNEL_UNREGISTERED;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_CHANNEL_WRITABILITY_CHANGED;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_EXCEPTION_CAUGHT;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_HANDLER_ADDED;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_HANDLER_REMOVED;
import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_USER_EVENT_TRIGGERED;
import static com.nike.riposte.server.handler.base.PipelineContinuationBehavior.CONTINUE;

/**
 * <b>
 *     <i>CRITICAL WARNING:</i> Do not, under any circumstances, register a concrete implementation of this base handler
 *     with a channel pipeline so that it uses a separate executor group (e.g. {@link
 *     ChannelPipeline#addLast(EventExecutorGroup, ChannelHandler...)}. You will get race conditions on the {@link
 *     #linkTracingAndMdcToCurrentThread(ChannelHandlerContext)} and/or {@link
 *     #unlinkTracingAndMdcFromCurrentThread(ChannelHandlerContext, Pair)} methods and it will screw up your channel's
 *     {@link HttpProcessingState} object. These symptoms will be rare so things will look like they're working fine but
 *     you will get sporadic failures that will be extremely difficult to track down. Just don't do it. Instead, if you
 *     need a handler that extends this class to support blocking tasks, have the handler run those tasks directly using
 *     an executor group rather than running the handler itself on the executor group.
 * </b>
 *
 * <p>This is an extension of {@link ChannelInboundHandlerAdapter} that supports distributed tracing and MDC in an
 * invisible manner so that concrete implementations don't need to worry about them. Concrete implementations should
 * override one of the {@code do...} methods rather than the actual handler method (e.g. you should override {@link
 * #doChannelRead(ChannelHandlerContext, Object)}, NOT {@link #channelRead(ChannelHandlerContext, Object)}). Before a
 * {@code do...} method is called the tracing and MDC for the request will be registered on the current thread via
 * {@link #linkTracingAndMdcToCurrentThread(ChannelHandlerContext)}, and when the {@code do...} method is done they will
 * be unregistered and stored for the next {@code do...} call via {@link
 * #unlinkTracingAndMdcFromCurrentThread(ChannelHandlerContext, Pair)}. Each {@code do...} method returns a {@link
 * PipelineContinuationBehavior}, which controls whether the event will be propagated on the pipeline or whether it
 * should stop there.
 *
 * <p>NOTE: Linking and unlinking tracing and MDC info can get expensive if it's done dozens and dozens of times
 * for each request, especially when it's often unnecessary (i.e. your class only executes any real logic in some but
 * not all cases). Therefore this class allows you to fine tune when the linking/unlinking is performed by overriding
 * {@link #argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(HandlerMethodToExecute, ChannelHandlerContext,
 * Object, Throwable)}.
 * If your class only needs linking/unlinking for some methods, or specific message types, etc, then you can override
 * that method and include the logic for when the linking/unlinking should occur. And if your class only needs the
 * tracing and MDC info attached in very rare cases (e.g. when an error occurs) but the usual case does not require it,
 * then you might consider overriding that method to always return false, and link/unlink on demand for the rare cases
 * you need it (i.e. using something like {@link
 * AsyncNettyHelper#runnableWithTracingAndMdc(Runnable, ChannelHandlerContext)} to execute a log statement with the
 * correct tracing and MDC info attached).
 *
 * <p>ALSO NOTE: Due to the potential nested nature of the handler calls, {@link
 * #linkTracingAndMdcToCurrentThread(ChannelHandlerContext)} returns the original MDC and tracer information as it was
 * found on the thread when the link method was called, and {@link
 * #unlinkTracingAndMdcFromCurrentThread(ChannelHandlerContext, Pair)} takes that original thread info as an
 * argument and resets the thread to that original pre-link-thread-data information after storing the necessary
 * current-thread-data in the state object. Without this you could have a situation where nested handler calls happen on
 * the same thread for the same request, and the inner handler's unlink method would empty the thread information
 * causing broken behavior as the nested calls unwound.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public abstract class BaseInboundHandlerWithTracingAndMdcSupport extends ChannelInboundHandlerAdapter {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public static final String DEBUG_HANDLER_METHOD_CALLS_SYSTEM_PROP_KEY = "netty.debugBaseHandlerMethodCalls";
    public static final String FORCE_ENABLE_DTRACE_REGISTRATION_FOR_ALL_HANDLER_METHODS =
        "forceEnableDTraceRegistrationForAllNettyHandlerMethods";
    /**
     * Whether or not each event handler method should get a debug message spit out to the logs. This can be very useful
     * during debugging when you're trying to figure out what the handlers are doing, when events are fired, etc. In
     * order to turn this on you'll need to set the {@link #DEBUG_HANDLER_METHOD_CALLS_SYSTEM_PROP_KEY} System property
     * so that it is set to the string "true" when your handlers are instantiated. Once it's turned on you can further
     * limit output to only specific handlers by tweaking your SLF4J logging properties so that debug-level logging is
     * only enabled for the handlers you want to see.
     */
    private final boolean debugHandlerMethodCalls =
        "true".equalsIgnoreCase(System.getProperty(DEBUG_HANDLER_METHOD_CALLS_SYSTEM_PROP_KEY));

    private final boolean forceEnableDTraceOnAllMethods =
        "true".equalsIgnoreCase(System.getProperty(FORCE_ENABLE_DTRACE_REGISTRATION_FOR_ALL_HANDLER_METHODS));

    private final boolean isDefaultDoChannelRegisteredImpl;
    private final boolean isDefaultDoChannelUnregisteredImpl;
    private final boolean isDefaultDoChannelActiveImpl;
    private final boolean isDefaultDoChannelInactiveImpl;
    private final boolean isDefaultDoChannelReadImpl;
    private final boolean isDefaultDoChannelReadCompleteImpl;
    private final boolean isDefaultDoUserEventTriggeredImpl;
    private final boolean isDefaultDoChannelWritabilityChangedImpl;
    private final boolean isDefaultDoExceptionCaughtImpl;
    private final boolean isDefaultDoHandlerAddedImpl;
    private final boolean isDefaultDoHandlerRemovedImpl;

    public BaseInboundHandlerWithTracingAndMdcSupport() {
        Method[] methods = this.getClass().getMethods();
        Map<String, Method> nameToMethodMap = Arrays.stream(methods)
                                                    .filter(m -> m.getName().startsWith("do"))
                                                    .collect(Collectors.toMap(Method::getName, m -> m));

        isDefaultDoChannelRegisteredImpl = isDefaultMethodImpl("doChannelRegistered", nameToMethodMap);
        isDefaultDoChannelUnregisteredImpl = isDefaultMethodImpl("doChannelUnregistered", nameToMethodMap);
        isDefaultDoChannelActiveImpl = isDefaultMethodImpl("doChannelActive", nameToMethodMap);
        isDefaultDoChannelInactiveImpl = isDefaultMethodImpl("doChannelInactive", nameToMethodMap);
        isDefaultDoChannelReadImpl = isDefaultMethodImpl("doChannelRead", nameToMethodMap);
        isDefaultDoChannelReadCompleteImpl = isDefaultMethodImpl("doChannelReadComplete", nameToMethodMap);
        isDefaultDoUserEventTriggeredImpl = isDefaultMethodImpl("doUserEventTriggered", nameToMethodMap);
        isDefaultDoChannelWritabilityChangedImpl = isDefaultMethodImpl("doChannelWritabilityChanged", nameToMethodMap);
        isDefaultDoExceptionCaughtImpl = isDefaultMethodImpl("doExceptionCaught", nameToMethodMap);
        isDefaultDoHandlerAddedImpl = isDefaultMethodImpl("doHandlerAdded", nameToMethodMap);
        isDefaultDoHandlerRemovedImpl = isDefaultMethodImpl("doHandlerRemoved", nameToMethodMap);
    }

    protected static boolean isDefaultMethodImpl(String methodNameInQuestion, Map<String, Method> nameToMethodMap) {
        Method method = nameToMethodMap.get(methodNameInQuestion);
        if (method == null)
            throw new IllegalStateException(
                "Unable to find method named " + methodNameInQuestion + " in list of methods.");

        return method.getDeclaringClass().equals(BaseInboundHandlerWithTracingAndMdcSupport.class);
    }

    public enum HandlerMethodToExecute {
        DO_CHANNEL_REGISTERED,
        DO_CHANNEL_UNREGISTERED,
        DO_CHANNEL_ACTIVE,
        DO_CHANNEL_INACTIVE,
        DO_CHANNEL_READ,
        DO_CHANNEL_READ_COMPLETE,
        DO_USER_EVENT_TRIGGERED,
        DO_CHANNEL_WRITABILITY_CHANGED,
        DO_EXCEPTION_CAUGHT,
        DO_HANDLER_ADDED,
        DO_HANDLER_REMOVED
    }

    protected Pair<Deque<Span>, Map<String, String>> linkTracingAndMdcToCurrentThread(ChannelHandlerContext ctx) {
        return AsyncNettyHelper.linkTracingAndMdcToCurrentThread(ctx);
    }

    protected void unlinkTracingAndMdcFromCurrentThread(ChannelHandlerContext ctx,
                                                        Pair<Deque<Span>, Map<String, String>> origThreadInfo) {
        // Update the state (if we have any) with the current values of the MDC and tracer data
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state != null) {
            // Get references to the *current* MDC and tracer data for storing in our ctx
            //      and set them on the state object
            Map<String, String> currentMdcContextMapForState = MDC.getCopyOfContextMap();
            Deque<Span> currentTraceStackForState = Tracer.getInstance().unregisterFromThread();

            state.setLoggerMdcContextMap(currentMdcContextMapForState);
            state.setDistributedTraceStack(currentTraceStackForState);
        }

        // Reset the thread to the way it was before linkTracingAndMdcToCurrentThread was called
        AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread(origThreadInfo);
    }

    protected boolean shouldLinkAndUnlinkDistributedTraceInfoForMethod(
        HandlerMethodToExecute methodToExecute, boolean isDefaultMethodImpl, boolean forceEnableDTraceOnAllMethods,
        boolean debugHandlerMethodCalls,
        ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        return
            (!isDefaultMethodImpl
             && argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(methodToExecute, ctx, msgOrEvt, cause)
            )
            || forceEnableDTraceOnAllMethods
            || debugHandlerMethodCalls;
    }

    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        return true;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_CHANNEL_REGISTERED, isDefaultDoChannelRegisteredImpl, forceEnableDTraceOnAllMethods,
            debugHandlerMethodCalls, ctx, null, null
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("11111111111111 channelRegistered for {}", this.getClass().getName());

            methodExecutionResponse = doChannelRegistered(ctx);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.channelRegistered(ctx);
    }

    @SuppressWarnings("UnusedParameters")
    public PipelineContinuationBehavior doChannelRegistered(ChannelHandlerContext ctx) throws Exception {
        return CONTINUE;
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_CHANNEL_UNREGISTERED, isDefaultDoChannelUnregisteredImpl, forceEnableDTraceOnAllMethods,
            debugHandlerMethodCalls, ctx, null, null
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("222222222222222 channelUnregistered for {}", this.getClass().getName());

            methodExecutionResponse = doChannelUnregistered(ctx);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.channelUnregistered(ctx);
    }

    @SuppressWarnings("UnusedParameters")
    public PipelineContinuationBehavior doChannelUnregistered(ChannelHandlerContext ctx) throws Exception {
        return CONTINUE;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_CHANNEL_ACTIVE, isDefaultDoChannelActiveImpl, forceEnableDTraceOnAllMethods, debugHandlerMethodCalls,
            ctx, null, null
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("333333333333333 channelActive for {}", this.getClass().getName());

            methodExecutionResponse = doChannelActive(ctx);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.channelActive(ctx);
    }

    public PipelineContinuationBehavior doChannelActive(ChannelHandlerContext ctx) throws Exception {
        return CONTINUE;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_CHANNEL_INACTIVE, isDefaultDoChannelInactiveImpl, forceEnableDTraceOnAllMethods, debugHandlerMethodCalls,
            ctx, null, null
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("444444444444444 channelInactive for {}", this.getClass().getName());

            methodExecutionResponse = doChannelInactive(ctx);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.channelInactive(ctx);
    }

    @SuppressWarnings("UnusedParameters")
    public PipelineContinuationBehavior doChannelInactive(ChannelHandlerContext ctx) throws Exception {
        return CONTINUE;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_CHANNEL_READ, isDefaultDoChannelReadImpl, forceEnableDTraceOnAllMethods, debugHandlerMethodCalls,
            ctx, msg, null
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("############### channelRead for {}", this.getClass().getName());

            methodExecutionResponse = doChannelRead(ctx, msg);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.channelRead(ctx, msg);
    }

    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        return CONTINUE;
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_CHANNEL_READ_COMPLETE, isDefaultDoChannelReadCompleteImpl, forceEnableDTraceOnAllMethods,
            debugHandlerMethodCalls, ctx, null, null
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("$$$$$$$$$$$$$$$$$ channelReadComplete for {}", this.getClass().getName());

            methodExecutionResponse = doChannelReadComplete(ctx);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.channelReadComplete(ctx);
    }

    @SuppressWarnings("UnusedParameters")
    public PipelineContinuationBehavior doChannelReadComplete(ChannelHandlerContext ctx) throws Exception {
        return CONTINUE;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_USER_EVENT_TRIGGERED, isDefaultDoUserEventTriggeredImpl, forceEnableDTraceOnAllMethods,
            debugHandlerMethodCalls, ctx, evt, null
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("************** userEventTriggered for {}", this.getClass().getName());

            methodExecutionResponse = doUserEventTriggered(ctx, evt);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.userEventTriggered(ctx, evt);
    }

    @SuppressWarnings("UnusedParameters")
    public PipelineContinuationBehavior doUserEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        return CONTINUE;
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_CHANNEL_WRITABILITY_CHANGED, isDefaultDoChannelWritabilityChangedImpl, forceEnableDTraceOnAllMethods,
            debugHandlerMethodCalls, ctx, null, null
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("^^^^^^^^^^^^^^^ channelWritabilityChanged for {}", this.getClass().getName());

            methodExecutionResponse = doChannelWritabilityChanged(ctx);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.channelWritabilityChanged(ctx);
    }

    @SuppressWarnings("UnusedParameters")
    public PipelineContinuationBehavior doChannelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        return CONTINUE;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_EXCEPTION_CAUGHT, isDefaultDoExceptionCaughtImpl, forceEnableDTraceOnAllMethods, debugHandlerMethodCalls,
            ctx, null, cause
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("!!!!!!!!!!!!!!!!! exceptionCaught for {}", this.getClass().getName());

            methodExecutionResponse = doExceptionCaught(ctx, cause);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.exceptionCaught(ctx, cause);
    }

    public PipelineContinuationBehavior doExceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        return CONTINUE;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_HANDLER_ADDED, isDefaultDoHandlerAddedImpl, forceEnableDTraceOnAllMethods, debugHandlerMethodCalls,
            ctx, null, null
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("(((((((((((((( handlerAdded for {}", this.getClass().getName());

            methodExecutionResponse = doHandlerAdded(ctx);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.handlerAdded(ctx);
    }

    @SuppressWarnings("UnusedParameters")
    public PipelineContinuationBehavior doHandlerAdded(ChannelHandlerContext ctx) throws Exception {
        return CONTINUE;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        PipelineContinuationBehavior methodExecutionResponse;

        boolean shouldLinkAndUnlinkDTraceInfo = shouldLinkAndUnlinkDistributedTraceInfoForMethod(
            DO_HANDLER_REMOVED, isDefaultDoHandlerRemovedImpl, forceEnableDTraceOnAllMethods, debugHandlerMethodCalls,
            ctx, null, null
        );

        Pair<Deque<Span>, Map<String, String>> origThreadInfo = null;
        try {
            if (shouldLinkAndUnlinkDTraceInfo)
                origThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

            if (debugHandlerMethodCalls)
                logger.debug("))))))))))))))))) handlerRemoved for {}", this.getClass().getName());

            methodExecutionResponse = doHandlerRemoved(ctx);
        }
        finally {
            if (shouldLinkAndUnlinkDTraceInfo)
                unlinkTracingAndMdcFromCurrentThread(ctx, origThreadInfo);
        }

        if (methodExecutionResponse == null || CONTINUE.equals(methodExecutionResponse))
            super.handlerRemoved(ctx);
    }

    @SuppressWarnings("UnusedParameters")
    public PipelineContinuationBehavior doHandlerRemoved(ChannelHandlerContext ctx) throws Exception {
        return CONTINUE;
    }
}
