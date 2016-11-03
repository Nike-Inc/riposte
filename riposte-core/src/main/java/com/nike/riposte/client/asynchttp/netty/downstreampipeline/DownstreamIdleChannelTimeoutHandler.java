package com.nike.riposte.client.asynchttp.netty.downstreampipeline;

import com.nike.riposte.server.error.exception.DownstreamIdleChannelTimeoutException;
import com.nike.riposte.util.AsyncNettyHelper;
import com.nike.wingtips.Span;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * Handler that detects when a channel used for downstream calls has been idle longer than the given threshold, and
 * throws a {@link DownstreamIdleChannelTimeoutException} when that idle timeout is crossed.
 * <p/>
 * There are two use cases for this handler:
 * <ul>
 *     <li>
 *         Active call timeout detector. In this case {@link #shouldKillIdleChannelNowSupplier} should be set to return
 *         true if the call is still active, false if the call successfully completed. {@link
 *         #isActiveDownstreamCallTimer} should be set to true.
 *     </li>
 *     <li>
 *         Unused channel idle timeout detector. In this case {@link #shouldKillIdleChannelNowSupplier} should always
 *         return true, and {@link #isActiveDownstreamCallTimer} should be set to false.
 *     </li>
 * </ul>
 *
 * @author Nic Munroe
 */
public class DownstreamIdleChannelTimeoutHandler extends IdleStateHandler {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final String customHandlerIdForLogs;
    private final long idleTimeoutMillis;
    private final Supplier<Boolean> shouldKillIdleChannelNowSupplier;
    private final boolean isActiveDownstreamCallTimer;
    private final Deque<Span> traceStackForLogging;
    private final Map<String, String> mdcInfoForLogging;

    private boolean disabled = false;

    /**
     * @param idleTimeoutMillis
     *     The amount of time in millis before {@link #channelIdle(ChannelHandlerContext, IdleStateEvent)} should be
     *     triggered and an exception possibly thrown (depending on the value of running {@code
     *     shouldKillIdleChannelNowSupplier}).
     * @param shouldKillIdleChannelNowSupplier
     *     This will be executed when {@code idleTimeoutMillis} of idle time has passed to determine if this handler
     *     should throw a {@link DownstreamIdleChannelTimeoutException} or not. See the javadocs for this class ({@link
     *     DownstreamIdleChannelTimeoutHandler}) for instructions on how this arg should work for different use cases.
     * @param isActiveDownstreamCallTimer
     *     This determines the log level that will be used when a timeout exception is thrown. If this is true then it
     *     will be logged as an INFO level event. If this is false then it will be DEBUG level.
     * @param customHandlerIdForLogs A custom ID used for the logs.
     * @param traceStackForLogging The trace stack to use when logging.
     * @param mdcInfoForLogging The MDC info to use when logging.
     */
    public DownstreamIdleChannelTimeoutHandler(long idleTimeoutMillis,
                                               Supplier<Boolean> shouldKillIdleChannelNowSupplier,
                                               boolean isActiveDownstreamCallTimer, String customHandlerIdForLogs,
                                               Deque<Span> traceStackForLogging,
                                               Map<String, String> mdcInfoForLogging) {
        super(0, 0, (int) idleTimeoutMillis, TimeUnit.MILLISECONDS);
        this.customHandlerIdForLogs = customHandlerIdForLogs;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.shouldKillIdleChannelNowSupplier = shouldKillIdleChannelNowSupplier;
        this.isActiveDownstreamCallTimer = isActiveDownstreamCallTimer;
        this.traceStackForLogging = traceStackForLogging;
        this.mdcInfoForLogging = mdcInfoForLogging;
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        if (!disabled && shouldKillIdleChannelNowSupplier.get()) {
            String reason = (isActiveDownstreamCallTimer)
                            ? "Throwing call timeout error because the active downstream call took longer than the "
                              + "allowed timeout value."
                            : "Closing downstream channel because it was sitting unused for too long between calls.";

            AsyncNettyHelper.runnableWithTracingAndMdc(
                () -> logger.debug("{} custom_handler_id={}, idle_timeout_millis={}, worker_channel_throwing_error={}",
                                   reason, customHandlerIdForLogs, idleTimeoutMillis, ctx.channel().toString()),
                traceStackForLogging,
                mdcInfoForLogging
            ).run();

            throw new DownstreamIdleChannelTimeoutException(idleTimeoutMillis, ctx.channel());
        }
    }

    /**
     * Call this method to disable the timeout handling functionality immediately (any {@link
     * #channelIdle(ChannelHandlerContext, IdleStateEvent)} events after this method has been called will be ignored).
     * This allows you to effectively "remove" this handler from its pipeline immediately even if you're not in the
     * pipeline channel's event loop.
     */
    public void disableTimeoutHandling() {
        this.disabled = true;
    }

}
