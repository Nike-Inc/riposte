package com.nike.riposte.server;

import com.nike.riposte.server.channelpipeline.HttpChannelInitializer;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.hooks.PostServerStartupHook;
import com.nike.riposte.server.hooks.PreServerStartupHook;
import com.nike.riposte.server.hooks.ServerShutdownHook;
import com.nike.riposte.server.http.ResponseSender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Netty Server implementation for Riposte that supports HTTP endpoints. Takes in a {@link ServerConfig} in the
 * constructor that provides all the configuration options. Call {@link #startup()} to kick off the server, bind to a
 * port, and start accepting requests.
 *
 * @author Nic Munroe
 */
public class Server {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final ServerConfig serverConfig;

    private final List<EventLoopGroup> eventLoopGroups = new ArrayList<>();
    private final List<Channel> channels = new ArrayList<>();
    private boolean startedUp = false;

    @SuppressWarnings("WeakerAccess")
    public static final String SERVER_BOSS_CHANNEL_DEBUG_LOGGER_NAME = "ServerBossChannelDebugLogger";

    public Server(ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
    }

    public void startup() throws CertificateException, IOException, InterruptedException {
        if (startedUp) {
            throw new IllegalArgumentException("This Server instance has already started. "
                                               + "You can only call startup() once");
        }

        // Figure out what port to bind to.
        int port = Integer.parseInt(
            System.getProperty("endpointsPort", serverConfig.isEndpointsUseSsl()
                                                ? String.valueOf(serverConfig.endpointsSslPort())
                                                : String.valueOf(serverConfig.endpointsPort())
            )
        );

        // Configure SSL if desired.
        final SslContext sslCtx;
        if (serverConfig.isEndpointsUseSsl()) {
            sslCtx = serverConfig.createSslContext();
        }
        else {
            sslCtx = null;
        }

        // Configure the server
        EventLoopGroup bossGroup;
        EventLoopGroup workerGroup;
        Class<? extends ServerChannel> channelClass;

        // Use the native epoll event loop groups if available for maximum performance
        //      (see http://netty.io/wiki/native-transports.html). If they're not available then fall back to standard
        //      NIO event loop group.
        if (Epoll.isAvailable()) {
            logger.info("The epoll native transport is available. Using epoll instead of NIO. "
                        + "riposte_server_using_native_epoll_transport=true");
            bossGroup = (serverConfig.bossThreadFactory() == null)
                        ? new EpollEventLoopGroup(serverConfig.numBossThreads())
                        : new EpollEventLoopGroup(serverConfig.numBossThreads(), serverConfig.bossThreadFactory());
            workerGroup = (serverConfig.workerThreadFactory() == null)
                          ? new EpollEventLoopGroup(serverConfig.numWorkerThreads())
                          : new EpollEventLoopGroup(serverConfig.numWorkerThreads(),
                                                    serverConfig.workerThreadFactory());
            channelClass = EpollServerSocketChannel.class;
        }
        else {
            logger.info("The epoll native transport is NOT available or you are not running on a compatible "
                        + "OS/architecture. Using NIO. riposte_server_using_native_epoll_transport=false");
            bossGroup = (serverConfig.bossThreadFactory() == null)
                        ? new NioEventLoopGroup(serverConfig.numBossThreads())
                        : new NioEventLoopGroup(serverConfig.numBossThreads(), serverConfig.bossThreadFactory());
            workerGroup = (serverConfig.workerThreadFactory() == null)
                          ? new NioEventLoopGroup(serverConfig.numWorkerThreads())
                          : new NioEventLoopGroup(serverConfig.numWorkerThreads(), serverConfig.workerThreadFactory());
            channelClass = NioServerSocketChannel.class;
        }

        eventLoopGroups.add(bossGroup);
        eventLoopGroups.add(workerGroup);

        // Figure out which channel initializer should set up the channel pipelines for new channels.
        ChannelInitializer<SocketChannel> channelInitializer = serverConfig.customChannelInitializer();
        if (channelInitializer == null) {
            // No custom channel initializer, so use the default
            channelInitializer = new HttpChannelInitializer(
                sslCtx, serverConfig.maxRequestSizeInBytes(), serverConfig.appEndpoints(),
                serverConfig.requestAndResponseFilters(),
                serverConfig.longRunningTaskExecutor(), serverConfig.riposteErrorHandler(),
                serverConfig.riposteUnhandledErrorHandler(),
                serverConfig.requestContentValidationService(), serverConfig.defaultRequestContentDeserializer(),
                new ResponseSender(
                    serverConfig.defaultResponseContentSerializer(), serverConfig.errorResponseBodySerializer()
                ),
                serverConfig.metricsListener(),
                serverConfig.defaultCompletableFutureTimeoutInMillisForNonblockingEndpoints(),
                serverConfig.accessLogger(), serverConfig.pipelineCreateHooks(),
                serverConfig.requestSecurityValidator(), serverConfig.workerChannelIdleTimeoutMillis(),
                serverConfig.proxyRouterConnectTimeoutMillis(), serverConfig.incompleteHttpCallTimeoutMillis(),
                serverConfig.maxOpenIncomingServerChannels(), serverConfig.isDebugChannelLifecycleLoggingEnabled(),
                serverConfig.userIdHeaderKeys(), serverConfig.responseCompressionThresholdBytes()
            );
        }

        // Create the server bootstrap
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(channelClass)
         .childHandler(channelInitializer);

        // execute pre startup hooks
        if (serverConfig.preServerStartupHooks() != null) {
            for (PreServerStartupHook hook : serverConfig.preServerStartupHooks()) {
                hook.executePreServerStartupHook(b);
            }
        }

        if (serverConfig.isDebugChannelLifecycleLoggingEnabled())
            b.handler(new LoggingHandler(SERVER_BOSS_CHANNEL_DEBUG_LOGGER_NAME, LogLevel.DEBUG));

        // Bind the server to the desired port and start it up so it is ready to receive requests
        Channel ch = b.bind(port)
                      .sync()
                      .channel();

        // execute post startup hooks
        if (serverConfig.postServerStartupHooks() != null) {
            for (PostServerStartupHook hook : serverConfig.postServerStartupHooks()) {
                hook.executePostServerStartupHook(serverConfig, ch);
            }
        }

        channels.add(ch);

        logger.info("Server channel open and accepting " + (serverConfig.isEndpointsUseSsl() ? "https" : "http")
                    + " requests on port " + port);
        startedUp = true;

        // Add a shutdown hook so we can gracefully stop the server when the JVM is going down
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    shutdown();
                }
                catch (Exception e) {
                    logger.warn("Error shutting down Riposte", e);
                }
            }
        });
    }

    public void shutdown() throws InterruptedException {
        try {
            logger.info("Shutting down Riposte...");
            List<ChannelFuture> channelCloseFutures = new ArrayList<>();
            for (Channel ch : channels) {
                // execute shutdown hooks
                if (serverConfig.serverShutdownHooks() != null) {
                    for (ServerShutdownHook hook : serverConfig.serverShutdownHooks()) {
                        hook.executeServerShutdownHook(serverConfig, ch);
                    }
                }

                channelCloseFutures.add(ch.close());
            }
            for (ChannelFuture chf : channelCloseFutures) {
                chf.sync();
            }
        }
        finally {
            eventLoopGroups.forEach(EventExecutorGroup::shutdownGracefully);
            logger.info("...Riposte shutdown complete");
        }
    }
}
