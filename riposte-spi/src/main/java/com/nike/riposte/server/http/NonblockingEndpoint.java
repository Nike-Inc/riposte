package com.nike.riposte.server.http;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.netty.channel.ChannelHandlerContext;

/**
 * Represents a non-blocking resource endpoint.
 * <p/>
 * <b>Since this is non-blocking it will be initially executed on a Netty worker I/O thread - DO *NOT* perform any
 * blocking actions on this thread (sleeping, calling downstream services, making a DB query, expensive calculations,
 * etc) or you will likely cripple your application's throughput! Instead, make sure that all blocking and/or expensive
 * actions are done in the returned {@link CompletableFuture}'s async functions. See {@link #execute(RequestInfo,
 * Executor, ChannelHandlerContext)} for more details. </b>
 *
 * @author Nic Munroe
 */
public interface NonblockingEndpoint<I, O> extends Endpoint<I> {

    /**
     * This is where the rubber hits the road - this is where the logic for the endpoint goes.
     *
     * <hr/> <h2>HOW TO AUTOMATICALLY DESERIALIZE THE REQUEST BODY'S CONTENT AND HAVE IT (OPTIONALLY) AUTOMATICALLY
     * VALIDATED</h2>
     *
     * If you want the incoming request's {@link RequestInfo#getContent()} to be populated then make sure you return an
     * appropriate non-null value in {@link #requestContentType()} (if you don't then {@link
     * RequestInfo#getRawContent()} will still be available for you to use). Note that by default {@code
     * StandardEndpoint} automagically determines an appropriate return value for {@link #requestContentType()} so most
     * of the time it will just work and you don't need to override that method - but if you need to you can.
     * <p/>
     * Additionally, if you setup the request's {@link RequestInfo#getContent()} to be populated you can have it
     * automatically validated for you before this method is called by making sure {@link
     * #isValidateRequestContent(RequestInfo)} returns true (it returns true by default).
     *
     * <hr/> <h2>HOW TO CONSTRUCT A {@link CompletableFuture} IN A WAY THAT WON'T BREAK YOUR APPLICATION</h2>
     *
     * Since this endpoint is non-blocking this method will initially be called on a Netty worker I/O thread, so you
     * should *NEVER* perform any blocking actions on this thread (sleeping, calling downstream services, making a DB
     * query, expensive calculations, etc).
     * <p/>
     * Ideally you would use an async driver for any downstream database or HTTP calls, and when the async driver call
     * finishes it calls {@link CompletableFuture#complete(Object)} or {@link CompletableFuture#completeExceptionally(Throwable)}
     * to complete the {@link CompletableFuture} you return from this method. If you are able to stick to this pattern
     * and have no expensive computation that needs to be performed in the application itself then your thread count
     * will never grow no matter how much concurrent traffic goes through the endpoint.
     * <p/>
     * Otherwise, if there is no async driver for your downstream calls or you have expensive computations you need to
     * perform in the endpoint itself then all blocking/expensive actions should be done in an async portion of the
     * returned {@link CompletableFuture}. Use the various factory methods to create and compose your {@link
     * CompletableFuture}, but <b>most of the time you should use the versions of the {@link CompletableFuture} methods
     * that let you pass in an {@link Executor} for performing the action, and pass in the {@code
     * longRunningTaskExecutor} argument.</b> Why? By default {@link CompletableFuture} will use {@link
     * java.util.concurrent.ForkJoinPool#commonPool()}, which is (1) limited to a handful of threads (not sufficient for
     * high throughput HTTP request handling), and (2) is a work-stealing pool that queues up tasks (not good if tasks
     * get stuck behind slow blocking ones in the queue). So for an application to support high volumes of HTTP requests
     * this common forkjoin pool is not sufficient - you would run out of threads very quickly and see your throughput
     * and performance drop through the floor except in the rare cases where the async tasks are short enough to not
     * clog up the forkjoin pool, but long enough that you need to do an async operation rather than returning a trivial
     * {@link CompletableFuture#completedFuture(Object)}.
     *
     * <p/>
     * Therefore this {@code execute(...)} method is passed the {@code longRunningTaskExecutor} which should be used
     * instead of the common forkjoin pool (in most cases), and one main drawback is that you have to remember to tell
     * your {@link CompletableFuture} that you want to use it - e.g. use {@link CompletableFuture#supplyAsync(Supplier,
     * Executor)}, *NOT* {@link CompletableFuture#supplyAsync(Supplier)}. If you fail to do this and your endpoints use
     * the common forkjoin pool or you perform blocking actions outside of the {@link CompletableFuture} then you'll get
     * miserable application performance at higher loads. The other main drawback is that your application's thread
     * count will grow based on the number of concurrent executions of the endpoint. This may be unavoidable, especially
     * if you have expensive computations to do in the endpoint (the work has to be done *somewhere* and you can't do it
     * on the Netty I/O thread), but it's something to keep in mind. If you can break the work up into small chunks and
     * have the ForkJoin pool do the work over time then that may be a better solution. Or you may be able to come up
     * with some other creative solution; there's no limitation on your creativity to keep the thread count low, the
     * only limitation is that the object returned by this method is a {@link CompletableFuture} that is eventually
     * completed *somehow*.
     *
     * <hr/> <h2>HOW TO HAVE YOUR ASYNC {@link CompletableFuture} SUPPORT THE DISTRIBUTED TRACING AND LOGGING MDC
     * ASSOCIATED WITH THE REQUEST</h2>
     *
     * Once the {@link CompletableFuture}'s async tasks start they will be on one or more different threads that are
     * missing the distributed tracing and logging MDC values associated with the request. In order to have the tracing
     * and MDC info "hop" threads we've provided some helpers in {@code com.nike.riposte.util.AsyncNettyHelper} that you
     * can use in conjunction with the {@link CompletableFuture}'s methods to provide the {@link CompletableFuture} with
     * {@link java.lang.Runnable}s, {@link Supplier}s, {@link java.util.function.Function}s, and {@link
     * java.util.function.Consumer}s that know how to make the tracing and MDC info hop threads correctly. For example,
     * if you wanted to use the {@link CompletableFuture#supplyAsync(Supplier, Executor)} method, then create your
     * supplier using {@code com.nike.riposte.util.AsyncNettyHelper#supplierWithTracingAndMdc(java.util.function.Supplier,
     * ChannelHandlerContext)} (passing in the raw Supplier that performs the async work along with the {@link
     * ChannelHandlerContext} ctx argument given to you by this {@code execute(...)} method). That Supplier-wrapper
     * returned by {@code com.nike.riposte.util.AsyncNettyHelper} will register the correct tracing and MDC info with
     * the thread before performing the async action of the original Supplier, and will unregister them when the async
     * action is done.
     * <p/>
     * The {@link ChannelHandlerContext} ctx argument is usually only necessary for use with {@code
     * com.nike.riposte.util.AsyncNettyHelper} to create tracing and MDC supported async processing objects, but it's
     * also here in case you need to access the {@code
     * com.nike.riposte.server.channelpipeline.ChannelAttributes#getHttpProcessingStateForChannel(ChannelHandlerContext)}
     * state for the channel, schedule a job to be performed at some arbitrary time in the future, or do some other
     * pipeline manipulation. Just make sure you know *EXACTLY* what you're doing when you use it, otherwise you could
     * break your application in subtle ways without even realizing it.
     */
    CompletableFuture<ResponseInfo<O>> execute(RequestInfo<I> request, Executor longRunningTaskExecutor,
                                               ChannelHandlerContext ctx);

    /**
     * @param request
     *     The request that was passed into the execute method.
     * @param ctx
     *     The channel handler context that was passed into the execute method.
     *
     * @return The error to throw when the {@link java.util.concurrent.CompletableFuture} times out, or null if you want
     * to use the default {@link com.nike.riposte.server.error.exception.NonblockingEndpointCompletableFutureTimedOut}.
     * Most of the time you can just return null, but if you want to throw (for example) a custom error that contains
     * special info based on the request you can do so.
     */
    @SuppressWarnings("UnusedParameters")
    default Throwable getCustomTimeoutExceptionCause(RequestInfo<I> request, ChannelHandlerContext ctx) {
        return null;
    }
}
