package com.nike.riposte.server.channelpipeline;

import com.nike.internal.util.StringUtils;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient;
import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.config.ServerConfig.HttpRequestDecoderConfig;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.error.exception.DownstreamIdleChannelTimeoutException;
import com.nike.riposte.server.error.handler.RiposteErrorHandler;
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler;
import com.nike.riposte.server.error.validation.RequestSecurityValidator;
import com.nike.riposte.server.error.validation.RequestValidator;
import com.nike.riposte.server.handler.AccessLogEndHandler;
import com.nike.riposte.server.handler.AccessLogStartHandler;
import com.nike.riposte.server.handler.ChannelPipelineFinalizerHandler;
import com.nike.riposte.server.handler.DTraceEndHandler;
import com.nike.riposte.server.handler.DTraceStartHandler;
import com.nike.riposte.server.handler.ExceptionHandlingHandler;
import com.nike.riposte.server.handler.IdleChannelTimeoutHandler;
import com.nike.riposte.server.handler.IncompleteHttpCallTimeoutHandler;
import com.nike.riposte.server.handler.NonblockingEndpointExecutionHandler;
import com.nike.riposte.server.handler.OpenChannelLimitHandler;
import com.nike.riposte.server.handler.ProcessFinalResponseOutputHandler;
import com.nike.riposte.server.handler.ProxyRouterEndpointExecutionHandler;
import com.nike.riposte.server.handler.RequestContentDeserializerHandler;
import com.nike.riposte.server.handler.RequestContentValidationHandler;
import com.nike.riposte.server.handler.RequestFilterHandler;
import com.nike.riposte.server.handler.RequestHasBeenHandledVerificationHandler;
import com.nike.riposte.server.handler.RequestInfoSetterHandler;
import com.nike.riposte.server.handler.RequestStateCleanerHandler;
import com.nike.riposte.server.handler.ResponseFilterHandler;
import com.nike.riposte.server.handler.ResponseSenderHandler;
import com.nike.riposte.server.handler.RoutingHandler;
import com.nike.riposte.server.handler.SecurityValidationHandler;
import com.nike.riposte.server.handler.SmartHttpContentCompressor;
import com.nike.riposte.server.handler.SmartHttpContentDecompressor;
import com.nike.riposte.server.hooks.PipelineCreateHook;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.http.filter.RequestAndResponseFilter;
import com.nike.riposte.server.logging.AccessLogger;
import com.nike.wingtips.Span;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.GlobalEventExecutor;

/**
 * Channel pipeline initializer that sets up the channel to handle HTTP requests. Also includes support for distributed
 * tracing, request content deserialization and validation, error handling, access logging, and more (see {@link
 * #initChannel(SocketChannel)}).
 */
@SuppressWarnings("WeakerAccess")
public class HttpChannelInitializer extends ChannelInitializer<SocketChannel> {

    @SuppressWarnings("FieldCanBeLocal")
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * The name that will be given to the SLF4J logger used by the {@link
     * #SERVER_WORKER_CHANNEL_DEBUG_LOGGING_HANDLER_NAME} handler. This is not the ID of the handler, it is the SLF4J
     * logger name, so rather than the classname of the class owning the logger showing up in the logs, this name will
     * be used instead.
     */
    public static final String SERVER_WORKER_CHANNEL_DEBUG_SLF4J_LOGGER_NAME = "ServerWorkerChannelDebugLogger";

    // -------- PIPELINE HANDLER NAME CONSTANTS ----------
    // Utility handlers
    /**
     * The name of the {@link LoggingHandler} handler in the pipeline. This handler may or may not be present in the
     * pipeline depending on the value of {@link #debugChannelLifecycleLoggingEnabled}.
     */
    public static final String SERVER_WORKER_CHANNEL_DEBUG_LOGGING_HANDLER_NAME = "WorkerChannelDebugLoggingHandler";
    /**
     * The name of the {@link IdleChannelTimeoutHandler} handler in the pipeline. This handler may or may not be present
     * in the pipeline depending on the value of {@link #workerChannelIdleTimeoutMillis} and the current state of the
     * request.
     */
    public static final String IDLE_CHANNEL_TIMEOUT_HANDLER_NAME = "IdleChannelTimeoutHandler";
    /**
     * The name of the {@link IncompleteHttpCallTimeoutHandler} handler in the pipeline. This handler may or may not be
     * present in the pipeline depending on the value of {@link #incompleteHttpCallTimeoutMillis} and the current state
     * of the request.
     */
    public static final String INCOMPLETE_HTTP_CALL_TIMEOUT_HANDLER_NAME = "IncompleteHttpCallTimeoutHandler";

    // Inbound or in/out handlers
    /**
     * The name of the {@link SslHandler} handler in the pipeline. This handler may or may not be present in the
     * pipeline depending on the value of {@link #sslCtx}.
     */
    public static final String SSL_HANDLER_NAME = "SslHandler";
    /**
     * The name of the {@link HttpServerCodec} handler in the pipeline.
     */
    public static final String HTTP_SERVER_CODEC_HANDLER_NAME = "HttpServerCodecHandler";
    /**
     * The name of the {@link RequestStateCleanerHandler} handler in the pipeline.
     */
    public static final String REQUEST_STATE_CLEANER_HANDLER_NAME = "RequestStateCleanerHandler";
    /**
     * The name of the {@link DTraceStartHandler} handler in the pipeline.
     */
    public static final String DTRACE_START_HANDLER_NAME = "DTraceStartHandler";
    /**
     * The name of the {@link AccessLogStartHandler} handler in the pipeline.
     */
    public static final String ACCESS_LOG_START_HANDLER_NAME = "AccessLogStartHandler";
    /**
     * The name of the {@link SmartHttpContentCompressor} handler in the pipeline.
     */
    public static final String SMART_HTTP_CONTENT_COMPRESSOR_HANDLER_NAME = "SmartHttpContentCompressorHandler";
    /**
     * The name of the {@link SmartHttpContentDecompressor} handler in the pipeline.
     */
    public static final String SMART_HTTP_CONTENT_DECOMPRESSOR_HANDLER_NAME = "SmartHttpContentDecompressorHandler";
    /**
     * The name of the {@link RequestInfoSetterHandler} handler in the pipeline.
     */
    public static final String REQUEST_INFO_SETTER_HANDLER_NAME = "RequestInfoSetterHandler";
    /**
     * The name of the {@link OpenChannelLimitHandler} handler in the pipeline.
     */
    public static final String OPEN_CHANNEL_LIMIT_HANDLER_NAME = "OpenChannelLimitHandler";
    /**
     * The name of the {@link RequestFilterHandler} before security handler in the pipeline.
     */
    public static final String REQUEST_FILTER_BEFORE_SECURITY_HANDLER_NAME = "BeforeSecurityRequestFilterHandler";
    /**
     * The name of the {@link RequestFilterHandler} after security handler in the pipeline.
     */
    public static final String REQUEST_FILTER_AFTER_SECURITY_HANDLER_NAME = "AfterSecurityRequestFilterHandler";
    /**
     * The name of the {@link RoutingHandler} handler in the pipeline.
     */
    public static final String ROUTING_HANDLER_NAME = "RoutingHandler";
    /**
     * The name of the {@link SecurityValidationHandler} handler in the pipeline.
     */
    public static final String SECURITY_VALIDATION_HANDLER_NAME = "SecurityValidationHandler";
    /**
     * The name of the {@link RequestContentDeserializerHandler} handler in the pipeline.
     */
    public static final String REQUEST_CONTENT_DESERIALIZER_HANDLER_NAME = "RequestContentDeserializerHandler";
    /**
     * The name of the {@link RequestContentValidationHandler} handler in the pipeline. This handler may or may not be
     * present in the pipeline depending on the value of {@link #validationService}.
     */
    public static final String REQUEST_CONTENT_VALIDATION_HANDLER_NAME = "RequestContentValidationHandler";
    /**
     * The name of the {@link NonblockingEndpointExecutionHandler} handler in the pipeline.
     */
    public static final String NONBLOCKING_ENDPOINT_EXECUTION_HANDLER_NAME = "NonblockingEndpointExecutionHandler";
    /**
     * The name of the {@link ProxyRouterEndpointExecutionHandler} handler in the pipeline.
     */
    public static final String PROXY_ROUTER_ENDPOINT_EXECUTION_HANDLER_NAME = "ProxyRouterEndpointExecutionHandler";
    /**
     * The name of the {@link RequestHasBeenHandledVerificationHandler} handler in the pipeline.
     */
    public static final String REQUEST_HAS_BEEN_HANDLED_VERIFICATION_HANDLER_NAME =
        "RequestHasBeenHandledVerificationHandler";
    /**
     * The name of the {@link ExceptionHandlingHandler} handler in the pipeline.
     */
    public static final String EXCEPTION_HANDLING_HANDLER_NAME = "ExceptionHandlingHandler";
    /**
     * The name of the {@link ResponseFilterHandler} handler in the pipeline.
     */
    public static final String RESPONSE_FILTER_HANDLER_NAME = "ResponseFilterHandler";
    /**
     * The name of the {@link ResponseSenderHandler} handler in the pipeline.
     */
    public static final String RESPONSE_SENDER_HANDLER_NAME = "ResponseSenderHandler";
    /**
     * The name of the {@link AccessLogEndHandler} handler in the pipeline.
     */
    public static final String ACCESS_LOG_END_HANDLER_NAME = "AccessLogEndHandler";
    /**
     * The name of the {@link DTraceEndHandler} handler in the pipeline.
     */
    public static final String DTRACE_END_HANDLER_NAME = "DTraceEndHandler";
    /**
     * The name of the {@link ChannelPipelineFinalizerHandler} handler in the pipeline.
     */
    public static final String CHANNEL_PIPELINE_FINALIZER_HANDLER_NAME = "ChannelPipelineFinalizerHandler";

    // Outbound-only handlers
    /**
     * The name of the {@link ProcessFinalResponseOutputHandler} handler in the pipeline.
     */
    public static final String PROCESS_FINAL_RESPONSE_OUTPUT_HANDLER_NAME = "ProcessFinalResponseOutputHandler";

    // -------- CLASS MEMBER FIELDS ----------
    private final SslContext sslCtx;
    private final int maxRequestSizeInBytes;
    private final Collection<Endpoint<?>> endpoints;
    private final Executor longRunningTaskExecutor;
    private final RiposteErrorHandler riposteErrorHandler;
    private final RiposteUnhandledErrorHandler riposteUnhandledErrorHandler;
    private final RequestValidator validationService;
    private final ObjectMapper requestContentDeserializer;
    private final ResponseSender responseSender;
    private final MetricsListener metricsListener;
    private final long defaultCompletableFutureTimeoutMillis;
    private final AccessLogger accessLogger;
    private final List<PipelineCreateHook> pipelineCreateHooks;
    private final RequestSecurityValidator requestSecurityValidator;
    private final long workerChannelIdleTimeoutMillis;
    private final long incompleteHttpCallTimeoutMillis;
    private final int maxOpenChannelsThreshold;
    private final ChannelGroup openChannelsGroup;
    private final boolean debugChannelLifecycleLoggingEnabled;
    private final int responseCompressionThresholdBytes;
    private final HttpRequestDecoderConfig httpRequestDecoderConfig;
    private final DistributedTracingConfig<Span> distributedTracingConfig;

    private final StreamingAsyncHttpClient streamingAsyncHttpClientForProxyRouterEndpoints;

    private final RequestFilterHandler beforeSecurityRequestFilterHandler;
    private final RequestFilterHandler afterSecurityRequestFilterHandler;
    private final ResponseFilterHandler cachedResponseFilterHandler;

    private final List<String> userIdHeaderKeys;

    /**
     * @param sslCtx
     *     The SSL context for handling all requests as SSL (HTTPS) requests. Pass in null if this channel should only
     *     handle normal non-SSL (HTTP) requests.
     * @param maxRequestSizeInBytes
     *     The max allowed request size in bytes. If a request exceeds this value then a {@link
     *     com.nike.riposte.server.error.exception.RequestTooBigException} will be thrown.
     *     WARNING: Not currently implemented - this argument will do nothing for now.
     * @param endpoints
     *     The list of endpoints that should be registered with each channel. Cannot be null or empty.
     * @param requestAndResponseFilters
     *     The list of request and response filters that should be applied to each request. May be null or empty if you
     *     have no filters.
     * @param longRunningTaskExecutor
     *     The task executor that should be used for long running tasks when endpoints need to do blocking
     *     I/O (e.g. making downstream calls to other systems, DB calls, etc, where there is no async nonblocking
     *     driver). This can be null - if it is null then {@link Executors#newCachedThreadPool()} will be used.
     * @param riposteErrorHandler
     *     The "normal" error handler. Cannot be null.
     * @param riposteUnhandledErrorHandler
     *     The "unhandled error" handler that will be used for everything that falls through the cracks. Cannot be null.
     * @param validationService
     *     The validation service for validating incoming request content. This can be null - if it's null then no
     *     request content validation will be performed.
     * @param requestContentDeserializer
     *     The deserializer for incoming request content. Incoming request bodies are always available as raw bytes and
     *     (optionally) raw strings as a matter of course, so this deserializer is specifically for converting {@link
     *     RequestInfo#getRawContentBytes()} to {@link RequestInfo#getContent()}. This can be null - if it is null then
     *     a basic default deserializer will be used.
     * @param responseSender
     *     The {@link ResponseSender} that should be used to send responses to the client. Cannot be null.
     * @param metricsListener
     *     The {@link MetricsListener} to use for collecting/processing metrics. Can be null - if it is null then no
     *     metrics will be collected.
     * @param defaultCompletableFutureTimeoutMillis
     *     The default timeout for {@link java.util.concurrent.CompletableFuture}s returned by standard endpoints.
     *     After this amount of time, if the future's {@link java.util.concurrent.CompletableFuture#isDone()} method
     *     returns false then it will be {@link java.util.concurrent.CompletableFuture#completeExceptionally(Throwable)}
     *     with a {@link com.nike.riposte.server.error.exception.NonblockingEndpointCompletableFutureTimedOut}. This
     *     value is also used in a similar way for the async downstream calls performed by proxy routing endpoints,
     *     except in that case a {@link DownstreamIdleChannelTimeoutException} will be used instead.
     * @param pipelineCreateHooks
     *     List of hooks that can be used to modify the pipeline
     * @param requestSecurityValidator
     *     The request security validator implementation from the server config
     * @param workerChannelIdleTimeoutMillis
     *     The amount of time in milliseconds that a worker channel can be idle before it will be closed via {@link
     *     io.netty.channel.Channel#close()}. If this argument is less than or equal to 0 then the feature will be
     *     disabled and channels will not be closed due to idleness. <b>WARNING:</b> turning this feature off is very
     *     dangerous because keep-alive connections can potentially stay alive forever. Each connection takes up memory
     *     to keep track of the open socket, leading to an out-of-memory type situation if enough clients open
     *     keep-alive connections and don't ever close them - something that could easily happen with connection pooling
     *     and enough clients. So this should only ever be set to 0 if you *really really* know what you're doing.
     * @param proxyRouterConnectTimeoutMillis
     *     The amount of time in milliseconds that a proxy/router endpoint should attempt to connect to the downstream
     *     service before giving up and throwing a connection timeout exception. Set this to 0 to disable connection
     *     timeouts entirely, which is REALLY DEFINITELY NOT RECOMMENDED and you do so at your own risk. See {@link
     *     ServerConfig#proxyRouterConnectTimeoutMillis()}
     * @param incompleteHttpCallTimeoutMillis
     *     The amount of idle time in milliseconds that the server should wait before throwing an
     *     incomplete-http-call-timeout when the request has been started (we've received at least one chunk of the
     *     request) but before the last chunk of the request is received. Set this to a value less than or equal to 0
     *     to disable incomplete-call-timeouts entirely, which is not recommended. See {@link
     *     ServerConfig#incompleteHttpCallTimeoutMillis()}.
     * @param maxOpenChannelsThreshold
     *     The max number of incoming server channels allowed to be open. -1 indicates unlimited. See {@link
     *     ServerConfig#maxOpenIncomingServerChannels()} for details on how this is
     *     used.
     * @param debugChannelLifecycleLoggingEnabled
     *     Whether or not a {@link LoggingHandler} should be added to the channel pipeline, which gives detailed
     *     lifecycle info about the channel (i.e. what local and remote ports it is connected to, when it becomes
     *     active/inactive/closed/etc)
     * @param userIdHeaderKeys
     *     The list of header keys that are considered "user ID header keys" for the purpose of distributed tracing.
     */
    public HttpChannelInitializer(SslContext sslCtx,
                                  int maxRequestSizeInBytes,
                                  Collection<Endpoint<?>> endpoints,
                                  List<RequestAndResponseFilter> requestAndResponseFilters,
                                  Executor longRunningTaskExecutor,
                                  RiposteErrorHandler riposteErrorHandler,
                                  RiposteUnhandledErrorHandler riposteUnhandledErrorHandler,
                                  RequestValidator validationService,
                                  ObjectMapper requestContentDeserializer,
                                  ResponseSender responseSender,
                                  MetricsListener metricsListener,
                                  long defaultCompletableFutureTimeoutMillis,
                                  AccessLogger accessLogger,
                                  List<PipelineCreateHook> pipelineCreateHooks,
                                  RequestSecurityValidator requestSecurityValidator,
                                  long workerChannelIdleTimeoutMillis,
                                  long proxyRouterConnectTimeoutMillis,
                                  long incompleteHttpCallTimeoutMillis,
                                  int maxOpenChannelsThreshold,
                                  boolean debugChannelLifecycleLoggingEnabled,
                                  List<String> userIdHeaderKeys,
                                  int responseCompressionThresholdBytes,
                                  HttpRequestDecoderConfig httpRequestDecoderConfig,
                                  @NotNull DistributedTracingConfig<Span> distributedTracingConfig) {
        if (endpoints == null || endpoints.isEmpty())
            throw new IllegalArgumentException("endpoints cannot be empty");

        if (longRunningTaskExecutor == null)
            longRunningTaskExecutor = Executors.newCachedThreadPool();

        if (riposteErrorHandler == null)
            throw new IllegalArgumentException("riposteErrorHandler cannot be null");

        if (riposteUnhandledErrorHandler == null)
            throw new IllegalArgumentException("riposteUnhandledErrorHandler cannot be null");

        if (responseSender == null)
            throw new IllegalArgumentException("responseSender cannot be null");

        if (httpRequestDecoderConfig == null) {
            httpRequestDecoderConfig = HttpRequestDecoderConfig.DEFAULT_IMPL;
        }

        //noinspection ConstantConditions
        if (distributedTracingConfig == null) {
            throw new IllegalArgumentException("distributedTracingConfig cannot be null");
        }

        this.sslCtx = sslCtx;
        this.maxRequestSizeInBytes = maxRequestSizeInBytes;
        this.endpoints = endpoints;
        this.longRunningTaskExecutor = longRunningTaskExecutor;
        this.riposteErrorHandler = riposteErrorHandler;
        this.riposteUnhandledErrorHandler = riposteUnhandledErrorHandler;
        this.validationService = validationService;
        this.requestContentDeserializer = requestContentDeserializer;
        this.responseSender = responseSender;
        this.metricsListener = metricsListener;
        this.defaultCompletableFutureTimeoutMillis = defaultCompletableFutureTimeoutMillis;
        this.accessLogger = accessLogger;
        this.pipelineCreateHooks = pipelineCreateHooks;
        this.requestSecurityValidator = requestSecurityValidator;
        logger.info(
            "Creating HttpChannelInitializer with {} default timeout in millis before cancelling endpoint"
            + " CompletableFutures. SSL enabled: {}",
            defaultCompletableFutureTimeoutMillis, (sslCtx != null)
        );
        for (Endpoint<?> endpoint : endpoints) {
            String matchingMethods = endpoint.requestMatcher().isMatchAllMethods()
                                     ? "ALL"
                                     : StringUtils.join(endpoint.requestMatcher().matchingMethods(), ",");
            logger.info(
                "Registering endpoint that matches methods and paths: {}\t{}",
                matchingMethods, endpoint.requestMatcher().matchingPathTemplates()
            );
        }

        this.workerChannelIdleTimeoutMillis = workerChannelIdleTimeoutMillis;
        this.maxOpenChannelsThreshold = maxOpenChannelsThreshold;
        this.incompleteHttpCallTimeoutMillis = incompleteHttpCallTimeoutMillis;
        openChannelsGroup = (maxOpenChannelsThreshold == -1)
                            ? null
                            : new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        this.debugChannelLifecycleLoggingEnabled = debugChannelLifecycleLoggingEnabled;

        this.streamingAsyncHttpClientForProxyRouterEndpoints = new StreamingAsyncHttpClient(
            workerChannelIdleTimeoutMillis, proxyRouterConnectTimeoutMillis, debugChannelLifecycleLoggingEnabled
        );

        boolean hasReqResFilters = requestAndResponseFilters != null && !requestAndResponseFilters.isEmpty();

        if (hasReqResFilters) {
            List<RequestAndResponseFilter> beforeSecurityFilters = new ArrayList<>();
            List<RequestAndResponseFilter> afterSecurityFilters = new ArrayList<>();

            requestAndResponseFilters.forEach(requestAndResponseFilter -> {
                if (requestAndResponseFilter.shouldExecuteBeforeSecurityValidation()) {
                    beforeSecurityFilters.add(requestAndResponseFilter);
                } else {
                    afterSecurityFilters.add(requestAndResponseFilter);
                }
            });

            beforeSecurityRequestFilterHandler = beforeSecurityFilters.isEmpty()? null : new RequestFilterHandler(beforeSecurityFilters);
            afterSecurityRequestFilterHandler = afterSecurityFilters.isEmpty()? null : new RequestFilterHandler(afterSecurityFilters);
        } else {
            beforeSecurityRequestFilterHandler = null;
            afterSecurityRequestFilterHandler = null;
        }

        cachedResponseFilterHandler = (hasReqResFilters) ? new ResponseFilterHandler(requestAndResponseFilters) : null;
        this.userIdHeaderKeys = userIdHeaderKeys;
        this.responseCompressionThresholdBytes = responseCompressionThresholdBytes;
        this.httpRequestDecoderConfig = httpRequestDecoderConfig;
        this.distributedTracingConfig = distributedTracingConfig;
    }

    @Override
    public void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();

        // UTILITY IN/OUT - Add a LoggingHandler if desired to give debug logging info on the channel's lifecycle and
        //                  request/response payloads, etc.
        if (debugChannelLifecycleLoggingEnabled) {
            p.addLast(SERVER_WORKER_CHANNEL_DEBUG_LOGGING_HANDLER_NAME,
                      new LoggingHandler(SERVER_WORKER_CHANNEL_DEBUG_SLF4J_LOGGER_NAME, LogLevel.DEBUG));
        }

        // IN/OUT - Add the SSL handler if desired. This will be the first non-utility inbound handler processed and the
        //          last non-utility outbound handler processed (since outbound handlers are processed in reverse
        //          order).
        if (sslCtx != null)
            p.addLast(SSL_HANDLER_NAME, sslCtx.newHandler(ch.alloc()));

        // IN/OUT - Add the HttpServerCodec to decode requests into the appropriate HttpObjects and encode responses
        //          from HttpObjects into bytes. This MUST be the earliest "outbound" handler after the SSL handler
        //          since outbound handlers are processed in reverse order.
        p.addLast(HTTP_SERVER_CODEC_HANDLER_NAME,
                  new HttpServerCodec(
                      httpRequestDecoderConfig.maxInitialLineLength(),
                      httpRequestDecoderConfig.maxHeaderSize(),
                      httpRequestDecoderConfig.maxChunkSize()
                  )
        );

        // OUTBOUND - Add ProcessFinalResponseOutputHandler to get the final response headers, calculate the final
        //            content length (after compression/gzip and/or any other modifications), etc, and set those values
        //            on the channel's HttpProcessingState.
        p.addLast(PROCESS_FINAL_RESPONSE_OUTPUT_HANDLER_NAME, new ProcessFinalResponseOutputHandler());

        // INBOUND - Now that the message is translated into HttpObjects we can add RequestStateCleanerHandler to
        //           setup/clean state for the rest of the pipeline.
        p.addLast(REQUEST_STATE_CLEANER_HANDLER_NAME,
                  new RequestStateCleanerHandler(
                      metricsListener,
                      incompleteHttpCallTimeoutMillis,
                      distributedTracingConfig
                  )
        );
        // INBOUND - Add DTraceStartHandler to start the distributed tracing for this request
        p.addLast(DTRACE_START_HANDLER_NAME, new DTraceStartHandler(userIdHeaderKeys, distributedTracingConfig));
        // INBOUND - Access log start
        p.addLast(ACCESS_LOG_START_HANDLER_NAME, new AccessLogStartHandler());

        // IN/OUT - Add SmartHttpContentCompressor for automatic content compression (if appropriate for the
        //          request/response/size threshold). This must be added after HttpServerCodec so that it can process
        //          after the request on the incoming pipeline and before the response on the outbound pipeline.
        p.addLast(SMART_HTTP_CONTENT_COMPRESSOR_HANDLER_NAME,
                  new SmartHttpContentCompressor(responseCompressionThresholdBytes));

        // INBOUND - Add the "before security" RequestFilterHandler before security and even before routing
        //      (if we have any filters to apply). This is here before RoutingHandler so that it can intercept requests
        //      before RoutingHandler throws 404s/405s.
        if (beforeSecurityRequestFilterHandler != null)
            p.addLast(REQUEST_FILTER_BEFORE_SECURITY_HANDLER_NAME, beforeSecurityRequestFilterHandler);

        // INBOUND - Add RoutingHandler to figure out which endpoint should handle the request and set it on our request
        //           state for later execution
        p.addLast(ROUTING_HANDLER_NAME, new RoutingHandler(endpoints, maxRequestSizeInBytes));

        // INBOUND - Add SmartHttpContentDecompressor for automatic content decompression if the request indicates it
        //           is compressed *and* the target endpoint (determined by the previous RoutingHandler) is one that
        //           is eligible for auto-decompression.
        p.addLast(SMART_HTTP_CONTENT_DECOMPRESSOR_HANDLER_NAME, new SmartHttpContentDecompressor());

        // INBOUND - Add RequestInfoSetterHandler to populate our RequestInfo's content.
        p.addLast(REQUEST_INFO_SETTER_HANDLER_NAME, new RequestInfoSetterHandler(maxRequestSizeInBytes));
        // INBOUND - Add OpenChannelLimitHandler to limit the number of open incoming server channels, but only if
        //           maxOpenChannelsThreshold is not -1.
        if (maxOpenChannelsThreshold != -1) {
            p.addLast(OPEN_CHANNEL_LIMIT_HANDLER_NAME,
                      new OpenChannelLimitHandler(openChannelsGroup, maxOpenChannelsThreshold));
        }

        // INBOUND - Add SecurityValidationHandler to validate the RequestInfo object for the matching endpoint
        p.addLast(SECURITY_VALIDATION_HANDLER_NAME, new SecurityValidationHandler(requestSecurityValidator));

        // INBOUND - Add the RequestFilterHandler for after security (if we have any filters to apply).
        if (afterSecurityRequestFilterHandler != null)
            p.addLast(REQUEST_FILTER_AFTER_SECURITY_HANDLER_NAME, afterSecurityRequestFilterHandler);

        // INBOUND - Now that the request state knows which endpoint will be called we can try to deserialize the
        //           request content (if desired by the endpoint)
        p.addLast(REQUEST_CONTENT_DESERIALIZER_HANDLER_NAME,
                  new RequestContentDeserializerHandler(requestContentDeserializer));

        // INBOUND - Now that the request content has (maybe) been deserialized we can try validation on that
        //           deserialized content (if desired by the endpoint and if we have a non-null validator)
        if (validationService != null)
            p.addLast(REQUEST_CONTENT_VALIDATION_HANDLER_NAME, new RequestContentValidationHandler(validationService));

        // INBOUND - Add NonblockingEndpointExecutionHandler to perform execution of async/nonblocking endpoints
        p.addLast(
            NONBLOCKING_ENDPOINT_EXECUTION_HANDLER_NAME,
            new NonblockingEndpointExecutionHandler(
                longRunningTaskExecutor, defaultCompletableFutureTimeoutMillis, distributedTracingConfig
            )
        );

        // INBOUND - Add ProxyRouterEndpointExecutionHandler to perform execution of proxy routing endpoints
        p.addLast(PROXY_ROUTER_ENDPOINT_EXECUTION_HANDLER_NAME,
                  new ProxyRouterEndpointExecutionHandler(longRunningTaskExecutor,
                                                          streamingAsyncHttpClientForProxyRouterEndpoints,
                                                          defaultCompletableFutureTimeoutMillis));

        // INBOUND - Add RequestHasBeenHandledVerificationHandler to verify that one of the endpoint handlers took care
        //           of the request. This makes sure that the messages coming into channelRead are correctly typed for
        //           the rest of the pipeline.
        p.addLast(REQUEST_HAS_BEEN_HANDLED_VERIFICATION_HANDLER_NAME, new RequestHasBeenHandledVerificationHandler());

        // INBOUND - Add ExceptionHandlingHandler to catch and deal with any exceptions or requests that fell through
        //           the cracks.
        ExceptionHandlingHandler exceptionHandlingHandler =
            new ExceptionHandlingHandler(riposteErrorHandler, riposteUnhandledErrorHandler, distributedTracingConfig);
        p.addLast(EXCEPTION_HANDLING_HANDLER_NAME, exceptionHandlingHandler);

        // INBOUND - Add the ResponseFilterHandler (if we have any filters to apply).
        if (cachedResponseFilterHandler != null)
            p.addLast(RESPONSE_FILTER_HANDLER_NAME, cachedResponseFilterHandler);

        // INBOUND - Add ResponseSenderHandler to send the response that got put into the request state
        p.addLast(RESPONSE_SENDER_HANDLER_NAME, new ResponseSenderHandler(responseSender));

        // INBOUND - Access log end
        p.addLast(ACCESS_LOG_END_HANDLER_NAME, new AccessLogEndHandler(accessLogger));
        // INBOUND - Add DTraceEndHandler to finish up our distributed trace for this request.
        p.addLast(DTRACE_END_HANDLER_NAME, new DTraceEndHandler());
        // INBOUND - Add ChannelPipelineFinalizerHandler to stop the request processing.
        p.addLast(
            CHANNEL_PIPELINE_FINALIZER_HANDLER_NAME,
            new ChannelPipelineFinalizerHandler(
                exceptionHandlingHandler, responseSender, metricsListener, accessLogger, workerChannelIdleTimeoutMillis
            )
        );

        // pipeline create hooks
        if (pipelineCreateHooks != null) {
            for (PipelineCreateHook hook : pipelineCreateHooks) {
                hook.executePipelineCreateHook(p);
            }
        }
    }

}