package com.nike.riposte.client.asynchttp.netty;

import com.nike.backstopper.exception.WrapperException;
import com.nike.internal.util.Pair;
import com.nike.riposte.client.asynchttp.netty.downstreampipeline.DownstreamIdleChannelTimeoutHandler;
import com.nike.riposte.server.error.exception.DownstreamChannelClosedUnexpectedlyException;
import com.nike.riposte.server.error.exception.DownstreamIdleChannelTimeoutException;
import com.nike.riposte.server.error.exception.HostnameResolutionException;
import com.nike.riposte.server.error.exception.NativeIoExceptionWrapper;
import com.nike.wingtips.Span;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.AbstractChannelPoolHandler;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolMap;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.Errors;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpObjectEncoder;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import static com.nike.riposte.util.AsyncNettyHelper.linkTracingAndMdcToCurrentThread;
import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;
import static com.nike.riposte.util.AsyncNettyHelper.unlinkTracingAndMdcFromCurrentThread;

/**
 * TODO: Class Description
 *
 * <p>NOTE: This class is one of those proof-of-concepts that morphed into production code through a slow series of
 * feature additions, bandaids, and duct tape. It is fairly complex and difficult to understand and follow. It will
 * be replaced at some point with a generic pure-Netty async nonblocking HTTP client that allows for streaming
 * request and response data. Until then this code is actually fairly well battle tested and proven reliable in high
 * throughput production edgerouter and domain router type scenarios.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class StreamingAsyncHttpClient {

    public static final String DOWNSTREAM_CLIENT_CHANNEL_DEBUG_LOGGER_NAME = "DownstreamClientChannelDebugLogger";

    public static final String SSL_HANDLER_NAME = "sslHandler";
    public static final String HTTP_CLIENT_CODEC_HANDLER_NAME = "httpClientCodec";
    public static final String CHUNK_SENDER_HANDLER_NAME = "chunkSender";
    public static final String ERROR_HANDLER_NAME = "errorHandler";
    public static final String DOWNSTREAM_IDLE_CHANNEL_TIMEOUT_HANDLER_NAME = "idleDownstreamChannelTimeoutHandler";
    public static final String DOWNSTREAM_CALL_TIMEOUT_HANDLER_NAME = "downstreamCallTimeoutHandler";
    public static final String DEBUG_LOGGER_HANDLER_NAME = "debugLoggerHandler";
    private static final Logger logger = LoggerFactory.getLogger(StreamingAsyncHttpClient.class);
    private volatile ChannelPoolMap<InetSocketAddress, SimpleChannelPool> poolMap;
    private SslContext clientSslCtx;
    private final boolean debugChannelLifecycleLoggingEnabled;
    private final long idleChannelTimeoutMillis;
    private final int downstreamConnectionTimeoutMillis;
    protected static final AttributeKey<Boolean> CHANNEL_IS_BROKEN_ATTR = AttributeKey.newInstance("channelIsBroken");
    private final ProxyRouterChannelHealthChecker CHANNEL_HEALTH_CHECK_INSTANCE = new ProxyRouterChannelHealthChecker();
    public final static String SHOULD_LOG_BAD_MESSAGES_AFTER_REQUEST_FINISHES_SYSTEM_PROP_KEY =
        "StreamingAsyncHttpClient.debug.shouldLogBadMessagesAfterRequestFinishes";
    private final boolean shouldLogBadMessagesAfterRequestFinishes = "true".equalsIgnoreCase(
            System.getProperty(SHOULD_LOG_BAD_MESSAGES_AFTER_REQUEST_FINISHES_SYSTEM_PROP_KEY, "false").trim()
    );

    private final Random randomGenerator = new Random();

    public StreamingAsyncHttpClient(long idleChannelTimeoutMillis, long downstreamConnectionTimeoutMillis,
                                    boolean debugChannelLifecycleLoggingEnabled) {
        this.idleChannelTimeoutMillis = idleChannelTimeoutMillis;
        this.downstreamConnectionTimeoutMillis = Math.toIntExact(downstreamConnectionTimeoutMillis);
        this.debugChannelLifecycleLoggingEnabled = debugChannelLifecycleLoggingEnabled;
    }

    public static class StreamingChannel {

        protected final Channel channel;
        protected final ChannelPool pool;
        protected final ObjectHolder<Boolean> callActiveHolder;
        protected final ObjectHolder<Boolean> downstreamLastChunkSentHolder;
        protected final Deque<Span> distributedTracingSpanStack;
        protected final Map<String, String> distributedTracingMdcInfo;
        protected boolean channelClosedDueToUnrecoverableError = false;
        private boolean alreadyLoggedMessageAboutIgnoringCloseDueToError = false;

        StreamingChannel(Channel channel,
                         ChannelPool pool,
                         ObjectHolder<Boolean> callActiveHolder,
                         ObjectHolder<Boolean> downstreamLastChunkSentHolder,
                         Deque<Span> distributedTracingSpanStack,
                         Map<String, String> distributedTracingMdcInfo) {
            this.channel = channel;
            this.pool = pool;
            this.callActiveHolder = callActiveHolder;
            this.downstreamLastChunkSentHolder = downstreamLastChunkSentHolder;
            this.distributedTracingSpanStack = distributedTracingSpanStack;
            this.distributedTracingMdcInfo = distributedTracingMdcInfo;
        }

        /**
         * Calls {@link Channel#writeAndFlush(Object)} to pass the given chunk to the downstream system. Note that
         * the flush will cause the reference count of the given chunk to decrease by 1. If any error occurs that
         * prevents the {@link Channel#writeAndFlush(Object)} call from executing (e.g. {@link
         * #channelClosedDueToUnrecoverableError} was called previously) then {@link HttpContent#release()} will
         * be called manually so that you can rely on the given chunk's reference count always being reduced by 1
         * at some point after calling this method.
         *
         * @param chunkToWrite The chunk to send downstream.
         * @return The {@link ChannelFuture} that will tell you if the write succeeded.
         */
        public ChannelFuture streamChunk(HttpContent chunkToWrite) {
            try {
                ChannelPromise result = channel.newPromise();

                channel.eventLoop().execute(
                    () -> doStreamChunk(chunkToWrite).addListener(future -> {
                        if (future.isCancelled()) {
                            result.cancel(true);
                        }
                        else if (future.isSuccess()) {
                            result.setSuccess();
                        }
                        else if (future.cause() != null) {
                            result.setFailure(future.cause());
                        }
                        else {
                            runnableWithTracingAndMdc(
                                () -> logger.error(
                                    "Found a future with no result. This should not be possible. Failing the future. "
                                    + "future_done={}, future_success={}, future_cancelled={}, future_failure_cause={}",
                                    future.isDone(), future.isSuccess(), future.isCancelled(), future.cause()
                                ),
                                distributedTracingSpanStack, distributedTracingMdcInfo
                            ).run();
                            result.setFailure(
                                new RuntimeException("Received ChannelFuture that was in an impossible state")
                            );
                        }
                    })
                );

                return result;
            }
            catch(Throwable t) {
                String errorMsg =
                    "StreamingChannel.streamChunk() threw an exception. This should not be possible and indicates "
                    + "something went wrong with a Netty write() and flush(). If you see Netty memory leak warnings "
                    + "then this could be why. Please report this along with the stack trace to "
                    + "https://github.com/Nike-Inc/riposte/issues/new";

                Exception exceptionToPass = new RuntimeException(errorMsg, t);

                logger.error(errorMsg, exceptionToPass);
                return channel.newFailedFuture(exceptionToPass);
            }
        }

        protected ChannelFuture doStreamChunk(HttpContent chunkToWrite) {
            // We are in the channel's event loop. Do some final checks to make sure it's still ok to write and flush
            //      the message, then do it (or handle the special cases appropriately).
            try {
                if (downstreamLastChunkSentHolder.heldObject
                    && (chunkToWrite instanceof LastHttpContent)
                    && chunkToWrite.content().readableBytes() == 0
                    ) {
                    // A LastHttpContent has already been written downstream. This is valid/legal when the downstream call
                    //      has a content-length header, and the downstream pipeline encoder realizes there's no more
                    //      content to write and generates a synthetic empty LastHttpContent rather than waiting for this
                    //      one to arrive. Therefore there's nothing to do but release the content chunk and return an
                    //      already-successfully-completed future.
                    if (logger.isDebugEnabled()) {
                        runnableWithTracingAndMdc(
                            () -> logger.warn("Ignoring zero-length LastHttpContent from upstream, because a "
                                              + "LastHttpContent has already been sent downstream."),
                            distributedTracingSpanStack, distributedTracingMdcInfo
                        ).run();
                    }
                    chunkToWrite.release();
                    return channel.newSucceededFuture();
                }

                if (!callActiveHolder.heldObject) {
                    chunkToWrite.release();
                    return channel.newFailedFuture(
                        new RuntimeException("Unable to stream chunk - downstream call is no longer active.")
                    );
                }

                if (channelClosedDueToUnrecoverableError) {
                    chunkToWrite.release();
                    return channel.newFailedFuture(
                        new RuntimeException("Unable to stream chunks downstream - the channel was closed previously "
                                             + "due to an unrecoverable error")
                    );
                }

                return channel.writeAndFlush(chunkToWrite);
            }
            catch(Throwable t) {
                String errorMsg =
                    "StreamingChannel.doStreamChunk() threw an exception. This should not be possible and indicates "
                    + "something went wrong with a Netty write() and flush(). If you see Netty memory leak warnings "
                    + "then this could be why. Please report this along with the stack trace to "
                    + "https://github.com/Nike-Inc/riposte/issues/new";

                Exception exceptionToPass = new RuntimeException(errorMsg, t);

                logger.error(errorMsg, exceptionToPass);
                return channel.newFailedFuture(exceptionToPass);
            }
        }

        private static final Logger logger = LoggerFactory.getLogger(StreamingChannel.class);

        public Channel getChannel() {
            return channel;
        }

        public boolean isDownstreamCallActive() {
            return callActiveHolder.heldObject;
        }

        public void closeChannelDueToUnrecoverableError(Throwable cause) {
            try {
                // Ignore subsequent calls to this method, and only try to do something if the call is still active.
                //      If the call is *not* active, then everything has already been cleaned up and we shouldn't
                //      do anything because the channel might have already been handed out for a different call.
                if (!channelClosedDueToUnrecoverableError && callActiveHolder.heldObject) {
                    // Schedule the close on the channel's event loop.
                    channel.eventLoop().execute(() -> doCloseChannelDueToUnrecoverableError(cause));
                    return;
                }

                if (!alreadyLoggedMessageAboutIgnoringCloseDueToError && logger.isDebugEnabled()) {
                    runnableWithTracingAndMdc(
                        () -> logger.debug(
                            "Ignoring calls to StreamingChannel.closeChannelDueToUnrecoverableError() because it "
                            + "has already been called, or the call is no longer active. "
                            + "previously_called={}, call_is_active={}",
                            channelClosedDueToUnrecoverableError, callActiveHolder.heldObject
                        ),
                        distributedTracingSpanStack, distributedTracingMdcInfo
                    ).run();
                }

                alreadyLoggedMessageAboutIgnoringCloseDueToError = true;
            }
            finally {
                channelClosedDueToUnrecoverableError = true;
            }
        }

        protected void doCloseChannelDueToUnrecoverableError(Throwable cause) {
            // We should now be in the channel's event loop. Do a final check to make sure the call is still active
            //      since it could have closed while we were waiting to run on the event loop.
            if (callActiveHolder.heldObject) {
                runnableWithTracingAndMdc(
                    () -> logger.error("Closing StreamingChannel due to unrecoverable error. "
                                       + "channel_id={}, unrecoverable_error={}",
                                       channel.toString(), String.valueOf(cause)
                    ),
                    distributedTracingSpanStack, distributedTracingMdcInfo
                ).run();

                // Mark the channel as broken so it will be closed and removed from the pool when it is returned.
                markChannelAsBroken(channel);

                // Release it back to the pool if possible/necessary so the pool can do its usual cleanup.
                releaseChannelBackToPoolIfCallIsActive(
                    channel, pool, callActiveHolder,
                    "closing StreamingChannel due to unrecoverable error: " + String.valueOf(cause),
                    distributedTracingSpanStack, distributedTracingMdcInfo
                );

                // No matter what the cause is we want to make sure the channel is closed.
                channel.close();
            }
            else {
                logger.debug("The call deactivated before we could close the StreamingChannel. Therefore there's "
                             + "nothing to do for this unrecoverable error as any necessary cleanup has already been "
                             + "done. ignored_unrecoverable_error={}", cause.toString());
            }
        }
    }

    public interface StreamingCallback {

        void messageReceived(HttpObject msg);

        void unrecoverableErrorOccurred(Throwable error, boolean guaranteesBrokenDownstreamResponse);

        void cancelStreamingToOriginalCaller();
    }

    /**
     * Returns an {@link InetSocketAddress} for the given hostname and port - if the DNS for the hostname has multiple
     * IP addresses associated with it then the returned IP address will be randomly chosen from the available IPs.
     * <p/>
     * This is necessary to properly distribute traffic among all the IPs rather than firehosing a single one. For
     * example, Amazon ELBs function by associating multiple IPs with the ELB's DNS. Without this method all traffic
     * would pipe to only one of an ELB's IPs, and since ELBs scale up based on aggregate traffic over all IPs,
     * firehosing one IP means the ELB would never scale up even though it's being overloaded.
     */
    protected InetSocketAddress resolveHostnameToInetSocketAddressWithMultiIpSupport(String hostname, int port) {
        try {
            InetAddress[] ipAddresses = InetAddress.getAllByName(hostname);
            int numAddresses = ipAddresses.length;
            InetAddress address = (numAddresses == 1)
                                  ? ipAddresses[0]
                                  : ipAddresses[randomGenerator.nextInt(numAddresses)];
            return new InetSocketAddress(address, port);
        }
        catch (UnknownHostException e) {
            throw new HostnameResolutionException(
                "Unable to resolve hostname into IP address(es). hostname=" + hostname, e
            );
        }
    }

    protected Bootstrap generateClientBootstrap(EventLoopGroup eventLoopGroup,
                                                Class<? extends SocketChannel> channelClass) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup).channel(channelClass);
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, downstreamConnectionTimeoutMillis);
        return bootstrap;
    }

    protected ThreadFactory createProxyRouterThreadFactory() {
        return new DefaultThreadFactory("proxyRouterEventLoopGroup", false, Thread.NORM_PRIORITY);
    }

    protected ChannelPoolMap<InetSocketAddress, SimpleChannelPool> getPoolMap() {
        ChannelPoolMap<InetSocketAddress, SimpleChannelPool> result = poolMap;
        if (poolMap == null) {
            /*
                This method gets called for every downstream call, so we don't want to synchronize the whole method. But
                it's easy for multiple threads to get here at the same time when the server starts up, so we need *some*
                kind of protection around the creation of poolMap, hence the elaborate (but correct) double-checked
                locking. Since poolMap is volatile this works, and the local variable "result" helps with speed during
                the normal case where poolMap has already been initialized.
                See https://en.wikipedia.org/wiki/Double-checked_locking
             */
            synchronized (this) {
                result = poolMap;
                if (result == null) {
                    EventLoopGroup eventLoopGroup;
                    Class<? extends SocketChannel> channelClass;
                    if (Epoll.isAvailable()) {
                        logger.info(
                            "Creating channel pool. The epoll native transport is available. Using epoll instead of "
                            + "NIO. proxy_router_using_native_epoll_transport=true"
                        );
                        eventLoopGroup = new EpollEventLoopGroup(0, createProxyRouterThreadFactory());
                        channelClass = EpollSocketChannel.class;
                    }
                    else {
                        logger.info(
                            "Creating channel pool. The epoll native transport is NOT available or you are not running "
                            + "on a compatible OS/architecture. Using NIO. "
                            + "proxy_router_using_native_epoll_transport=false"
                        );
                        eventLoopGroup = new NioEventLoopGroup(0, createProxyRouterThreadFactory());
                        channelClass = NioSocketChannel.class;
                    }

                    result = new AbstractChannelPoolMap<InetSocketAddress, SimpleChannelPool>() {
                        @Override
                        protected SimpleChannelPool newPool(InetSocketAddress key) {
                            return new SimpleChannelPool(
                                generateClientBootstrap(eventLoopGroup, channelClass).remoteAddress(key),
                                new ChannelPoolHandlerImpl(),
                                CHANNEL_HEALTH_CHECK_INSTANCE
                            ) {
                                @Override
                                public Future<Void> release(Channel channel, Promise<Void> promise) {
                                    markChannelBrokenAndLogInfoIfHttpClientCodecStateIsNotZero(
                                        channel, "Releasing channel back to pool"
                                    );
                                    return super.release(channel, promise);
                                }

                                @Override
                                protected Channel pollChannel() {
                                    Channel channel = super.pollChannel();

                                    if (channel != null) {
                                        markChannelBrokenAndLogInfoIfHttpClientCodecStateIsNotZero(
                                            channel, "Polling channel to be reused before healthcheck"
                                        );

                                        if (idleChannelTimeoutMillis > 0) {
                                            /*
                                             We have a channel that is about to be re-used, so disable the idle channel
                                             timeout detector if it exists. By disabling it here we make sure that it is
                                             effectively "gone" before the healthcheck happens, preventing race
                                             conditions. Note that we can't call pipeline.remove() here because we may
                                             not be in the pipeline's event loop, so calling pipeline.remove() could
                                             lead to thread deadlock, but we can't call channel.eventLoop().execute()
                                             because we need it disabled *now* before the healthcheck happens. The
                                             pipeline preparation phase will remove it safely soon, and in the meantime
                                             it will be disabled.
                                             */
                                            ChannelPipeline pipeline = channel.pipeline();
                                            ChannelHandler idleHandler =
                                                pipeline.get(DOWNSTREAM_IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
                                            if (idleHandler != null) {
                                                ((DownstreamIdleChannelTimeoutHandler) idleHandler)
                                                    .disableTimeoutHandling();
                                            }
                                        }
                                    }

                                    return channel;
                                }

                                @Override
                                protected boolean offerChannel(Channel channel) {
                                    if (idleChannelTimeoutMillis > 0) {
                                        // Add an idle channel timeout detector. This will be removed before the
                                        //      channel's reacquisition healthcheck runs (in pollChannel()), so we won't
                                        //      have a race condition where this channel is handed over for use but gets
                                        //      squashed right before it's about to be used.
                                        // NOTE: Due to the semantics of pool.release() we're guaranteed to be in the
                                        //      channel's event loop, so there's no chance of a thread deadlock when
                                        //      messing with the pipeline.
                                        channel.pipeline().addFirst(
                                            DOWNSTREAM_IDLE_CHANNEL_TIMEOUT_HANDLER_NAME,
                                            new DownstreamIdleChannelTimeoutHandler(
                                                idleChannelTimeoutMillis, () -> true, false,
                                                "StreamingAsyncHttpClientChannel-idle", null, null)
                                        );
                                    }

                                    return super.offerChannel(channel);
                                }
                            };
                        }
                    };
                    poolMap = result;
                }
            }
        }
        return result;
    }

    protected ChannelPool getPooledChannelFuture(String downstreamHost, int downstreamPort) {
        return getPoolMap().get(
            resolveHostnameToInetSocketAddressWithMultiIpSupport(downstreamHost, downstreamPort)
        );
    }

    protected static class ChannelPoolHandlerImpl extends AbstractChannelPoolHandler {
        @Override
        public void channelCreated(Channel ch) throws Exception {
            // NOOP
        }
    }

    protected static class ProxyRouterChannelHealthChecker implements ChannelHealthChecker {
        @Override
        public Future<Boolean> isHealthy(Channel channel) {
            // See if we've marked the channel as being non-usable first.
            if (channelIsMarkedAsBeingBroken(channel))
                return channel.eventLoop().newSucceededFuture(Boolean.FALSE);

            // We haven't marked it broken, so fallback to the default channel health checker.
            return ChannelHealthChecker.ACTIVE.isHealthy(channel);
        }
    }

    protected static void markChannelAsBroken(Channel ch) {
        ch.attr(CHANNEL_IS_BROKEN_ATTR).set(true);
    }

    protected static boolean channelIsMarkedAsBeingBroken(Channel ch) {
        Attribute<Boolean> brokenAttr = ch.attr(CHANNEL_IS_BROKEN_ATTR);
        return Boolean.TRUE.equals(brokenAttr.get());
    }

    protected void logInitialRequestChunk(HttpRequest initialRequestChunk, String downstreamHost, int downstreamPort) {
        if (logger.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            for (String headerName : initialRequestChunk.headers().names()) {
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append(headerName).append("=\"")
                  .append(String.join(",", initialRequestChunk.headers().getAll(headerName))).append("\"");
            }

            logger.debug("STREAMING REQUEST HEADERS: " + sb.toString());
            logger.debug("STREAMING REQUEST HOST:PORT/PATH: " + downstreamHost + ":" + downstreamPort
                         + initialRequestChunk.getUri());
            logger.debug("STREAMING REQUEST METHOD: " + initialRequestChunk.getMethod().name());
            logger.debug("STREAMING REQUEST PROTOCOL: " + initialRequestChunk.getProtocolVersion().protocolName());
        }
    }

    /**
     * TODO: Fully document me.
     * <br/>
     * NOTE: The returned CompletableFuture will only be completed successfully if the connection to the downstream
     * server was successful and the initialRequestChunk was successfully written out. This has implications for
     * initialRequestChunk regarding releasing its reference count (i.e. calling {@link
     * io.netty.util.ReferenceCountUtil#release(Object)} and passing in initialRequestChunk). If the returned
     * CompletableFuture is successful it means initialRequestChunk's reference count will already be reduced by one
     * relative to when this method was called because it will have been passed to a successful {@link
     * ChannelHandlerContext#writeAndFlush(Object)} method call.
     * <p/>
     * Long story short - assume initialRequestChunk is an object with a reference count of x:
     * <ul>
     *     <li>
     *         If the returned CompletableFuture is successful, then when it completes successfully
     *         initialRequestChunk's reference count will be x - 1
     *     </li>
     *     <li>
     *         If the returned CompletableFuture is *NOT* successful, then when it completes initialRequestChunk's
     *         reference count will still be x
     *     </li>
     * </ul>
     */
    public CompletableFuture<StreamingChannel> streamDownstreamCall(
        String downstreamHost, int downstreamPort, HttpRequest initialRequestChunk, boolean isSecureHttpsCall,
        boolean relaxedHttpsValidation, StreamingCallback callback, long downstreamCallTimeoutMillis,
        boolean performSubSpanAroundDownstreamCalls,
        ChannelHandlerContext ctx
    ) {
        CompletableFuture<StreamingChannel> streamingChannel = new CompletableFuture<>();

        // set host header. include port in value when it is a non-default port
        boolean isDefaultPort = (downstreamPort == 80 && !isSecureHttpsCall)
                             || (downstreamPort == 443 && isSecureHttpsCall);
        String hostHeaderValue = (isDefaultPort)
                                 ? downstreamHost
                                 : downstreamHost + ":" + downstreamPort;
        initialRequestChunk.headers().set(HttpHeaders.Names.HOST, hostHeaderValue);

        ObjectHolder<Long> beforeConnectionStartTimeNanos = new ObjectHolder<>();
        beforeConnectionStartTimeNanos.heldObject = System.nanoTime();

        // Create a connection to the downstream server.
        ChannelPool pool = getPooledChannelFuture(downstreamHost, downstreamPort);
        Future<Channel> channelFuture = pool.acquire();
        // Add a listener that kicks off the downstream call once the connection is completed.
        channelFuture.addListener(future -> {
            Pair<Deque<Span>, Map<String, String>> originalThreadInfo = null;
            try {
                // Setup tracing and MDC so our log messages have the correct distributed trace info, etc.
                originalThreadInfo = linkTracingAndMdcToCurrentThread(ctx);

                if (!future.isSuccess()) {
                    try {
                        // We did not connect to the downstream host successfully. Notify the callback.
                        streamingChannel.completeExceptionally(
                            new WrapperException("Unable to connect to downstream host: " + downstreamHost,
                                                 future.cause())
                        );
                    }
                    finally {
                        Channel ch = channelFuture.getNow();
                        if (ch != null) {
                            // We likely will never reach here since the channel future was not successful, however if
                            //      we *do* manage to get here somehow, then mark the channel broken and release it back
                            //      to the pool.
                            markChannelAsBroken(ch);
                            pool.release(ch);
                        }
                    }

                    return;
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("CONNECTION SETUP TIME NANOS: {}",
                                 (System.nanoTime() - beforeConnectionStartTimeNanos.heldObject)
                    );
                }

                // Do a subspan around the downstream call if desired.
                //noinspection ConstantConditions
                if (performSubSpanAroundDownstreamCalls) {
                    // Add the subspan.
                    String spanName = getSubspanSpanName(
                        initialRequestChunk.getMethod().name(),
                        downstreamHost + ":" + downstreamPort + initialRequestChunk.getUri()
                    );
                    if (Tracer.getInstance().getCurrentSpan() == null) {
                        // There is no parent span to start a subspan from, so we have to start a new span for this call
                        //      rather than a subspan.
                        // TODO: Set this to CLIENT once we have that ability in the wingtips API for request root spans
                        Tracer.getInstance().startRequestWithRootSpan(spanName);
                    }
                    else {
                        // There was at least one span on the stack, so we can start a subspan for this call.
                        Tracer.getInstance().startSubSpan(spanName, Span.SpanPurpose.CLIENT);
                    }
                }

                Deque<Span> distributedSpanStackToUse = Tracer.getInstance().getCurrentSpanStackCopy();
                Map<String, String> mdcContextToUse = MDC.getCopyOfContextMap();

                Span spanForDownstreamCall = (distributedSpanStackToUse == null)
                                             ? null
                                             : distributedSpanStackToUse.peek();

                // Add distributed trace headers to the downstream call if we have a current span.
                if (spanForDownstreamCall != null) {
                    setHeaderIfValueNotNull(initialRequestChunk, TraceHeaders.TRACE_SAMPLED,
                                            String.valueOf(spanForDownstreamCall.isSampleable()));
                    setHeaderIfValueNotNull(initialRequestChunk, TraceHeaders.TRACE_ID,
                                            spanForDownstreamCall.getTraceId());
                    setHeaderIfValueNotNull(initialRequestChunk, TraceHeaders.SPAN_ID,
                                            spanForDownstreamCall.getSpanId());
                    setHeaderIfValueNotNull(initialRequestChunk, TraceHeaders.PARENT_SPAN_ID,
                                            spanForDownstreamCall.getParentSpanId());
                    setHeaderIfValueNotNull(initialRequestChunk, TraceHeaders.SPAN_NAME,
                                            spanForDownstreamCall.getSpanName());
                }

                Channel ch = channelFuture.getNow();
                if (logger.isDebugEnabled())
                    logger.debug("Channel ID of the Channel pulled from the pool: {}", ch.toString());

                // We may not be in the right thread to modify the channel pipeline and write data. If we're in the
                //      wrong thread we can get deadlock type situations. By running the relevant bits in the channel's
                //      event loop we're guaranteed it will be run in the correct thread.
                ch.eventLoop().execute(runnableWithTracingAndMdc(() -> {
                    BiConsumer<String, Throwable> prepChannelErrorHandler = (errorMessage, cause) -> {
                        try {
                            streamingChannel.completeExceptionally(new WrapperException(errorMessage, cause));
                        }
                        finally {
                            // This channel may be permanently busted depending on the error, so mark it broken and let
                            //      the pool close it and clean it up.
                            markChannelAsBroken(ch);
                            pool.release(ch);
                        }
                    };

                    try {
                        ObjectHolder<Boolean> callActiveHolder = new ObjectHolder<>();
                        callActiveHolder.heldObject = true;
                        ObjectHolder<Boolean> lastChunkSentDownstreamHolder = new ObjectHolder<>();
                        lastChunkSentDownstreamHolder.heldObject = false;
                        //noinspection ConstantConditions
                        prepChannelForDownstreamCall(
                            pool, ch, callback, distributedSpanStackToUse, mdcContextToUse, isSecureHttpsCall,
                            relaxedHttpsValidation, performSubSpanAroundDownstreamCalls, downstreamCallTimeoutMillis,
                            callActiveHolder, lastChunkSentDownstreamHolder
                        );

                        logInitialRequestChunk(initialRequestChunk, downstreamHost, downstreamPort);

                        // Send the HTTP request.
                        ChannelFuture writeFuture = ch.writeAndFlush(initialRequestChunk);

                        // After the initial chunk has been sent we'll open the floodgates
                        //      for any further chunk streaming
                        writeFuture.addListener(completedWriteFuture -> {
                            if (completedWriteFuture.isSuccess())
                                streamingChannel.complete(new StreamingChannel(
                                    ch, pool, callActiveHolder, lastChunkSentDownstreamHolder,
                                    distributedSpanStackToUse, mdcContextToUse
                                ));
                            else {
                                prepChannelErrorHandler.accept(
                                    "Writing the first HttpRequest chunk to the downstream service failed.",
                                    completedWriteFuture.cause()
                                );
                                //noinspection UnnecessaryReturnStatement
                                return;
                            }
                        });
                    }
                    catch (SSLException | NoSuchAlgorithmException | KeyStoreException ex) {
                        prepChannelErrorHandler.accept("Error setting up SSL context for downstream call", ex);
                        //noinspection UnnecessaryReturnStatement
                        return;
                    }
                    catch (Throwable t) {
                        // If we don't catch and handle this here it gets swallowed since we're in a Runnable
                        prepChannelErrorHandler.accept(
                            "An unexpected error occurred while prepping the channel pipeline for the downstream call",
                            t
                        );
                        //noinspection UnnecessaryReturnStatement
                        return;
                    }
                }, ctx));
            }
            catch (Throwable ex) {
                try {
                    String errorMsg = "Error occurred attempting to send first chunk (headers/etc) downstream";
                    Exception errorToFire = new WrapperException(errorMsg, ex);
                    logger.warn(errorMsg, errorToFire);
                    streamingChannel.completeExceptionally(errorToFire);
                }
                finally {
                    Channel ch = channelFuture.getNow();
                    if (ch != null) {
                        // Depending on where the error was thrown the channel may or may not exist. If it does exist,
                        //      then assume it's unusable, mark it as broken, and let the pool close it and remove it.
                        markChannelAsBroken(ch);
                        pool.release(ch);
                    }
                }
            }
            finally {
                // Unhook the tracing and MDC stuff from this thread now that we're done.
                unlinkTracingAndMdcFromCurrentThread(originalThreadInfo);
            }
        });

        return streamingChannel;
    }

    protected void prepChannelForDownstreamCall(
        ChannelPool pool, Channel ch, StreamingCallback callback, Deque<Span> distributedSpanStackToUse,
        Map<String, String> mdcContextToUse, boolean isSecureHttpsCall, boolean relaxedHttpsValidation,
        boolean performSubSpanAroundDownstreamCalls, long downstreamCallTimeoutMillis,
        ObjectHolder<Boolean> callActiveHolder, ObjectHolder<Boolean> lastChunkSentDownstreamHolder
    ) throws SSLException, NoSuchAlgorithmException, KeyStoreException {

        ChannelHandler chunkSenderHandler = new SimpleChannelInboundHandler<HttpObject>() {
            @Override
            protected void channelRead0(ChannelHandlerContext downstreamCallCtx, HttpObject msg) throws Exception {
                try {
                    // Only do the distributed trace and callback work if the call is active. Messages that pop up after
                    //      the call is fully processed should not trigger the behavior a second time.
                    if (callActiveHolder.heldObject) {
                        if (msg instanceof LastHttpContent) {
                            lastChunkSentDownstreamHolder.heldObject = true;

                            if (performSubSpanAroundDownstreamCalls) {
                                // Complete the subspan.
                                runnableWithTracingAndMdc(
                                    () -> {
                                        if (distributedSpanStackToUse == null || distributedSpanStackToUse.size() < 2)
                                            Tracer.getInstance().completeRequestSpan();
                                        else
                                            Tracer.getInstance().completeSubSpan();
                                    },
                                    distributedSpanStackToUse, mdcContextToUse
                                ).run();
                            }
                        }

                        HttpObject msgToPass = msg;
                        if (msg instanceof HttpResponse) {
                            // We can't pass the original HttpResponse back to the callback due to intricacies of how
                            //      Netty handles determining the last chunk. If we do, and the callback ends up writing
                            //      the message out to the client (which happens during proxy routing for example), then
                            //      msg's headers might get modified - potentially causing this channel pipeline to
                            //      never send a LastHttpContent, which will in turn cause an indefinite hang.
                            HttpResponse origHttpResponse = (HttpResponse) msg;

                            HttpResponse httpResponse =
                                (msg instanceof FullHttpResponse)
                                                        ? new DefaultFullHttpResponse(
                                                            origHttpResponse.getProtocolVersion(),
                                                            origHttpResponse.getStatus(),
                                                            ((FullHttpResponse) msg).content())
                                                        : new DefaultHttpResponse(origHttpResponse.getProtocolVersion(),
                                                                                  origHttpResponse.getStatus());
                            httpResponse.headers().add(origHttpResponse.headers());
                            msgToPass = httpResponse;
                        }

                        callback.messageReceived(msgToPass);
                    }
                    else {
                        if (shouldLogBadMessagesAfterRequestFinishes) {
                            runnableWithTracingAndMdc(
                                () -> logger.warn("Received HttpObject msg when call was not active: {}",
                                                  String.valueOf(msg)),
                                distributedSpanStackToUse, mdcContextToUse
                            ).run();
                        }
                    }
                }
                finally {
                    if (msg instanceof LastHttpContent) {
                        releaseChannelBackToPoolIfCallIsActive(ch, pool, callActiveHolder, "last content chunk sent",
                                                               distributedSpanStackToUse, mdcContextToUse);
                    }
                }
            }
        };

        Consumer<Throwable> doErrorHandlingConsumer = (cause) -> {
            Pair<Deque<Span>, Map<String, String>> originalThreadInfo = null;
            try {
                // Setup tracing and MDC so our log messages have the correct distributed trace info, etc.
                originalThreadInfo = linkTracingAndMdcToCurrentThread(distributedSpanStackToUse, mdcContextToUse);

                // Only do the distributed trace and callback work if the call is active. Errors that pop up after the
                //      call is fully processed should not trigger the behavior a second time.
                if (callActiveHolder.heldObject) {
                    if (performSubSpanAroundDownstreamCalls) {
                        if (distributedSpanStackToUse == null || distributedSpanStackToUse.size() < 2)
                            Tracer.getInstance().completeRequestSpan();
                        else
                            Tracer.getInstance().completeSubSpan();
                    }

                    Tracer.getInstance().unregisterFromThread();

                    if (cause instanceof Errors.NativeIoException) {
                        // NativeIoExceptions are often setup to not have stack traces which is bad for debugging.
                        //      Wrap it in a NativeIoExceptionWrapper that maps to a 503 since this is likely a busted
                        //      connection and a second attempt should work.
                        cause = new NativeIoExceptionWrapper(
                            "Caught a NativeIoException in the downstream streaming call pipeline. Wrapped it in a "
                            + "NativeIoExceptionWrapper so that it maps to a 503 and provides a usable stack trace "
                            + "in the logs.",
                            (Errors.NativeIoException) cause
                        );
                    }

                    callback.unrecoverableErrorOccurred(cause, true);
                }
                else {
                    if (cause instanceof DownstreamIdleChannelTimeoutException) {
                        logger.debug("A channel used for downstream calls will be closed because it was idle too long. "
                                     + "This is normal behavior and does not indicate a downstream call failure: {}",
                                     cause.toString());
                    }
                    else {
                        logger.warn("Received exception in downstream call pipeline after the call was finished. "
                                    + "Not necessarily anything to worry about but in case it helps debugging the "
                                    + "exception was: {}",
                                    cause.toString());
                    }
                }
            }
            finally {
                // Mark the channel as broken so it will be closed and removed from the pool when it is returned.
                markChannelAsBroken(ch);

                // Release it back to the pool if possible/necessary so the pool can do its usual cleanup.
                releaseChannelBackToPoolIfCallIsActive(
                    ch, pool, callActiveHolder, "error received in downstream pipeline: " + cause.toString(),
                    distributedSpanStackToUse, mdcContextToUse
                );

                // No matter what the cause is we want to make sure the channel is closed. Doing this raw ch.close()
                //      here will catch the cases where this channel does not have an active call but still needs to be
                //      closed (e.g. an idle channel timeout that happens in-between calls).
                ch.close();

                // Unhook the tracing and MDC stuff from this thread now that we're done.
                unlinkTracingAndMdcFromCurrentThread(originalThreadInfo);
            }
        };

        ChannelHandler errorHandler = new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext downstreamCallCtx, Throwable cause) throws Exception {
                doErrorHandlingConsumer.accept(cause);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                if (logger.isDebugEnabled()) {
                    runnableWithTracingAndMdc(
                        () -> logger.debug(
                            "Downstream channel closing. call_active={}, last_chunk_sent_downstream={}, channel_id={}",
                            callActiveHolder.heldObject, lastChunkSentDownstreamHolder.heldObject,
                            ctx.channel().toString()
                        ),
                        distributedSpanStackToUse, mdcContextToUse
                    ).run();
                }

                // We only care if the channel was closed while the call was active.
                if (callActiveHolder.heldObject)
                    doErrorHandlingConsumer.accept(new DownstreamChannelClosedUnexpectedlyException(ch));

                super.channelInactive(ctx);
            }
        };

        // Set up the HTTP client pipeline.
        ChannelPipeline p = ch.pipeline();

        List<String> registeredHandlerNames = p.names();

        // Clean up any dangling idle channel timeout handlers that were disabled in SimpleChannelPool.pollChannel() but
        //      couldn't be removed at that time because it wasn't in the channel's eventLoop.
        if (registeredHandlerNames.contains(DOWNSTREAM_IDLE_CHANNEL_TIMEOUT_HANDLER_NAME)) {
            ChannelHandler idleHandler = p.get(DOWNSTREAM_IDLE_CHANNEL_TIMEOUT_HANDLER_NAME);
            if (idleHandler != null)
                p.remove(idleHandler);
        }

        if (debugChannelLifecycleLoggingEnabled && !registeredHandlerNames.contains(DEBUG_LOGGER_HANDLER_NAME)) {
            // Add the channel debug logger if desired.
            p.addFirst(DEBUG_LOGGER_HANDLER_NAME, new LoggingHandler(DOWNSTREAM_CLIENT_CHANNEL_DEBUG_LOGGER_NAME,
                                                                     LogLevel.DEBUG)
            );
        }

        // Add/replace a downstream call timeout detector.
        addOrReplacePipelineHandler(
            new DownstreamIdleChannelTimeoutHandler(
                downstreamCallTimeoutMillis, () -> callActiveHolder.heldObject, true,
                "StreamingAsyncHttpClientChannel-call-timeout", distributedSpanStackToUse, mdcContextToUse
            ),
            DOWNSTREAM_CALL_TIMEOUT_HANDLER_NAME, p, registeredHandlerNames
        );

        if (isSecureHttpsCall) {
            // SSL call. Make sure we add the SSL handler if necessary.
            if (!registeredHandlerNames.contains(SSL_HANDLER_NAME)) {
                if (clientSslCtx == null) {
                    if (relaxedHttpsValidation) {
                        clientSslCtx =
                            SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                    }
                    else {
                        TrustManagerFactory tmf =
                            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                        tmf.init((KeyStore) null);
                        clientSslCtx = SslContextBuilder.forClient().trustManager(tmf).build();
                    }
                }

                p.addAfter(DOWNSTREAM_CALL_TIMEOUT_HANDLER_NAME, SSL_HANDLER_NAME, clientSslCtx.newHandler(ch.alloc()));
            }
        }
        else {
            // Not an SSL call. Remove the SSL handler if it's there.
            if (registeredHandlerNames.contains(SSL_HANDLER_NAME))
                p.remove(SSL_HANDLER_NAME);
        }

        // The HttpClientCodec handler deals with HTTP codec stuff so you don't have to. Set it up if it hasn't already
        //      been setup, and inspect it to make sure it's in a "ready to handle a new request" state. Some rare
        //      and currently unknown edgecases can cause us to hit this point with the HttpClientCodec in an unclean
        //      state, and if we barrel forward without cleaning this up the call will fail.
        boolean pipelineContainsHttpClientCodec = registeredHandlerNames.contains(HTTP_CLIENT_CODEC_HANDLER_NAME);
        boolean existingHttpClientCodecIsInBadState = false;
        if (pipelineContainsHttpClientCodec) {
            HttpClientCodec currentCodec = (HttpClientCodec) p.get(HTTP_CLIENT_CODEC_HANDLER_NAME);
            int currentHttpClientCodecInboundState = determineHttpClientCodecInboundState(currentCodec);
            if (currentHttpClientCodecInboundState != 0) {
                runnableWithTracingAndMdc(
                    () -> logger.warn(
                        "HttpClientCodec inbound state was not 0. It will be replaced with a fresh HttpClientCodec. "
                        + "bad_httpclientcodec_inbound_state={}", currentHttpClientCodecInboundState
                    ),
                    distributedSpanStackToUse, mdcContextToUse
                ).run();
                existingHttpClientCodecIsInBadState = true;
            }
            else {
                int currentHttpClientCodecOutboundState = determineHttpClientCodecOutboundState(currentCodec);
                if (currentHttpClientCodecOutboundState != 0) {
                    runnableWithTracingAndMdc(
                        () -> logger.warn(
                            "HttpClientCodec outbound state was not 0. It will be replaced with a fresh HttpClientCodec. "
                            + "bad_httpclientcodec_outbound_state={}", currentHttpClientCodecOutboundState
                        ),
                        distributedSpanStackToUse, mdcContextToUse
                    ).run();
                    existingHttpClientCodecIsInBadState = true;
                }
            }
        }

        // Add the HttpClientCodec if it wasn't already there (i.e. this is the first call on this pipeline),
        //      or replace it if it was in a bad state.
        if (!pipelineContainsHttpClientCodec || existingHttpClientCodecIsInBadState) {
            addOrReplacePipelineHandler(
                new HttpClientCodec(4096, 8192, 8192, true), HTTP_CLIENT_CODEC_HANDLER_NAME, p, registeredHandlerNames
            );
        }

        // Update the chunk sender handler and error handler to the newly created versions that know about the correct
        //      callback, dtrace info, etc to use for this request.
        addOrReplacePipelineHandler(chunkSenderHandler, CHUNK_SENDER_HANDLER_NAME, p, registeredHandlerNames);
        addOrReplacePipelineHandler(errorHandler, ERROR_HANDLER_NAME, p, registeredHandlerNames);
    }

    private static final Field httpClientCodecInboundHandlerField;
    private static final Field httpObjectDecoderCurrentStateField;

    private static final Field httpClientCodecOutboundHandlerField;
    private static final Field httpObjectEncoderStateField;

    static {
        try {
            Field field = CombinedChannelDuplexHandler.class.getDeclaredField("inboundHandler");
            field.setAccessible(true);
            httpClientCodecInboundHandlerField = field;
        }
        catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }

        try {
            Field field = HttpObjectDecoder.class.getDeclaredField("currentState");
            field.setAccessible(true);
            httpObjectDecoderCurrentStateField = field;
        }
        catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }

        try {
            Field field = CombinedChannelDuplexHandler.class.getDeclaredField("outboundHandler");
            field.setAccessible(true);
            httpClientCodecOutboundHandlerField = field;
        }
        catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }

        try {
            Field field = HttpObjectEncoder.class.getDeclaredField("state");
            field.setAccessible(true);
            httpObjectEncoderStateField = field;
        }
        catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    protected static int determineHttpClientCodecInboundState(HttpClientCodec currentCodec) {
        try {
            HttpObjectDecoder decoder = (HttpObjectDecoder) httpClientCodecInboundHandlerField.get(currentCodec);
            return ((Enum) httpObjectDecoderCurrentStateField.get(decoder)).ordinal();
        }
        catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    protected static int determineHttpClientCodecOutboundState(HttpClientCodec currentCodec) {
        try {
            HttpRequestEncoder encoder = (HttpRequestEncoder) httpClientCodecOutboundHandlerField.get(currentCodec);
            return httpObjectEncoderStateField.getInt(encoder);
        }
        catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    protected static void markChannelBrokenAndLogInfoIfHttpClientCodecStateIsNotZero(Channel ch,
                                                                                     String callContextForLogs) {
        HttpClientCodec currentCodec = (HttpClientCodec) ch.pipeline().get(HTTP_CLIENT_CODEC_HANDLER_NAME);
        if (currentCodec != null) {
            int currentHttpClientCodecInboundState = determineHttpClientCodecInboundState(currentCodec);
            if (currentHttpClientCodecInboundState != 0) {
                boolean channelAlreadyBroken = channelIsMarkedAsBeingBroken(ch);
                logger.warn(
                    "HttpClientCodec inbound state was not 0. The channel will be marked as broken so it won't be "
                    + "used. bad_httpclientcodec_inbound_state={}, channel_already_broken={}, channel_id={}, "
                    + "call_context=\"{}\"",
                    currentHttpClientCodecInboundState, channelAlreadyBroken, ch.toString(), callContextForLogs
                );
                markChannelAsBroken(ch);
            }
            else {
                int currentHttpClientCodecOutboundState = determineHttpClientCodecOutboundState(currentCodec);
                if (currentHttpClientCodecOutboundState != 0) {
                    boolean channelAlreadyBroken = channelIsMarkedAsBeingBroken(ch);
                    logger.warn(
                        "HttpClientCodec outbound state was not 0. The channel will be marked as broken so it won't be "
                        + "used. bad_httpclientcodec_outbound_state={}, channel_already_broken={}, channel_id={}, "
                        + "call_context=\"{}\"",
                        currentHttpClientCodecOutboundState, channelAlreadyBroken, ch.toString(), callContextForLogs
                    );
                    markChannelAsBroken(ch);
                }
            }
        }
    }

    protected void addOrReplacePipelineHandler(ChannelHandler handler, String handlerName, ChannelPipeline p,
                                               List<String> registeredHandlerNames) {
        if (registeredHandlerNames.contains(handlerName))
            p.replace(handlerName, handlerName, handler);
        else
            p.addLast(handlerName, handler);
    }

    protected static void releaseChannelBackToPoolIfCallIsActive(Channel ch, ChannelPool pool,
                                                                 ObjectHolder<Boolean> callActiveHolder,
                                                                 String contextReason,
                                                                 Deque<Span> distributedTracingStack,
                                                                 Map<String, String> distributedTracingMdcInfo) {
        if (callActiveHolder.heldObject) {
            if (logger.isDebugEnabled()) {
                runnableWithTracingAndMdc(
                    () -> logger.debug(
                        "Marking call as inactive and releasing channel back to pool. "
                        + "channel_release_reason=\"{}\"", contextReason
                    ),
                    distributedTracingStack, distributedTracingMdcInfo
                ).run();
            }

            callActiveHolder.heldObject = false;
            pool.release(ch);
        }
    }

    protected void setHeaderIfValueNotNull(HttpMessage httpMessage, String key, String value) {
        if (value != null)
            httpMessage.headers().set(key, value);
    }

    /**
     * @return The span name that should be used for the downstream call's subspan.
     */
    protected String getSubspanSpanName(String httpMethod, String url) {
        return "async_downstream_call-" + httpMethod + "_" + url;
    }

    protected static class ObjectHolder<T> {
        public T heldObject;
    }
}
