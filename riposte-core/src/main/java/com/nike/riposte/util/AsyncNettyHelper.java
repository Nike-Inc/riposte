package com.nike.riposte.util;

import com.nike.fastbreak.CircuitBreaker;
import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.util.asynchelperwrapper.BiConsumerWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.BiFunctionWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.CallableWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.ConsumerWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.FunctionWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.RunnableWithTracingAndMdcSupport;
import com.nike.riposte.util.asynchelperwrapper.SupplierWithTracingAndMdcSupport;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.channel.ChannelHandlerContext;

/**
 * Helper class that provides static methods for dealing with async stuff in Netty/Riposte, mainly providing easy ways
 * to support distributed tracing and logger MDC when hopping threads using {@link
 * java.util.concurrent.CompletableFuture}s. There are various {@code *WithTracingAndMdc(...)} methods for wrapping
 * objects with the same type that knows how to handle tracing and MDC. You can also call the various {@code
 * linkTracingAndMdcToCurrentThread(...)} and {@code unlinkTracingAndMdcFromCurrentThread(...)} methods directly if
 * you want to do it manually yourself.
 * <p/>
 * If you want to use the link and unlink methods manually to wrap some chunk of code, the general procedure looks like
 * this:
 * <pre>
 *  Pair&lt;Deque&lt;Span>, Map&lt;String, String>> originalThreadInfo = null;
 *  try {
 *      originalThreadInfo = AsyncNettyHelper.linkTracingAndMdcToCurrentThread(...);
 *      // Code that needs wrapping goes here
 *  }
 *  finally {
 *      AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread(originalThreadInfo);
 *  }
 * </pre>
 * Following this procedure guarantees that the code you want wrapped will be wrapped successfully with whatever tracing
 * and MDC info you want, but when it finishes the trace and MDC info will be put back the way it was as if your code
 * never ran. If you deviate from this then you risk having traces stomp on each other or other weird interactions that
 * you never expect or predict can mess up your tracing, so make sure you know what you're doing and test it thoroughly
 * under load if you deviate from this procedure.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class AsyncNettyHelper {

    private static final Logger logger = LoggerFactory.getLogger(AsyncNettyHelper.class);
    public static final Void VOID = null;

    // Intentionally protected - use the static methods.
    protected AsyncNettyHelper() { /* do nothing */ }

    /**
     * @return A {@link Runnable} that wraps the given original so that the given {@link ChannelHandlerContext}'s
     * distributed tracing and MDC information is registered with the thread and therefore available during execution
     * and unregistered after execution.
     */
    public static Runnable runnableWithTracingAndMdc(Runnable runnable, ChannelHandlerContext ctx) {
        return new RunnableWithTracingAndMdcSupport(runnable, ctx);
    }

    /**
     * @return A {@link Runnable} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static Runnable runnableWithTracingAndMdc(Runnable runnable,
                                                     Pair<Deque<Span>, Map<String, String>> threadInfoToLink) {
        return new RunnableWithTracingAndMdcSupport(runnable, threadInfoToLink);
    }

    /**
     * @return A {@link Runnable} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static Runnable runnableWithTracingAndMdc(Runnable runnable, Deque<Span> distributedTraceStackToLink,
                                                     Map<String, String> mdcContextMapToLink) {
        return new RunnableWithTracingAndMdcSupport(runnable, distributedTraceStackToLink, mdcContextMapToLink);
    }

    /**
     * @return A {@link Callable} that wraps the given original so that the given {@link ChannelHandlerContext}'s
     * distributed tracing and MDC information is registered with the thread and therefore available during execution
     * and unregistered after execution.
     */
    public static <U> Callable<U> callableWithTracingAndMdc(Callable<U> callable, ChannelHandlerContext ctx) {
        return new CallableWithTracingAndMdcSupport<>(callable, ctx);
    }

    /**
     * @return A {@link Callable} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static <U> Callable<U> callableWithTracingAndMdc(Callable<U> callable,
                                                            Pair<Deque<Span>, Map<String, String>> threadInfoToLink) {
        return new CallableWithTracingAndMdcSupport<>(callable, threadInfoToLink);
    }

    /**
     * @return A {@link Callable} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static <U> Callable<U> callableWithTracingAndMdc(Callable<U> callable,
                                                            Deque<Span> distributedTraceStackToLink,
                                                            Map<String, String> mdcContextMapToLink) {
        return new CallableWithTracingAndMdcSupport<>(callable, distributedTraceStackToLink, mdcContextMapToLink);
    }

    /**
     * @return A {@link Supplier} that wraps the given original so that the given {@link ChannelHandlerContext}'s
     * distributed tracing and MDC information is registered with the thread and therefore available during execution
     * and unregistered after execution.
     */
    public static <U> Supplier<U> supplierWithTracingAndMdc(Supplier<U> supplier, ChannelHandlerContext ctx) {
        return new SupplierWithTracingAndMdcSupport<>(supplier, ctx);
    }

    /**
     * @return A {@link Supplier} that wraps the given distributed tracing and MDC information is registered with the
     * thread and therefore available during execution and unregistered after execution.
     */
    public static <U> Supplier<U> supplierWithTracingAndMdc(Supplier<U> supplier,
                                                            Pair<Deque<Span>, Map<String, String>> threadInfoToLink) {
        return new SupplierWithTracingAndMdcSupport<>(supplier, threadInfoToLink);
    }

    /**
     * @return A {@link Supplier} that wraps the given distributed tracing and MDC information is registered with the
     * thread and therefore available during execution and unregistered after execution.
     */
    public static <U> Supplier<U> supplierWithTracingAndMdc(Supplier<U> supplier,
                                                            Deque<Span> distributedTraceStackToLink,
                                                            Map<String, String> mdcContextMapToLink) {
        return new SupplierWithTracingAndMdcSupport<>(supplier, distributedTraceStackToLink, mdcContextMapToLink);
    }

    /**
     * @return A {@link Function} that wraps the given original so that the given {@link ChannelHandlerContext}'s
     * distributed tracing and MDC information is registered with the thread and therefore available during execution
     * and unregistered after execution.
     */
    public static <T, U> Function<T, U> functionWithTracingAndMdc(Function<T, U> fn, ChannelHandlerContext ctx) {
        return new FunctionWithTracingAndMdcSupport<>(fn, ctx);
    }

    /**
     * @return A {@link Function} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static <T, U> Function<T, U> functionWithTracingAndMdc(
        Function<T, U> fn, Pair<Deque<Span>, Map<String, String>> threadInfoToLink
    ) {
        return new FunctionWithTracingAndMdcSupport<>(fn, threadInfoToLink);
    }

    /**
     * @return A {@link Function} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static <T, U> Function<T, U> functionWithTracingAndMdc(Function<T, U> fn,
                                                                  Deque<Span> distributedTraceStackToLink,
                                                                  Map<String, String> mdcContextMapToLink) {
        return new FunctionWithTracingAndMdcSupport<>(fn, distributedTraceStackToLink, mdcContextMapToLink);
    }

    /**
     * @return A {@link BiFunction} that wraps the given original so that the given {@link ChannelHandlerContext}'s
     * distributed tracing and MDC information is registered with the thread and therefore available during execution
     * and unregistered after execution.
     */
    public static <T, U, R> BiFunction<T, U, R> biFunctionWithTracingAndMdc(BiFunction<T, U, R> fn,
                                                                            ChannelHandlerContext ctx) {
        return new BiFunctionWithTracingAndMdcSupport<>(fn, ctx);
    }

    /**
     * @return A {@link BiFunction} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static <T, U, R> BiFunction<T, U, R> biFunctionWithTracingAndMdc(
        BiFunction<T, U, R> fn, Pair<Deque<Span>, Map<String, String>> threadInfoToLink
    ) {
        return new BiFunctionWithTracingAndMdcSupport<>(fn, threadInfoToLink);
    }

    /**
     * @return A {@link BiFunction} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static <T, U, R> BiFunction<T, U, R> biFunctionWithTracingAndMdc(BiFunction<T, U, R> fn,
                                                                            Deque<Span> distributedTraceStackToLink,
                                                                            Map<String, String> mdcContextMapToLink) {
        return new BiFunctionWithTracingAndMdcSupport<>(fn, distributedTraceStackToLink, mdcContextMapToLink);
    }

    /**
     * @return A {@link Consumer} that wraps the given original so that the given {@link ChannelHandlerContext}'s
     * distributed tracing and MDC information is registered with the thread and therefore available during execution
     * and unregistered after execution.
     */
    public static <T> Consumer<T> consumerWithTracingAndMdc(Consumer<T> consumer, ChannelHandlerContext ctx) {
        return new ConsumerWithTracingAndMdcSupport<>(consumer, ctx);
    }

    /**
     * @return A {@link Consumer} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static <T> Consumer<T> consumerWithTracingAndMdc(Consumer<T> consumer,
                                                            Pair<Deque<Span>, Map<String, String>> threadInfoToLink) {
        return new ConsumerWithTracingAndMdcSupport<>(consumer, threadInfoToLink);
    }

    /**
     * @return A {@link Consumer} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static <T> Consumer<T> consumerWithTracingAndMdc(Consumer<T> consumer,
                                                            Deque<Span> distributedTraceStackToLink,
                                                            Map<String, String> mdcContextMapToLink) {
        return new ConsumerWithTracingAndMdcSupport<>(consumer, distributedTraceStackToLink, mdcContextMapToLink);
    }

    /**
     * @return A {@link BiConsumer} that wraps the given original so that the given {@link ChannelHandlerContext}'s
     * distributed tracing and MDC information is registered with the thread and therefore available during execution
     * and unregistered after execution.
     */
    public static <T, U> BiConsumer<T, U> biConsumerWithTracingAndMdc(BiConsumer<T, U> biConsumer,
                                                                      ChannelHandlerContext ctx) {
        return new BiConsumerWithTracingAndMdcSupport<>(biConsumer, ctx);
    }

    /**
     * @return A {@link BiConsumer} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static <T, U> BiConsumer<T, U> biConsumerWithTracingAndMdc(
        BiConsumer<T, U> biConsumer, Pair<Deque<Span>, Map<String, String>> threadInfoToLink
    ) {
        return new BiConsumerWithTracingAndMdcSupport<>(biConsumer, threadInfoToLink);
    }

    /**
     * @return A {@link BiConsumer} that wraps the given original so that the given distributed tracing and MDC
     * information is registered with the thread and therefore available during execution and unregistered after
     * execution.
     */
    public static <T, U> BiConsumer<T, U> biConsumerWithTracingAndMdc(BiConsumer<T, U> biConsumer,
                                                                      Deque<Span> distributedTraceStackToLink,
                                                                      Map<String, String> mdcContextMapToLink) {
        return new BiConsumerWithTracingAndMdcSupport<>(biConsumer, distributedTraceStackToLink, mdcContextMapToLink);
    }

    /**
     * Extracts the {@link HttpProcessingState} from the given {@link ChannelHandlerContext}'s channel and uses the
     * resulting {@link HttpProcessingState#getDistributedTraceStack()} and {@link
     * HttpProcessingState#getLoggerMdcContextMap()} to setup distributed tracing and the logging MDC on the current
     * thread with the values contained in the state by calling {@link #linkTracingAndMdcToCurrentThread(Deque, Map)}.
     *
     * @param ctx
     *     The {@link ChannelHandlerContext} to use to extract the {@link HttpProcessingState}, and from the state
     *     extract the distributed tracing and MDC info to link to the thread. This method safely handles null - if null
     *     is found for the ctx argument or the state inside then {@link Tracer} will be setup with an empty trace stack
     *     (wiping out any existing in-progress traces) *and* {@link org.slf4j.MDC#clear()} will be called (wiping out
     *     any existing MDC info).
     *
     * @return A *COPY* of the original trace stack and MDC info on the thread when this method was called (before being
     * replaced with the given arguments). The {@link Pair} object will never be null, but the values it contains may be
     * null. A copy is returned rather than the original to prevent undesired behavior (storing the return value and
     * then passing it in to {@link #unlinkTracingAndMdcFromCurrentThread(Pair)} later should *guarantee* that after
     * calling that unlink method the thread state is exactly as it was right *before* calling this link method. If we
     * returned the original trace stack this contract guarantee could be violated).
     */
    public static Pair<Deque<Span>, Map<String, String>> linkTracingAndMdcToCurrentThread(ChannelHandlerContext ctx) {
        if (ctx == null)
            return linkTracingAndMdcToCurrentThread(null, null);

        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state == null)
            return linkTracingAndMdcToCurrentThread(null, null);

        return linkTracingAndMdcToCurrentThread(state.getDistributedTraceStack(), state.getLoggerMdcContextMap());
    }

    /**
     * Links the given distributed tracing and logging MDC info to the current thread. Any existing tracing and MDC info
     * on the current thread will be wiped out and overridden, so if you need to go back to them in the future you'll
     * need to store the copy info returned by this method for later.
     *
     * @param threadInfoToLink
     *     A {@link Pair} containing the distributed trace stack and MDC info you want to link to the current thread.
     *     This argument can be null - if it is null then {@link Tracer} will be setup with an empty trace stack (wiping
     *     out any existing in-progress traces) *and* {@link org.slf4j.MDC#clear()} will be called (wiping out any
     *     existing MDC info). The left and/or right portion of the pair can also be null, with any null portion of the
     *     pair causing the corresponding portion to be emptied/cleared while letting any non-null portion link to the
     *     thread as expected.
     *
     * @return A *COPY* of the original trace stack and MDC info on the thread when this method was called (before being
     * replaced with the given arguments). The {@link Pair} object will never be null, but the values it contains may be
     * null. A copy is returned rather than the original to prevent undesired behavior (storing the return value and
     * then passing it in to {@link #unlinkTracingAndMdcFromCurrentThread(Pair)} later should *guarantee* that after
     * calling that unlink method the thread state is exactly as it was right *before* calling this link method. If we
     * returned the original trace stack this contract guarantee could be violated).
     */
    public static Pair<Deque<Span>, Map<String, String>> linkTracingAndMdcToCurrentThread(
        Pair<Deque<Span>, Map<String, String>> threadInfoToLink
    ) {
        Deque<Span> distributedTraceStack = (threadInfoToLink == null) ? null : threadInfoToLink.getLeft();
        Map<String, String> mdcContextMap = (threadInfoToLink == null) ? null : threadInfoToLink.getRight();

        return linkTracingAndMdcToCurrentThread(distributedTraceStack, mdcContextMap);
    }

    /**
     * Links the given distributed tracing and logging MDC info to the current thread. Any existing tracing and MDC info
     * on the current thread will be wiped out and overridden, so if you need to go back to them in the future you'll
     * need to store the copy info returned by this method for later.
     *
     * @param distributedTraceStackToLink
     *     The stack of distributed traces that should be associated with the current thread. This can be null - if it
     *     is null then {@link Tracer} will be setup with an empty trace stack (wiping out any existing in-progress
     *     traces).
     * @param mdcContextMapToLink
     *     The MDC context map to associate with the current thread. This can be null - if it is null then {@link
     *     org.slf4j.MDC#clear()} will be called (wiping out any existing MDC info).
     *
     * @return A *COPY* of the original trace stack and MDC info on the thread when this method was called (before being
     * replaced with the given arguments). The {@link Pair} object will never be null, but the values it contains may be
     * null. A copy is returned rather than the original to prevent undesired behavior (storing the return value and
     * then passing it in to {@link #unlinkTracingAndMdcFromCurrentThread(Pair)} later should *guarantee* that after
     * calling that unlink method the thread state is exactly as it was right *before* calling this link method. If we
     * returned the original trace stack this contract guarantee could be violated).
     */
    public static Pair<Deque<Span>, Map<String, String>> linkTracingAndMdcToCurrentThread(
        Deque<Span> distributedTraceStackToLink, Map<String, String> mdcContextMapToLink
    ) {
        // Unregister the trace stack so that if there's already a trace on the stack we don't get exceptions when
        //      registering the desired stack with the thread, and keep a copy of the results.
        Map<String, String> callingThreadMdcContextMap = MDC.getCopyOfContextMap();
        Deque<Span> callingThreadTraceStack = Tracer.getInstance().unregisterFromThread();

        // Now setup the trace stack and MDC as desired
        if (mdcContextMapToLink == null)
            MDC.clear();
        else
            MDC.setContextMap(mdcContextMapToLink);

        Tracer.getInstance().registerWithThread(distributedTraceStackToLink);

        // Return the copied original data so that it can be re-linked later (if the caller wants)
        return Pair.of(callingThreadTraceStack, callingThreadMdcContextMap);
    }

    /**
     * Helper method for calling {@link #unlinkTracingAndMdcFromCurrentThread(Deque, Map)} that
     * gracefully handles the case where the pair you pass in is null - if the pair you pass in is null then {@link
     * #unlinkTracingAndMdcFromCurrentThread(Deque, Map)} will be called with both arguments null.
     */
    public static void unlinkTracingAndMdcFromCurrentThread(
        Pair<Deque<Span>, Map<String, String>> threadInfoToResetFor) {
        Deque<Span> traceStackToResetFor = (threadInfoToResetFor == null) ? null : threadInfoToResetFor.getLeft();
        Map<String, String> mdcContextMapToResetFor = (threadInfoToResetFor == null)
                                                      ? null
                                                      : threadInfoToResetFor.getRight();

        unlinkTracingAndMdcFromCurrentThread(traceStackToResetFor, mdcContextMapToResetFor);
    }

    /**
     * Calls {@link Tracer#unregisterFromThread()} and {@link org.slf4j.MDC#clear()} to reset this thread's tracing and
     * MDC state to be completely clean, then (optionally) resets the trace stack and MDC info to the arguments
     * provided. If the trace stack argument is null then the trace stack will *not* be reset, and similarly if the MDC
     * info is null then the MDC info will *not* be reset. So if both are null then when this method finishes the trace
     * stack and MDC will be left in a blank state.
     */
    public static void unlinkTracingAndMdcFromCurrentThread(Deque<Span> distributedTraceStackToResetFor,
                                                            Map<String, String> mdcContextMapToResetFor) {
        Tracer.getInstance().unregisterFromThread();
        MDC.clear();

        if (mdcContextMapToResetFor != null)
            MDC.setContextMap(mdcContextMapToResetFor);

        if (distributedTraceStackToResetFor != null)
            Tracer.getInstance().registerWithThread(distributedTraceStackToResetFor);
    }

    /**
     * @param ctx
     *     The {@link ChannelHandlerContext} that holds the current request state.
     *
     * @return Pulls the distributed tracing span stack and MDC information from the {@link HttpProcessingState} stored
     * in the given {@link ChannelHandlerContext}, or null in the case {@code ctx} is null or does not contain a {@link
     * HttpProcessingState}.
     */
    public static Pair<Deque<Span>, Map<String, String>> extractTracingAndMdcInfoFromChannelHandlerContext(
        ChannelHandlerContext ctx) {
        if (ctx == null)
            return null;

        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state == null)
            return null;

        return Pair.of(state.getDistributedTraceStack(), state.getLoggerMdcContextMap());
    }

    /**
     * Executes the given runnable only if {@code ctx.channel().isActive()} returns true. If the channel is not active
     * then a warning is logged, resources are released, the distributed trace is completed (if appropriate), and the
     * runnable is *not* executed. Call this when you're about to do something and it's possible that the channel has
     * been closed. Usually this is only necessary when you're manually firing an event on the given {@code ctx} after
     * some asynchronous delay (e.g. a future completes, or a timeout was scheduled on the event loop, etc).
     *
     * @param ctx
     *     The {@link ChannelHandlerContext} that contains the state for this request.
     * @param markerForLogs
     *     This will be put into the log warning if the channel is not active to help you identify where the problem
     *     occurred. This is usually some arbitrary "ID" representing the code that is calling this method.
     * @param thingToMaybeExecute
     *     This will be executed if the channel is active, and ignored if the channel is not active.
     *
     * @return true if the channel was active and the thingToMaybeExecute was executed, false if the channel was not
     * active and things were cleaned up as per this method description.
     */
    public static boolean executeOnlyIfChannelIsActive(ChannelHandlerContext ctx, String markerForLogs,
                                                       Runnable thingToMaybeExecute) {
        if (ctx.channel().isActive()) {
            // The channel is active, so execute the runnable.
            thingToMaybeExecute.run();
            return true;
        }
        else {
            Pair<Deque<Span>, Map<String, String>> origTracingAndMdcPair = linkTracingAndMdcToCurrentThread(ctx);
            try {
                // The channel is *not* active. Log a warning, release resources,
                //      and complete any existing distributed trace.
                logger.warn(
                    "Unable to continue - channel is no longer active. The client may have closed the connection. "
                    + "Releasing resources and stopping request processing. channel_inactive_cannot_continue_marker={}",
                    markerForLogs
                );

                // Gather the stuff we want to try to release resources for.
                HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
                RequestInfo<?> requestInfo = (state == null) ? null : state.getRequestInfo();
                ProxyRouterProcessingState proxyRouterState =
                    ChannelAttributes.getProxyRouterProcessingStateForChannel(ctx).get();

                // Tell the RequestInfo it can release all its resources.
                if (requestInfo != null)
                    requestInfo.releaseAllResources();

                // Tell the ProxyRouterProcessingState that the stream failed and trigger its chunk streaming error
                //      handling with an artificial exception. If the call had already succeeded previously then this
                //      will do nothing, but if it hasn't already succeeded then it's not going to (since the connection
                //      is closing) and doing this will cause any resources it's holding onto to be released.
                if (proxyRouterState != null) {
                    Throwable reason = new RuntimeException("Cannot execute - Server worker channel closed");
                    proxyRouterState.cancelRequestStreaming(reason, ctx);
                    proxyRouterState.cancelDownstreamRequest(reason);
                }

                // Complete the trace only if there's no state, or if we have a state but the trace hasn't been
                //      completed yet. If the state says the trace has already been completed we don't want to spit it
                //      out a second time.
                if (state == null || !state.isTraceCompletedOrScheduled()) {
                    Tracer.getInstance().completeRequestSpan();
                    // If we have a state then indicate that the span has already been completed.
                    if (state != null)
                        state.setTraceCompletedOrScheduled(true);
                }

                return false;
            }
            finally {
                unlinkTracingAndMdcFromCurrentThread(origTracingAndMdcPair);
            }
        }
    }

    /**
     * Helper method for creating a `CompletableFuture` that is using the tracing helpers.
     * <p>
     * <pre>
     * AsyncNettyHelper.supplyAsync(() -> {
     *      //do some work in a background thread
     *      return VOID;
     * }, executor, ctx);
     * </pre>
     */
    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> f, Executor executor, ChannelHandlerContext ctx) {
        return CompletableFuture.supplyAsync(supplierWithTracingAndMdc(f, ctx), executor);
    }

    /**
     * Helper method for creating a `CompletableFuture` that is using the tracing helpers and has the CompletableFuture
     * wrapped in a `CircuitBreaker`
     * <p>
     * <pre>
     * AsyncNettyHelper.supplyAsync(() -> {
     *      //do some work in a background thread
     *      return VOID;
     * }, circuitBreaker, executor, ctx);
     * </pre>
     */
    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> f, CircuitBreaker<U> circuitBreaker, Executor executor, ChannelHandlerContext ctx) {
        return circuitBreaker.executeAsyncCall(() -> supplyAsync(f, executor, ctx));
    }

    /**
     * Helper method for creating a `CompletableFuture` that is wrapped by a CircuitBreaker and
     * has the `Supplier` wrapped around a SubSpan.
     * <p>
     * You would prefer this method over {@link #supplyAsync(Supplier, Executor, ChannelHandlerContext)} or
     * {@link #supplyAsync(Supplier, CircuitBreaker, Executor, ChannelHandlerContext)}
     * when your `Supplier` has logic that makes an outbound/downstream call. This will net you distributed tracing logs.
     * <p>
     * An example would be using a client SDK that makes blocking HTTP calls.
     * <p>
     * The SubSpan purpose will be set to `CLIENT` as this is the typical use case when utilizing these helpers.
     * <p>
     * <pre>
     * AsyncNettyHelper.supplyAsync("someWorkToBeDone", () -> {
     *      //do some work in a background thread
     *      return VOID;
     * }, circuitBreaker, executor, ctx);
     * </pre>
     */
    public static <U> CompletableFuture<U> supplyAsync(String subSpanName, Supplier<U> f, CircuitBreaker<U> circuitBreaker, Executor executor, ChannelHandlerContext ctx) {
        return circuitBreaker.executeAsyncCall(() -> supplyAsync(subSpanName, f, executor, ctx));
    }

    /**
     * Helper method for creating a `CompletableFuture` that has the `Supplier` wrapped around a SubSpan.
     * <p>
     * You would prefer this method over the above when your `Supplier` has logic that makes an outbound/downstream call
     * and you do not want the use of a `CircuitBreaker`.
     * <p>
     * You would prefer this method over {@link #supplyAsync(String, Supplier, CircuitBreaker, Executor, ChannelHandlerContext)}
     * when your `Supplier` has logic that you would like wrapped with distributed tracing logs and not use a {@link CircuitBreaker}
     * <p>
     * An example would be using a client SDK that makes blocking HTTP calls.
     * <p>
     * The SubSpan purpose will be set to `CLIENT` as this is the typical use case when utilizing these helpers.
     * <p>
     * <pre>
     * AsyncNettyHelper.supplyAsync("someWorkToBeDone", () -> {
     *      //do some work in a background thread
     *      return VOID;
     * }, executor, ctx);
     * </pre>
     */
    public static <U> CompletableFuture<U> supplyAsync(String subSpanName, Supplier<U> f, Executor executor, ChannelHandlerContext ctx) {
        return CompletableFuture.supplyAsync(supplierWithTracingAndMdc(() -> {
            try {
                Tracer.getInstance().startSubSpan(subSpanName, Span.SpanPurpose.CLIENT);
                return f.get();
            } finally {
                Tracer.getInstance().completeSubSpan();
            }
        }, ctx), executor);
    }

}
