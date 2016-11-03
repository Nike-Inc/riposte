package com.nike.riposte.util.asynchelperwrapper;

import com.nike.internal.util.Pair;
import com.nike.riposte.util.AsyncNettyHelper;
import com.nike.wingtips.Span;

import java.util.Deque;
import java.util.Map;
import java.util.function.Consumer;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import static com.nike.riposte.util.AsyncNettyHelper.linkTracingAndMdcToCurrentThread;
import static com.nike.riposte.util.AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread;

/**
 * Implementation of {@link ChannelFutureListener} that executes the given {@link #postCompleteOperation} {@link
 * Consumer} when the {@link #operationComplete(ChannelFuture)} method is called and associates the given trace and MDC
 * information with the thread for the duration of that {@link Consumer}'s execution. The original thread's tracing and
 * MDC information will be reset after the execution is complete.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class ChannelFutureListenerWithTracingAndMdc implements ChannelFutureListener {

    protected final Consumer<ChannelFuture> postCompleteOperation;
    protected final Deque<Span> distributedTraceStackForExecution;
    protected final Map<String, String> mdcContextMapForExecution;

    /**
     * Contructor that extracts the trace and MDC info from the given {@link ChannelHandlerContext}'s {@link
     * com.nike.riposte.server.http.HttpProcessingState} so that it can be associated with the thread when the given
     * operation is executed.
     * <p/>
     * The operation you pass in cannot be null (an {@link IllegalArgumentException} will be thrown if you pass in null
     * for the operation).
     * <p/>
     * The {@link ChannelHandlerContext} can be null (or the state inside can be null) but if you pass null it means
     * there won't be any trace or MDC info associated with the thread when the given operation is executed.
     */
    public ChannelFutureListenerWithTracingAndMdc(Consumer<ChannelFuture> postCompleteOperation,
                                                  ChannelHandlerContext ctx) {
        this(postCompleteOperation, AsyncNettyHelper.extractTracingAndMdcInfoFromChannelHandlerContext(ctx));
    }

    /**
     * Constructor that uses the given trace and MDC information, which will be associated with the thread when the
     * given operation is executed.
     * <p/>
     * The operation you pass in cannot be null (an {@link IllegalArgumentException} will be thrown if you pass in null
     * for the operation).
     * <p/>
     * The {@link Pair} can be null, or you can pass null for the left and/or right side of the pair, and no error will
     * be thrown. Any trace or MDC info that is null means the corresponding info will not be available to the thread
     * when the operation is executed however.
     */
    public ChannelFutureListenerWithTracingAndMdc(Consumer<ChannelFuture> postCompleteOperation,
                                                  Pair<Deque<Span>, Map<String, String>> originalThreadInfo) {
        this(
            postCompleteOperation,
            (originalThreadInfo == null) ? null : originalThreadInfo.getLeft(),
            (originalThreadInfo == null) ? null : originalThreadInfo.getRight()
        );
    }

    /**
     * Constructor that uses the given trace and MDC information, which will be associated with the thread when the
     * given operation is executed.
     * <p/>
     * The operation you pass in cannot be null (an {@link IllegalArgumentException} will be thrown if you pass in null
     * for the operation).
     * <p/>
     * The trace and/or MDC info can be null and no error will be thrown, however any trace or MDC info that is null
     * means the corresponding info will not be available to the thread when the operation is executed.
     */
    public ChannelFutureListenerWithTracingAndMdc(Consumer<ChannelFuture> postCompleteOperation,
                                                  Deque<Span> distributedTraceStackForExecution,
                                                  Map<String, String> mdcContextMapForExecution) {
        if (postCompleteOperation == null)
            throw new IllegalArgumentException("postCompleteOperation cannot be null");

        this.postCompleteOperation = postCompleteOperation;
        this.distributedTraceStackForExecution = distributedTraceStackForExecution;
        this.mdcContextMapForExecution = mdcContextMapForExecution;
    }

    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
        Pair<Deque<Span>, Map<String, String>> originalThreadInfo = null;
        try {
            originalThreadInfo =
                linkTracingAndMdcToCurrentThread(distributedTraceStackForExecution, mdcContextMapForExecution);

            postCompleteOperation.accept(future);
        }
        finally {
            unlinkTracingAndMdcFromCurrentThread(originalThreadInfo);
        }
    }
}
