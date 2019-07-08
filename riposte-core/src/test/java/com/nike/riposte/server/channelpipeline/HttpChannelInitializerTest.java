package com.nike.riposte.server.channelpipeline;

import com.nike.internal.util.Pair;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient;
import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.config.ServerConfig.HttpRequestDecoderConfig;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.ProxyRouterSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.config.distributedtracing.ServerSpanNamingAndTaggingStrategy;
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
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.http.filter.RequestAndResponseFilter;
import com.nike.riposte.server.logging.AccessLogger;
import com.nike.riposte.util.Matcher;
import com.nike.wingtips.Span;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.assertj.core.api.Assertions;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.JdkSslClientContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
/**
 * Tests the functionality of {@link HttpChannelInitializer}
 */
@RunWith(DataProviderRunner.class)
public class HttpChannelInitializerTest {

    private SocketChannel socketChannelMock;
    private ChannelPipeline channelPipelineMock;

    @Before
    public void beforeMethod() {
        socketChannelMock = mock(SocketChannel.class);
        channelPipelineMock = mock(ChannelPipeline.class);
        ByteBufAllocator byteBufAllocatorMock = mock(ByteBufAllocator.class);

        doReturn(channelPipelineMock).when(socketChannelMock).pipeline();
        doReturn(byteBufAllocatorMock).when(socketChannelMock).alloc();
    }

    private <T> T extractField(Object obj, String fieldName) {
        return (T)Whitebox.getInternalState(obj, fieldName);
    }

    private Endpoint<Object> getMockEndpoint(String path, HttpMethod... matchingMethods) {
        return new Endpoint<Object>() {
            @Override
            public @NotNull Matcher requestMatcher() {
                if (matchingMethods == null || matchingMethods.length == 0)
                    return Matcher.match(path);
                else
                    return Matcher.match(path, matchingMethods);
            }
        };
    }

    @Test
    public void constructor_works_with_valid_args() {
        // given
        SslContext sslCtx = mock(SslContext.class);
        int maxRequestSizeInBytes = 42;
        Collection<Endpoint<?>> endpoints = Arrays.asList(getMockEndpoint("/some/path", HttpMethod.GET));

        RequestAndResponseFilter beforeSecurityRequestFilter = mock(RequestAndResponseFilter.class);
        doReturn(true).when(beforeSecurityRequestFilter).shouldExecuteBeforeSecurityValidation();
        RequestAndResponseFilter afterSecurityRequestFilter = mock(RequestAndResponseFilter.class);
        doReturn(false).when(afterSecurityRequestFilter).shouldExecuteBeforeSecurityValidation();
        List<RequestAndResponseFilter> reqResFilters = Arrays.asList(beforeSecurityRequestFilter, afterSecurityRequestFilter);

        Executor longRunningTaskExecutor = mock(Executor.class);
        RiposteErrorHandler riposteErrorHandler = mock(RiposteErrorHandler.class);
        RiposteUnhandledErrorHandler riposteUnhandledErrorHandler = mock(RiposteUnhandledErrorHandler.class);
        RequestValidator validationService = mock(RequestValidator.class);
        ObjectMapper requestContentDeserializer = mock(ObjectMapper.class);
        ResponseSender responseSender = mock(ResponseSender.class);
        @SuppressWarnings("unchecked")
        MetricsListener metricsListener = mock(MetricsListener.class);
        long defaultCompletableFutureTimeoutMillis = 4242L;
        AccessLogger accessLogger = mock(AccessLogger.class);
        List<PipelineCreateHook> pipelineCreateHooks = mock(List.class);
        RequestSecurityValidator requestSecurityValidator = mock(RequestSecurityValidator.class);
        long workerChannelIdleTimeoutMillis = 121000;
        long proxyRouterConnectTimeoutMillis = 4200;
        long incompleteHttpCallTimeoutMillis = 1234;
        int maxOpenChannelsThreshold = 1000;
        boolean debugChannelLifecycleLoggingEnabled = true;
        List<String> userIdHeaderKeys = mock(List.class);
        int responseCompressionThresholdBytes = 5678;
        HttpRequestDecoderConfig httpRequestDecoderConfig = new HttpRequestDecoderConfig() {};
        DistributedTracingConfig<Span> distributedTracingConfig = mock(DistributedTracingConfig.class);
        ProxyRouterSpanNamingAndTaggingStrategy<Span> proxySpanTaggingStrategyMock =
            mock(ProxyRouterSpanNamingAndTaggingStrategy.class);
        doReturn(proxySpanTaggingStrategyMock).when(distributedTracingConfig)
                                              .getProxyRouterSpanNamingAndTaggingStrategy();

        // when
        HttpChannelInitializer hci = new HttpChannelInitializer(
            sslCtx, maxRequestSizeInBytes, endpoints, reqResFilters, longRunningTaskExecutor, riposteErrorHandler, riposteUnhandledErrorHandler,
            validationService, requestContentDeserializer, responseSender, metricsListener, defaultCompletableFutureTimeoutMillis, accessLogger,
            pipelineCreateHooks, requestSecurityValidator, workerChannelIdleTimeoutMillis, proxyRouterConnectTimeoutMillis,
            incompleteHttpCallTimeoutMillis, maxOpenChannelsThreshold, debugChannelLifecycleLoggingEnabled, userIdHeaderKeys,
            responseCompressionThresholdBytes, httpRequestDecoderConfig, distributedTracingConfig);

        // then
        assertThat(extractField(hci, "sslCtx"), is(sslCtx));
        assertThat(extractField(hci, "maxRequestSizeInBytes"), is(maxRequestSizeInBytes));
        assertThat(extractField(hci, "endpoints"), is(endpoints));
        assertThat(extractField(hci, "longRunningTaskExecutor"), is(longRunningTaskExecutor));
        assertThat(extractField(hci, "riposteErrorHandler"), is(riposteErrorHandler));
        assertThat(extractField(hci, "riposteUnhandledErrorHandler"), is(riposteUnhandledErrorHandler));
        assertThat(extractField(hci, "validationService"), is(validationService));
        assertThat(extractField(hci, "requestContentDeserializer"), is(requestContentDeserializer));
        assertThat(extractField(hci, "responseSender"), is(responseSender));
        assertThat(extractField(hci, "metricsListener"), is(metricsListener));
        assertThat(extractField(hci, "defaultCompletableFutureTimeoutMillis"), is(defaultCompletableFutureTimeoutMillis));
        assertThat(extractField(hci, "accessLogger"), is(accessLogger));
        assertThat(extractField(hci, "pipelineCreateHooks"), is(pipelineCreateHooks));
        assertThat(extractField(hci, "requestSecurityValidator"), is(requestSecurityValidator));
        assertThat(extractField(hci, "workerChannelIdleTimeoutMillis"), is(workerChannelIdleTimeoutMillis));
        assertThat(extractField(hci, "incompleteHttpCallTimeoutMillis"), is(incompleteHttpCallTimeoutMillis));
        assertThat(extractField(hci, "maxOpenChannelsThreshold"), is(maxOpenChannelsThreshold));
        assertThat(extractField(hci, "debugChannelLifecycleLoggingEnabled"), is(debugChannelLifecycleLoggingEnabled));
        assertThat(extractField(hci, "userIdHeaderKeys"), is(userIdHeaderKeys));
        assertThat(extractField(hci, "responseCompressionThresholdBytes"), is(responseCompressionThresholdBytes));
        assertThat(extractField(hci, "httpRequestDecoderConfig"), is(httpRequestDecoderConfig));
        assertThat(extractField(hci, "distributedTracingConfig"), is(distributedTracingConfig));

        StreamingAsyncHttpClient sahc = extractField(hci, "streamingAsyncHttpClientForProxyRouterEndpoints");
        assertThat(extractField(sahc, "idleChannelTimeoutMillis"), is(workerChannelIdleTimeoutMillis));
        assertThat(extractField(sahc, "downstreamConnectionTimeoutMillis"), is((int)proxyRouterConnectTimeoutMillis));
        assertThat(extractField(sahc, "debugChannelLifecycleLoggingEnabled"), is(debugChannelLifecycleLoggingEnabled));
        assertThat(extractField(sahc, "proxySpanTaggingStrategy"), is(proxySpanTaggingStrategyMock));

        RequestFilterHandler beforeSecReqFH = extractField(hci, "beforeSecurityRequestFilterHandler");
        assertThat(extractField(beforeSecReqFH, "filters"), is(Collections.singletonList(beforeSecurityRequestFilter)));

        RequestFilterHandler afterSecReqFH = extractField(hci, "afterSecurityRequestFilterHandler");
        assertThat(extractField(afterSecReqFH, "filters"), is(Collections.singletonList(afterSecurityRequestFilter)));

        ResponseFilterHandler resFH = extractField(hci, "cachedResponseFilterHandler");
        List<RequestAndResponseFilter> reversedFilters = new ArrayList<>(reqResFilters);
        Collections.reverse(reversedFilters);
        assertThat(extractField(resFH, "filtersInResponseProcessingOrder"), is(reversedFilters));
    }

    @Test
    public void constructor_gracefully_handles_some_null_args() {
        // when
        HttpChannelInitializer hci = new HttpChannelInitializer(
            null, 42, Arrays.asList(getMockEndpoint("/some/path")), null, null, mock(RiposteErrorHandler.class), mock(RiposteUnhandledErrorHandler.class),
            null, null, mock(ResponseSender.class), null, 4242L, null,
            null, null, 121, 42, 321, 100, false, null,
            123, null, mock(DistributedTracingConfig.class));

        // then
        assertThat(extractField(hci, "sslCtx"), nullValue());
        Executor longRunningTaskExecutor = extractField(hci, "longRunningTaskExecutor");
        assertThat(longRunningTaskExecutor, notNullValue());
        assertThat(longRunningTaskExecutor, instanceOf(ThreadPoolExecutor.class));
        assertThat(((ThreadPoolExecutor)longRunningTaskExecutor).getMaximumPoolSize(), is(Integer.MAX_VALUE));
        assertThat(((ThreadPoolExecutor)longRunningTaskExecutor).getKeepAliveTime(TimeUnit.SECONDS), is(60L));
        assertThat(extractField(hci, "validationService"), nullValue());
        assertThat(extractField(hci, "requestContentDeserializer"), nullValue());
        assertThat(extractField(hci, "metricsListener"), nullValue());
        assertThat(extractField(hci, "accessLogger"), nullValue());
        assertThat(extractField(hci, "beforeSecurityRequestFilterHandler"), nullValue());
        assertThat(extractField(hci, "afterSecurityRequestFilterHandler"), nullValue());
        assertThat(extractField(hci, "cachedResponseFilterHandler"), nullValue());
        assertThat(extractField(hci, "userIdHeaderKeys"), nullValue());
        assertThat(extractField(hci, "httpRequestDecoderConfig"), is(HttpRequestDecoderConfig.DEFAULT_IMPL));
    }

    @Test
    public void constructor_handles_empty_after_security_request_handlers() {
        // given
        RequestAndResponseFilter beforeSecurityRequestFilter = mock(RequestAndResponseFilter.class);
        doReturn(true).when(beforeSecurityRequestFilter).shouldExecuteBeforeSecurityValidation();
        List<RequestAndResponseFilter> reqResFilters = Arrays.asList(beforeSecurityRequestFilter);

        // when
        HttpChannelInitializer hci = new HttpChannelInitializer(
                null, 42, Arrays.asList(getMockEndpoint("/some/path")), reqResFilters, null, mock(RiposteErrorHandler.class), mock(RiposteUnhandledErrorHandler.class),
                null, null, mock(ResponseSender.class), null, 4242L, null,
                null, null, 121, 42, 321, 100, false, null,
                123, null, mock(DistributedTracingConfig.class));

        // then
        RequestFilterHandler beforeSecReqFH = extractField(hci, "beforeSecurityRequestFilterHandler");
        assertThat(extractField(beforeSecReqFH, "filters"), is(Collections.singletonList(beforeSecurityRequestFilter)));

        assertThat(extractField(hci, "afterSecurityRequestFilterHandler"), nullValue());

        ResponseFilterHandler responseFilterHandler = extractField(hci, "cachedResponseFilterHandler");
        assertThat(extractField(responseFilterHandler, "filtersInResponseProcessingOrder"), is(reqResFilters));
    }

    @Test
    public void constructor_handles_empty_before_security_request_handlers() {
        // given
        RequestAndResponseFilter afterSecurityRequestFilter = mock(RequestAndResponseFilter.class);
        doReturn(false).when(afterSecurityRequestFilter).shouldExecuteBeforeSecurityValidation();
        List<RequestAndResponseFilter> reqResFilters = Arrays.asList(afterSecurityRequestFilter);

        // when
        HttpChannelInitializer hci = new HttpChannelInitializer(
                null, 42, Arrays.asList(getMockEndpoint("/some/path")), reqResFilters, null, mock(RiposteErrorHandler.class), mock(RiposteUnhandledErrorHandler.class),
                null, null, mock(ResponseSender.class), null, 4242L, null,
                null, null, 121, 42, 321, 100, false, null,
                123, null, mock(DistributedTracingConfig.class));

        // then
        RequestFilterHandler beforeSecReqFH = extractField(hci, "afterSecurityRequestFilterHandler");
        assertThat(extractField(beforeSecReqFH, "filters"), is(Collections.singletonList(afterSecurityRequestFilter)));

        assertThat(extractField(hci, "beforeSecurityRequestFilterHandler"), nullValue());

        ResponseFilterHandler responseFilterHandler = extractField(hci, "cachedResponseFilterHandler");
        assertThat(extractField(responseFilterHandler, "filtersInResponseProcessingOrder"), is(reqResFilters));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_endpoints_is_null() {
        // expect
        new HttpChannelInitializer(
            null, 42, null, null, null, mock(RiposteErrorHandler.class), mock(RiposteUnhandledErrorHandler.class),
            null, null, mock(ResponseSender.class), null, 4242L, null,
            null, null, 121, 42, 321, 100, false, null,
            123, null, mock(DistributedTracingConfig.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_endpoints_is_empty() {
        // expect
        new HttpChannelInitializer(
            null, 42, Collections.emptyList(), null, null, mock(RiposteErrorHandler.class), mock(RiposteUnhandledErrorHandler.class),
            null, null, mock(ResponseSender.class), null, 4242L, null,
            null, null, 121, 42, 321, 100, false, null,
            123, null, mock(DistributedTracingConfig.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_riposteErrorHandler_is_null() {
        // expect
        new HttpChannelInitializer(
            null, 42, Arrays.asList(getMockEndpoint("/some/path")), null, null, null, mock(RiposteUnhandledErrorHandler.class),
            null, null, mock(ResponseSender.class), null, 4242L, null,
            null, null, 121, 42, 321, 100, false, null,
            123, null, mock(DistributedTracingConfig.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_riposteUnhandledErrorHandler_is_null() {
        // expect
        new HttpChannelInitializer(
            null, 42, Arrays.asList(getMockEndpoint("/some/path")), null, null, mock(RiposteErrorHandler.class), null,
            null, null, mock(ResponseSender.class), null, 4242L, null,
            null, null, 121, 42, 321, 100, false, null,
            123, null, mock(DistributedTracingConfig.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_responseSender_is_null() {
        // expect
        new HttpChannelInitializer(
            null, 42, Arrays.asList(getMockEndpoint("/some/path")), null, null, mock(RiposteErrorHandler.class), mock(RiposteUnhandledErrorHandler.class),
            null, null, null, null, 4242L, null,
            null, null, 121, 42, 321, 100, false, null,
            123, null, mock(DistributedTracingConfig.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_distributedTracingConfig_is_null() {
        // expect
        new HttpChannelInitializer(
            null, 42, Arrays.asList(getMockEndpoint("/some/path")), null, null, mock(RiposteErrorHandler.class), mock(RiposteUnhandledErrorHandler.class),
            null, null, mock(ResponseSender.class), null, 4242L, null,
            null, null, 121, 42, 321, 100, false, null,
            123, null, null);
    }

    private <T extends ChannelHandler> Pair<Integer, T> findChannelHandler(List<ChannelHandler> channelHandlers, Class<T> classToFind, boolean findLast) {
        Pair<Integer, T> channelHandlerPair = null;

        for (int i = 0; i < channelHandlers.size(); i++) {
            ChannelHandler ch = channelHandlers.get(i);
            if (classToFind.isInstance(ch)) {
                channelHandlerPair = Pair.of(i, classToFind.cast(ch));
                if (!findLast) {
                    return channelHandlerPair;
                }
            }
        }

        return channelHandlerPair;
    }

    private <T extends ChannelHandler> Pair<Integer, T> findChannelHandler(List<ChannelHandler> channelHandlers, Class<T> classToFind) {
        return findChannelHandler(channelHandlers, classToFind, false);
    }

    private HttpChannelInitializer basicHttpChannelInitializerNoUtilityHandlers() {
        return basicHttpChannelInitializer(null, 0, -1, false, null, null);
    }

    private HttpChannelInitializer basicHttpChannelInitializer(SslContext sslCtx, long workerChannelIdleTimeoutMillis, int maxOpenChannelsThreshold,
                                                               boolean debugChannelLifecycleLoggingEnabled, RequestValidator validationService,
                                                               List<RequestAndResponseFilter> requestAndResponseFilters) {
        return new HttpChannelInitializer(
            sslCtx, 42, Arrays.asList(getMockEndpoint("/some/path")), requestAndResponseFilters, null, mock(RiposteErrorHandler.class),
            mock(RiposteUnhandledErrorHandler.class), validationService, null, mock(ResponseSender.class), null, 4242L, null,
            null, null, workerChannelIdleTimeoutMillis, 4200, 1234, maxOpenChannelsThreshold, debugChannelLifecycleLoggingEnabled,
            null, 123, null, mock(DistributedTracingConfig.class));
    }

    @Test
    public void initChannel_adds_all_handlers_with_correct_names() throws SSLException {
        RequestAndResponseFilter beforeSecurityRequestFilter = mock(RequestAndResponseFilter.class);
        doReturn(true).when(beforeSecurityRequestFilter).shouldExecuteBeforeSecurityValidation();
        
        RequestAndResponseFilter afterSecurityRequestFilter = mock(RequestAndResponseFilter.class);
        doReturn(false).when(afterSecurityRequestFilter).shouldExecuteBeforeSecurityValidation();

        List<RequestAndResponseFilter> reqResFilters = Arrays.asList(beforeSecurityRequestFilter, afterSecurityRequestFilter);

        // given
        HttpChannelInitializer
            hci = basicHttpChannelInitializer(new JdkSslClientContext(), 42, 100, true, mock(RequestValidator.class), reqResFilters);

        // when
        hci.initChannel(socketChannelMock);

        // then
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.SERVER_WORKER_CHANNEL_DEBUG_LOGGING_HANDLER_NAME), any(LoggingHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.SSL_HANDLER_NAME), any(SslHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.HTTP_SERVER_CODEC_HANDLER_NAME), any(HttpServerCodec.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.PROCESS_FINAL_RESPONSE_OUTPUT_HANDLER_NAME), any(ProcessFinalResponseOutputHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.REQUEST_STATE_CLEANER_HANDLER_NAME), any(RequestStateCleanerHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.DTRACE_START_HANDLER_NAME), any(DTraceStartHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.ACCESS_LOG_START_HANDLER_NAME), any(AccessLogStartHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.SMART_HTTP_CONTENT_COMPRESSOR_HANDLER_NAME), any(SmartHttpContentCompressor.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.ROUTING_HANDLER_NAME), any(RoutingHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.SMART_HTTP_CONTENT_DECOMPRESSOR_HANDLER_NAME), any(SmartHttpContentDecompressor.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.REQUEST_INFO_SETTER_HANDLER_NAME), any(RequestInfoSetterHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.OPEN_CHANNEL_LIMIT_HANDLER_NAME), any(OpenChannelLimitHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.REQUEST_FILTER_BEFORE_SECURITY_HANDLER_NAME), any(RequestFilterHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.SECURITY_VALIDATION_HANDLER_NAME), any(SecurityValidationHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.REQUEST_FILTER_AFTER_SECURITY_HANDLER_NAME), any(RequestFilterHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.REQUEST_CONTENT_DESERIALIZER_HANDLER_NAME), any(RequestContentDeserializerHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.REQUEST_CONTENT_VALIDATION_HANDLER_NAME), any(RequestContentValidationHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.NONBLOCKING_ENDPOINT_EXECUTION_HANDLER_NAME), any(NonblockingEndpointExecutionHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.PROXY_ROUTER_ENDPOINT_EXECUTION_HANDLER_NAME), any(ProxyRouterEndpointExecutionHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.REQUEST_HAS_BEEN_HANDLED_VERIFICATION_HANDLER_NAME), any(RequestHasBeenHandledVerificationHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.EXCEPTION_HANDLING_HANDLER_NAME), any(ExceptionHandlingHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.RESPONSE_FILTER_HANDLER_NAME), any(ResponseFilterHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.RESPONSE_SENDER_HANDLER_NAME), any(ResponseSenderHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.ACCESS_LOG_END_HANDLER_NAME), any(AccessLogEndHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.DTRACE_END_HANDLER_NAME), any(DTraceEndHandler.class));
        verify(channelPipelineMock).addLast(eq(HttpChannelInitializer.CHANNEL_PIPELINE_FINALIZER_HANDLER_NAME), any(ChannelPipelineFinalizerHandler.class));
        verifyNoMoreInteractions(channelPipelineMock);

        RequestFilterHandler beforeSecReqFH = extractField(hci, "beforeSecurityRequestFilterHandler");
        assertThat(extractField(beforeSecReqFH, "filters"), is(Collections.singletonList(beforeSecurityRequestFilter)));

        RequestFilterHandler afterSecReqFH = extractField(hci, "afterSecurityRequestFilterHandler");
        assertThat(extractField(afterSecReqFH, "filters"), is(Collections.singletonList(afterSecurityRequestFilter)));
    }

    @Test
    public void initChannel_adds_debugLoggingHandler_first_if_debugChannelLifecycleLoggingEnabled_is_true() throws SSLException {
        // given
        HttpChannelInitializer
            hci = basicHttpChannelInitializer(new JdkSslClientContext(), 42, 100, true, mock(RequestValidator.class),
                                              createRequestAndResponseFilterMock());

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        assertThat(handlers.get(0), instanceOf(LoggingHandler.class));
    }

    @Test
    public void initChannel_does_not_add_debugLoggingHandler_if_debugChannelLifecycleLoggingEnabled_is_false() throws SSLException {
        // given
        HttpChannelInitializer
            hci = basicHttpChannelInitializer(new JdkSslClientContext(), 42, 100, false, mock(RequestValidator.class),
                                                createRequestAndResponseFilterMock());

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        assertThat(findChannelHandler(handlers, LoggingHandler.class), nullValue());
    }

    @Test
    public void initChannel_adds_sslCtx_handler_first_if_available_and_no_utility_handlers() throws SSLException {
        // given
        SslContext sslCtx = new JdkSslClientContext();
        HttpChannelInitializer hci = basicHttpChannelInitializer(sslCtx, 0, 100, false, mock(RequestValidator.class),
                                                                 createRequestAndResponseFilterMock());

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        assertThat(handlers.get(0), instanceOf(SslHandler.class));
    }

    @Test
    public void initChannel_does_not_add_validationService_handler_if_it_is_null() throws SSLException {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        assertThat(findChannelHandler(handlers, RequestContentValidationHandler.class), nullValue());
    }

    @Test
    public void initChannel_adds_HttpServerCodec_as_the_last_outbound_handler_before_sslCtx() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, HttpServerCodec> foundHandler = findChannelHandler(handlers, HttpServerCodec.class);

        assertThat(foundHandler, notNullValue());

        // No SSL Context was passed, so HttpResponseEncoder should be the handler at index 0 in the list (corresponding to the "last" outbound handler since they go in reverse order).
        assertThat(foundHandler.getLeft(), is(0));
    }

    @Test
    public void initChannel_adds_HttpServerCodec_as_the_first_inbound_handler_after_sslCtx() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, ChannelInboundHandler> firstInboundHandler = findChannelHandler(handlers, ChannelInboundHandler.class);
        Pair<Integer, HttpServerCodec> foundHandler = findChannelHandler(handlers, HttpServerCodec.class);

        assertThat(firstInboundHandler, notNullValue());
        assertThat(foundHandler, notNullValue());

        // No SSL Context was passed, so HttpRequestDecoder should be the first inbound handler.
        assertThat(foundHandler.getLeft(), is(firstInboundHandler.getLeft()));
        assertThat(foundHandler.getRight(), is(firstInboundHandler.getRight()));
    }

    @Test
    public void initChannel_adds_HttpServerCodec_with_config_values_coming_from_httpRequestDecoderConfig() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();
        HttpRequestDecoderConfig configWithCustomValues = new HttpRequestDecoderConfig() {
            @Override
            public int maxInitialLineLength() {
                return 123;
            }

            @Override
            public int maxHeaderSize() {
                return 456;
            }

            @Override
            public int maxChunkSize() {
                return 789;
            }
        };
        Whitebox.setInternalState(hci, "httpRequestDecoderConfig", configWithCustomValues);

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, HttpServerCodec> httpServerCodecHandlerPair = findChannelHandler(handlers, HttpServerCodec.class);

        Assertions.assertThat(httpServerCodecHandlerPair).isNotNull();

        HttpServerCodec httpServerCodecHandler = httpServerCodecHandlerPair.getValue();
        HttpRequestDecoder decoderHandler = (HttpRequestDecoder) Whitebox.getInternalState(httpServerCodecHandler, "inboundHandler");
        int actualMaxInitialLineLength = extractField(extractField(decoderHandler, "lineParser"), "maxLength");
        int actualMaxHeaderSize = extractField(extractField(decoderHandler, "headerParser"), "maxLength");
        int actualMaxChunkSize = extractField(decoderHandler, "maxChunkSize");

        Assertions.assertThat(actualMaxInitialLineLength).isEqualTo(configWithCustomValues.maxInitialLineLength());
        Assertions.assertThat(actualMaxHeaderSize).isEqualTo(configWithCustomValues.maxHeaderSize());
        Assertions.assertThat(actualMaxChunkSize).isEqualTo(configWithCustomValues.maxChunkSize());
    }

    @Test
    public void initChannel_adds_RequestStateCleanerHandler_immediately_after_HttpServerCodec_and_ProcessFinalResponseOutputHandler() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();
        MetricsListener expectedMetricsListener = mock(MetricsListener.class);
        long expectedIncompleteCallTimeoutMillis = 424242;
        DistributedTracingConfig<Span> distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        Whitebox.setInternalState(hci, "metricsListener", expectedMetricsListener);
        Whitebox.setInternalState(hci, "incompleteHttpCallTimeoutMillis", expectedIncompleteCallTimeoutMillis);
        Whitebox.setInternalState(hci, "distributedTracingConfig", distributedTracingConfigMock);

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, HttpServerCodec> httpServerCodecHandler = findChannelHandler(handlers, HttpServerCodec.class);
        Pair<Integer, ProcessFinalResponseOutputHandler> processFinalResponseOutputHandler = findChannelHandler(
            handlers, ProcessFinalResponseOutputHandler.class
        );
        Pair<Integer, RequestStateCleanerHandler> requestStateCleanerHandler = findChannelHandler(handlers, RequestStateCleanerHandler.class);

        assertThat(httpServerCodecHandler, notNullValue());
        assertThat(processFinalResponseOutputHandler, notNullValue());
        assertThat(requestStateCleanerHandler, notNullValue());

        assertThat(processFinalResponseOutputHandler.getLeft(), is(httpServerCodecHandler.getLeft() + 1));
        assertThat(requestStateCleanerHandler.getLeft(), is(httpServerCodecHandler.getLeft() + 2));

        RequestStateCleanerHandler handler = requestStateCleanerHandler.getRight();
        assertThat(Whitebox.getInternalState(handler, "metricsListener"), is(expectedMetricsListener));
        assertThat(Whitebox.getInternalState(handler, "incompleteHttpCallTimeoutMillis"), is(expectedIncompleteCallTimeoutMillis));
        assertThat(Whitebox.getInternalState(handler, "distributedTracingConfig"), is(distributedTracingConfigMock));
    }

    @Test
    public void initChannel_adds_DTraceStartHandler_immediately_after_RequestStateCleanerHandler() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();
        DistributedTracingConfig<Span> distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        ServerSpanNamingAndTaggingStrategy<Span> serverSpanNamingAndTaggingStrategyMock =
            mock(ServerSpanNamingAndTaggingStrategy.class);
        Whitebox.setInternalState(hci, "distributedTracingConfig", distributedTracingConfigMock);
        doReturn(serverSpanNamingAndTaggingStrategyMock)
            .when(distributedTracingConfigMock).getServerSpanNamingAndTaggingStrategy();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, RequestStateCleanerHandler> requestStateCleanerHandler = findChannelHandler(handlers, RequestStateCleanerHandler.class);
        Pair<Integer, DTraceStartHandler> dTraceStartHandler = findChannelHandler(handlers, DTraceStartHandler.class);

        assertThat(requestStateCleanerHandler, notNullValue());
        assertThat(dTraceStartHandler, notNullValue());

        assertThat(dTraceStartHandler.getLeft(), is(requestStateCleanerHandler.getLeft() + 1));
        assertThat(extractField(dTraceStartHandler.getRight(), "spanNamingAndTaggingStrategy"),
                   is(serverSpanNamingAndTaggingStrategyMock));
    }

    @Test
    public void initChannel_adds_AccessLogStartHandler_immediately_after_DTraceStartHandler() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, DTraceStartHandler> dTraceStartHandler = findChannelHandler(handlers, DTraceStartHandler.class);
        Pair<Integer, AccessLogStartHandler> accessLogStartHandler = findChannelHandler(handlers, AccessLogStartHandler.class);

        assertThat(dTraceStartHandler, notNullValue());
        assertThat(accessLogStartHandler, notNullValue());

        assertThat(accessLogStartHandler.getLeft(), is(dTraceStartHandler.getLeft() + 1));
    }

    @Test
    public void initChannel_adds_SmartHttpContentCompressor_before_HttpServerCodec_for_outbound_handler() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, SmartHttpContentCompressor> httpContentCompressor = findChannelHandler(handlers, SmartHttpContentCompressor.class);
        Pair<Integer, HttpServerCodec> httpServerCodec = findChannelHandler(handlers, HttpServerCodec.class);

        assertThat(httpContentCompressor, notNullValue());
        assertThat(httpServerCodec, notNullValue());

        // SmartHttpContentCompressor's index should be later than HttpResponseEncoder's index to verify that it comes
        //      BEFORE HttpResponseEncoder on the OUTBOUND handlers (since the outbound handlers are processed in
        //      reverse order).
        assertThat(httpContentCompressor.getLeft(), is(greaterThan(httpServerCodec.getLeft())));
        // Verify that SmartHttpContentCompressor's threshold is set to the specified config value.
        long expectedThresholdValue = ((Integer)extractField(hci, "responseCompressionThresholdBytes")).longValue();
        assertThat(extractField(httpContentCompressor.getRight(), "responseSizeThresholdBytes"),
                   is(expectedThresholdValue));
    }

    @Test
    public void initChannel_adds_RequestInfoSetterHandler_after_SmartHttpContentCompressor() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, SmartHttpContentCompressor> httpContentCompressor = findChannelHandler(handlers, SmartHttpContentCompressor.class);
        Pair<Integer, RequestInfoSetterHandler> requestInfoSetterHandler = findChannelHandler(handlers, RequestInfoSetterHandler.class);

        assertThat(httpContentCompressor, notNullValue());
        assertThat(requestInfoSetterHandler, notNullValue());

        assertThat(requestInfoSetterHandler.getLeft(), is(greaterThan(httpContentCompressor.getLeft())));
        //verify max size is passed through into RequestInfoSetterHandler
        assertThat(extractField(requestInfoSetterHandler.getRight(), "globalConfiguredMaxRequestSizeInBytes"), is(42));
    }

    @Test
    public void initChannel_adds_OpenChannelLimitHandler_after_RequestInfoSetterHandler_and_uses_cached_ChannelGroup() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializer(null, 0, 42, false, null, null);

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, RequestInfoSetterHandler> requestInfoSetterHandler = findChannelHandler(handlers, RequestInfoSetterHandler.class);
        Pair<Integer, OpenChannelLimitHandler> openChannelLimitHandler = findChannelHandler(handlers, OpenChannelLimitHandler.class);

        assertThat(requestInfoSetterHandler, notNullValue());
        assertThat(openChannelLimitHandler, notNullValue());

        assertThat(openChannelLimitHandler.getLeft(), is(requestInfoSetterHandler.getLeft() + 1));

        // and then
        ChannelGroup expectedChannelGroup = extractField(hci, "openChannelsGroup");
        ChannelGroup actualChannelGroup = (ChannelGroup) Whitebox.getInternalState(openChannelLimitHandler.getRight(), "openChannelsGroup");
        assertThat(actualChannelGroup, is(expectedChannelGroup));
    }

    @Test
    public void initChannel_does_not_add_OpenChannelLimitHandler_if_threshold_is_negative_1() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializer(null, 0, -1, false, null, null);

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        assertThat(findChannelHandler(handlers, OpenChannelLimitHandler.class), nullValue());
    }

    @Test
    public void initChannel_adds_before_and_after_RequestFilterHandler_appropriately_before_and_after_security_filter() {
        // given
        RequestAndResponseFilter beforeSecurityRequestFilter = mock(RequestAndResponseFilter.class);
        doReturn(true).when(beforeSecurityRequestFilter).shouldExecuteBeforeSecurityValidation();
        RequestAndResponseFilter afterSecurityRequestFilter = mock(RequestAndResponseFilter.class);
        doReturn(false).when(afterSecurityRequestFilter).shouldExecuteBeforeSecurityValidation();
        List<RequestAndResponseFilter> requestAndResponseFilters = Arrays.asList(beforeSecurityRequestFilter, afterSecurityRequestFilter);

        HttpChannelInitializer hci = basicHttpChannelInitializer(null, 0, 42, false, null, requestAndResponseFilters);

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, AccessLogStartHandler> accessLogStartHandler = findChannelHandler(handlers, AccessLogStartHandler.class);
        Pair<Integer, RequestFilterHandler> beforeSecurityRequestFilterHandler = findChannelHandler(handlers, RequestFilterHandler.class);
        Pair<Integer, RequestFilterHandler> afterSecurityRequestFilterHandler = findChannelHandler(handlers, RequestFilterHandler.class, true);
        Pair<Integer, RoutingHandler> routingHandler = findChannelHandler(handlers, RoutingHandler.class);
        Pair<Integer, SecurityValidationHandler> securityValidationHandler = findChannelHandler(handlers, SecurityValidationHandler.class);
        Pair<Integer, RequestContentDeserializerHandler> requestContentDeserializerHandler = findChannelHandler(handlers, RequestContentDeserializerHandler.class);

        assertThat(accessLogStartHandler, notNullValue());
        assertThat(beforeSecurityRequestFilterHandler, notNullValue());
        assertThat(routingHandler, notNullValue());
        assertThat(afterSecurityRequestFilterHandler, notNullValue());
        assertThat(securityValidationHandler, notNullValue());
        assertThat(requestContentDeserializerHandler, notNullValue());

        Assertions.assertThat(beforeSecurityRequestFilterHandler.getLeft()).isGreaterThan(accessLogStartHandler.getLeft());
        Assertions.assertThat(beforeSecurityRequestFilterHandler.getLeft()).isLessThan(securityValidationHandler.getLeft());
        // beforeSecurityRequestFilterHandler must come before RoutingHandler as well (so that it is executed before any 404 gets thrown)
        Assertions.assertThat(beforeSecurityRequestFilterHandler.getLeft()).isLessThan(routingHandler.getLeft());

        Assertions.assertThat(afterSecurityRequestFilterHandler.getLeft()).isGreaterThan(securityValidationHandler.getLeft());
        Assertions.assertThat(afterSecurityRequestFilterHandler.getLeft()).isLessThan(requestContentDeserializerHandler.getLeft());

        // and then
        RequestFilterHandler beforeSecurityCachedHandler = extractField(hci, "beforeSecurityRequestFilterHandler");
        Assertions.assertThat(beforeSecurityRequestFilterHandler.getRight()).isSameAs(beforeSecurityCachedHandler);

        RequestFilterHandler afterSecurityCachedHandler = extractField(hci, "afterSecurityRequestFilterHandler");
        Assertions.assertThat(afterSecurityRequestFilterHandler.getRight()).isSameAs(afterSecurityCachedHandler);
    }

    @Test
    public void initChannel_adds_RoutingHandler_after_AccessLogStartHandler_and_before_SmartHttpContentDecompressor_and_uses_endpoints_collection() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();

        DistributedTracingConfig<Span> distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        ServerSpanNamingAndTaggingStrategy<Span> expectedServerSpanNamingAndTaggingStrategy =
            mock(ServerSpanNamingAndTaggingStrategy.class);
        Whitebox.setInternalState(hci, "distributedTracingConfig", distributedTracingConfigMock);
        doReturn(expectedServerSpanNamingAndTaggingStrategy)
            .when(distributedTracingConfigMock).getServerSpanNamingAndTaggingStrategy();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, AccessLogStartHandler> accessLogStartHandler = findChannelHandler(handlers, AccessLogStartHandler.class);
        Pair<Integer, RoutingHandler> routingHandler = findChannelHandler(handlers, RoutingHandler.class);
        Pair<Integer, SmartHttpContentDecompressor> smartDecompressionHandler = findChannelHandler(handlers, SmartHttpContentDecompressor.class);

        assertThat(accessLogStartHandler, notNullValue());
        assertThat(routingHandler, notNullValue());
        assertThat(smartDecompressionHandler, notNullValue());

        assertThat(routingHandler.getLeft(), is(greaterThan(accessLogStartHandler.getLeft())));
        assertThat(routingHandler.getLeft(), is(lessThan(smartDecompressionHandler.getLeft())));

        // and then
        Collection<Endpoint<?>> expectedEndpoints = extractField(hci, "endpoints");
        Collection<Endpoint<?>> actualEndpoints = (Collection<Endpoint<?>>) Whitebox.getInternalState(routingHandler.getRight(), "endpoints");
        assertThat(actualEndpoints, is(expectedEndpoints));
        ServerSpanNamingAndTaggingStrategy<Span> actualNamingStrategy =
            (ServerSpanNamingAndTaggingStrategy<Span>) Whitebox.getInternalState(
                routingHandler.getRight(), "spanNamingAndTaggingStrategy"
            );
        assertThat(actualNamingStrategy, is(expectedServerSpanNamingAndTaggingStrategy));
    }

    @Test
    public void initChannel_adds_SmartHttpContentDecompressor_after_RoutingHandler_and_before_RequestInfoSetterHandler() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, RoutingHandler> routingHandler = findChannelHandler(handlers, RoutingHandler.class);
        Pair<Integer, SmartHttpContentDecompressor> smartDecompressionHandler = findChannelHandler(handlers, SmartHttpContentDecompressor.class);
        Pair<Integer, RequestInfoSetterHandler> requestInfoSetterhandler = findChannelHandler(handlers, RequestInfoSetterHandler.class);

        assertThat(routingHandler, notNullValue());
        assertThat(smartDecompressionHandler, notNullValue());
        assertThat(requestInfoSetterhandler, notNullValue());

        assertThat(smartDecompressionHandler.getLeft(), is(greaterThan(routingHandler.getLeft())));
        assertThat(smartDecompressionHandler.getLeft(), is(lessThan(requestInfoSetterhandler.getLeft())));
    }

    @Test
    public void initChannel_adds_RequestContentDeserializerHandler_after_RequestInfoSetterHandler_and_RoutingHandler_and_uses_requestContentDeserializer() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();
        ObjectMapper expectedRequestContentDeserializer = mock(ObjectMapper.class);
        Whitebox.setInternalState(hci, "requestContentDeserializer", expectedRequestContentDeserializer);

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, RequestInfoSetterHandler> requestInfoSetterHandler = findChannelHandler(handlers, RequestInfoSetterHandler.class);
        Pair<Integer, RoutingHandler> routingHandler = findChannelHandler(handlers, RoutingHandler.class);
        Pair<Integer, RequestContentDeserializerHandler> requestContentDeserializerHandler = findChannelHandler(handlers, RequestContentDeserializerHandler.class);

        assertThat(requestInfoSetterHandler, notNullValue());
        assertThat(routingHandler, notNullValue());
        assertThat(requestContentDeserializerHandler, notNullValue());

        assertThat(requestContentDeserializerHandler.getLeft(), is(greaterThan(requestInfoSetterHandler.getLeft())));
        assertThat(requestContentDeserializerHandler.getLeft(), is(greaterThan(routingHandler.getLeft())));

        // and then
        ObjectMapper actualRequestContentDeserializer = (ObjectMapper) Whitebox.getInternalState(requestContentDeserializerHandler.getRight(), "defaultRequestContentDeserializer");
        assertThat(actualRequestContentDeserializer, is(expectedRequestContentDeserializer));
    }

    @Test
    public void initChannel_adds_RequestContentValidationHandler_after_RequestContentDeserializerHandler_and_uses_validationService() {
        // given
        RequestValidator expectedValidationService = mock(RequestValidator.class);
        HttpChannelInitializer hci = basicHttpChannelInitializer(null, 0, 100, false, expectedValidationService, null);

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, RequestContentDeserializerHandler> requestContentDeserializerHandler = findChannelHandler(handlers, RequestContentDeserializerHandler.class);
        Pair<Integer, RequestContentValidationHandler> requestContentValidationHandler = findChannelHandler(handlers, RequestContentValidationHandler.class);

        assertThat(requestContentDeserializerHandler, notNullValue());
        assertThat(requestContentValidationHandler, notNullValue());

        assertThat(requestContentValidationHandler.getLeft(), is(greaterThan(requestContentDeserializerHandler.getLeft())));

        // and then
        RequestValidator actualRequestValidator = (RequestValidator) Whitebox.getInternalState(requestContentValidationHandler.getRight(), "validationService");
        assertThat(actualRequestValidator, is(expectedValidationService));
    }

    @Test
    public void initChannel_adds_HttpResponseEncoder() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, HttpServerCodec> httpServerCodec = findChannelHandler(handlers, HttpServerCodec.class);
        assertThat(httpServerCodec, notNullValue());
    }

    @Test
    public void initChannel_adds_NonblockingEndpointExecutionHandler_after_RoutingHandler_and_RequestContentDeserializerHandler_and_RequestContentValidationHandler_and_uses_longRunningTaskExecutor_and_defaultCompletableFutureTimeoutMillis() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();
        Whitebox.setInternalState(hci, "validationService", mock(RequestValidator.class));
        Executor expectedLongRunningTaskExecutor = extractField(hci, "longRunningTaskExecutor");
        long expectedDefaultCompletableFutureTimeoutMillis = extractField(hci, "defaultCompletableFutureTimeoutMillis");

        DistributedTracingConfig<Span> distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        ServerSpanNamingAndTaggingStrategy<Span> expectedServerSpanNamingAndTaggingStrategy =
            mock(ServerSpanNamingAndTaggingStrategy.class);
        Whitebox.setInternalState(hci, "distributedTracingConfig", distributedTracingConfigMock);
        doReturn(expectedServerSpanNamingAndTaggingStrategy)
            .when(distributedTracingConfigMock).getServerSpanNamingAndTaggingStrategy();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, RoutingHandler> routingHandler = findChannelHandler(handlers, RoutingHandler.class);
        Pair<Integer, RequestContentDeserializerHandler> requestContentDeserializerHandler = findChannelHandler(handlers, RequestContentDeserializerHandler.class);
        Pair<Integer, RequestContentValidationHandler> requestContentValidationHandler = findChannelHandler(handlers, RequestContentValidationHandler.class);
        Pair<Integer, NonblockingEndpointExecutionHandler> nonblockingEndpointExecutionHandler = findChannelHandler(handlers, NonblockingEndpointExecutionHandler.class);

        assertThat(routingHandler, notNullValue());
        assertThat(requestContentDeserializerHandler, notNullValue());
        assertThat(requestContentValidationHandler, notNullValue());
        assertThat(nonblockingEndpointExecutionHandler, notNullValue());

        assertThat(nonblockingEndpointExecutionHandler.getLeft(), is(greaterThan(routingHandler.getLeft())));
        assertThat(nonblockingEndpointExecutionHandler.getLeft(), is(greaterThan(requestContentDeserializerHandler.getLeft())));
        assertThat(nonblockingEndpointExecutionHandler.getLeft(), is(greaterThan(requestContentValidationHandler.getLeft())));

        // and then
        Executor actualLongRunningTaskExecutor = (Executor) Whitebox.getInternalState(nonblockingEndpointExecutionHandler.getRight(), "longRunningTaskExecutor");
        long actualDefaultCompletableFutureTimeoutMillis = (long) Whitebox.getInternalState(nonblockingEndpointExecutionHandler.getRight(), "defaultCompletableFutureTimeoutMillis");
        ServerSpanNamingAndTaggingStrategy<Span> actualTaggingStrategy =
            (ServerSpanNamingAndTaggingStrategy<Span>) Whitebox.getInternalState(
                nonblockingEndpointExecutionHandler.getRight(), "spanTaggingStrategy"
            );
        assertThat(actualLongRunningTaskExecutor, is(expectedLongRunningTaskExecutor));
        assertThat(actualDefaultCompletableFutureTimeoutMillis, is(expectedDefaultCompletableFutureTimeoutMillis));
        assertThat(actualTaggingStrategy, is(expectedServerSpanNamingAndTaggingStrategy));
    }

    @Test
    public void initChannel_adds_ResponseFilterHandler_after_ExceptionHandlingHandler_and_before_ResponseSenderHandler_and_uses_cached_handler() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializer(null, 0, 42, false, null, createRequestAndResponseFilterMock());

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, ExceptionHandlingHandler> exceptionHandler = findChannelHandler(handlers, ExceptionHandlingHandler.class);
        Pair<Integer, ResponseFilterHandler> responseFilterHandler = findChannelHandler(handlers, ResponseFilterHandler.class);
        Pair<Integer, ResponseSenderHandler> responseSenderHandler = findChannelHandler(handlers, ResponseSenderHandler.class);

        assertThat(exceptionHandler, notNullValue());
        assertThat(responseFilterHandler, notNullValue());
        assertThat(responseSenderHandler, notNullValue());

        Assertions.assertThat(responseFilterHandler.getLeft()).isGreaterThan(exceptionHandler.getLeft());
        Assertions.assertThat(responseFilterHandler.getLeft()).isLessThan(responseSenderHandler.getLeft());

        // and then
        ResponseFilterHandler cachedHandler = extractField(hci, "cachedResponseFilterHandler");
        Assertions.assertThat(responseFilterHandler.getRight()).isSameAs(cachedHandler);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void initChannel_does_not_add_RequestFilterHandler_or_ResponseFilterHandler_if_filter_list_is_null_or_empty(boolean isNullList) {
        // given
        List<RequestAndResponseFilter> filterList = (isNullList) ? null : Collections.emptyList();
        HttpChannelInitializer hci = basicHttpChannelInitializer(null, 0, 42, false, null, filterList);

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, RequestFilterHandler> requestFilterHandler = findChannelHandler(handlers, RequestFilterHandler.class);
        Pair<Integer, ResponseFilterHandler> responseFilterHandler = findChannelHandler(handlers, ResponseFilterHandler.class);

        Assertions.assertThat(requestFilterHandler).isNull();
        Assertions.assertThat(responseFilterHandler).isNull();
    }

    @Test
    public void initChannel_adds_ExceptionHandlingHandler_immediately_before_ResponseSenderHandler_and_after_NonblockingEndpointExecutionHandler_and_uses_riposteErrorHandler_and_riposteUnhandledErrorHandler() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();
        RiposteErrorHandler expectedRiposteErrorHandler = extractField(hci, "riposteErrorHandler");
        RiposteUnhandledErrorHandler
            expectedRiposteUnhandledErrorHandler = extractField(hci, "riposteUnhandledErrorHandler");

        DistributedTracingConfig<Span> distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        ServerSpanNamingAndTaggingStrategy<Span> serverSpanNamingAndTaggingStrategyMock =
            mock(ServerSpanNamingAndTaggingStrategy.class);
        Whitebox.setInternalState(hci, "distributedTracingConfig", distributedTracingConfigMock);
        doReturn(serverSpanNamingAndTaggingStrategyMock)
            .when(distributedTracingConfigMock).getServerSpanNamingAndTaggingStrategy();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, ResponseSenderHandler> responseSenderHandler = findChannelHandler(handlers, ResponseSenderHandler.class);
        Pair<Integer, NonblockingEndpointExecutionHandler> nonblockingEndpointExecutionHandler = findChannelHandler(handlers, NonblockingEndpointExecutionHandler.class);
        Pair<Integer, ExceptionHandlingHandler> exceptionHandlingHandler = findChannelHandler(handlers, ExceptionHandlingHandler.class);

        assertThat(responseSenderHandler, notNullValue());
        assertThat(nonblockingEndpointExecutionHandler, notNullValue());
        assertThat(exceptionHandlingHandler, notNullValue());

        assertThat(exceptionHandlingHandler.getLeft(), is(responseSenderHandler.getLeft() - 1));
        assertThat(exceptionHandlingHandler.getLeft(), is(greaterThan(nonblockingEndpointExecutionHandler.getLeft())));
        assertThat(extractField(exceptionHandlingHandler.getRight(), "spanNamingAndTaggingStrategy"),
                   is(serverSpanNamingAndTaggingStrategyMock));

        // and then
        RiposteErrorHandler
            actualRiposteErrorHandler = (RiposteErrorHandler) Whitebox.getInternalState(exceptionHandlingHandler.getRight(), "riposteErrorHandler");
        RiposteUnhandledErrorHandler
            actualRiposteUnhandledErrorHandler = (RiposteUnhandledErrorHandler) Whitebox.getInternalState(exceptionHandlingHandler.getRight(), "riposteUnhandledErrorHandler");
        assertThat(actualRiposteErrorHandler, is(expectedRiposteErrorHandler));
        assertThat(actualRiposteUnhandledErrorHandler, is(expectedRiposteUnhandledErrorHandler));
    }

    @Test
    public void initChannel_adds_ResponseSenderHandler_after_NonblockingEndpointExecutionHandler_and_uses_responseSender() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();
        ResponseSender expectedResponseSender = extractField(hci, "responseSender");

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, NonblockingEndpointExecutionHandler> nonblockingEndpointExecutionHandler = findChannelHandler(handlers, NonblockingEndpointExecutionHandler.class);
        Pair<Integer, ResponseSenderHandler> responseSenderHandler = findChannelHandler(handlers, ResponseSenderHandler.class);

        assertThat(nonblockingEndpointExecutionHandler, notNullValue());
        assertThat(responseSenderHandler, notNullValue());

        assertThat(responseSenderHandler.getLeft(), is(greaterThan(nonblockingEndpointExecutionHandler.getLeft())));

        // and then
        ResponseSender actualResponseSender = (ResponseSender) Whitebox.getInternalState(responseSenderHandler.getRight(), "responseSender");
        assertThat(actualResponseSender, is(expectedResponseSender));
    }

    @Test
    public void initChannel_adds_AccessLogEndHandler_immediately_before_DTraceEndHandler_and_uses_accessLogger() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();
        AccessLogger expectedAccessLogger = mock(AccessLogger.class);
        Whitebox.setInternalState(hci, "accessLogger", expectedAccessLogger);

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, DTraceEndHandler> dTraceEndHandler = findChannelHandler(handlers, DTraceEndHandler.class);
        Pair<Integer, AccessLogEndHandler> accessLogEndHandler = findChannelHandler(handlers, AccessLogEndHandler.class);

        assertThat(dTraceEndHandler, notNullValue());
        assertThat(accessLogEndHandler, notNullValue());

        assertThat(accessLogEndHandler.getLeft(), is(dTraceEndHandler.getLeft() - 1));

        // and then
        AccessLogger actualAccessLogger = (AccessLogger) Whitebox.getInternalState(accessLogEndHandler.getRight(), "accessLogger");
        assertThat(actualAccessLogger, is(expectedAccessLogger));
    }

    @Test
    public void initChannel_adds_DTraceEndHandler_immediately_before_ChannelPipelineFinalizerHandler() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, ChannelPipelineFinalizerHandler> channelPipelineFinalizerHandler = findChannelHandler(handlers, ChannelPipelineFinalizerHandler.class);
        Pair<Integer, DTraceEndHandler> dTraceEndHandler = findChannelHandler(handlers, DTraceEndHandler.class);

        assertThat(channelPipelineFinalizerHandler, notNullValue());
        assertThat(dTraceEndHandler, notNullValue());

        assertThat(dTraceEndHandler.getLeft(), is(channelPipelineFinalizerHandler.getLeft() - 1));
    }

    @Test
    public void initChannel_adds_ChannelPipelineFinalizerHandler_as_the_last_handler_and_passes_the_expected_args() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();
        MetricsListener expectedMetricsListener = mock(MetricsListener.class);
        AccessLogger expectedAccessLogger = mock(AccessLogger.class);
        Whitebox.setInternalState(hci, "metricsListener", expectedMetricsListener);
        Whitebox.setInternalState(hci, "accessLogger", expectedAccessLogger);
        ResponseSender expectedResponseSender = extractField(hci, "responseSender");

        // when
        hci.initChannel(socketChannelMock);

        // then
        ArgumentCaptor<ChannelHandler> channelHandlerArgumentCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(channelPipelineMock, atLeastOnce()).addLast(anyString(), channelHandlerArgumentCaptor.capture());
        List<ChannelHandler> handlers = channelHandlerArgumentCaptor.getAllValues();
        Pair<Integer, ChannelPipelineFinalizerHandler> channelPipelineFinalizerHandler = findChannelHandler(handlers, ChannelPipelineFinalizerHandler.class);

        assertThat(channelPipelineFinalizerHandler, notNullValue());

        assertThat(channelPipelineFinalizerHandler.getLeft(), is(handlers.size() - 1));

        // and then
        Pair<Integer, ExceptionHandlingHandler> expectedExceptionHandlingHandlerPair = findChannelHandler(handlers, ExceptionHandlingHandler.class);
        assertThat(expectedExceptionHandlingHandlerPair, notNullValue());

        ExceptionHandlingHandler actualExceptionHandlingHandler = (ExceptionHandlingHandler) Whitebox.getInternalState(channelPipelineFinalizerHandler.getRight(), "exceptionHandlingHandler");
        ResponseSender actualResponseSender = (ResponseSender) Whitebox.getInternalState(channelPipelineFinalizerHandler.getRight(), "responseSender");
        MetricsListener actualMetricsListener = (MetricsListener) Whitebox.getInternalState(channelPipelineFinalizerHandler.getRight(), "metricsListener");
        AccessLogger actualAccessLogger = (AccessLogger) Whitebox.getInternalState(channelPipelineFinalizerHandler.getRight(), "accessLogger");

        assertThat(actualExceptionHandlingHandler, is(expectedExceptionHandlingHandlerPair.getRight()));
        assertThat(actualResponseSender, is(expectedResponseSender));
        assertThat(actualMetricsListener, is(expectedMetricsListener));
        Assertions.assertThat(actualAccessLogger).isSameAs(expectedAccessLogger);
    }

    @Test
    public void initChannel_executes_pipelineCreateHooks() {
        // given
        HttpChannelInitializer hci = basicHttpChannelInitializerNoUtilityHandlers();
        List<PipelineCreateHook> hooks = Arrays.asList(mock(PipelineCreateHook.class), mock(PipelineCreateHook.class));
        Whitebox.setInternalState(hci, "pipelineCreateHooks", hooks);

        // when
        hci.initChannel(socketChannelMock);

        // then
        hooks.forEach(hook -> verify(hook).executePipelineCreateHook(channelPipelineMock));
    }

    private List<RequestAndResponseFilter> createRequestAndResponseFilterMock() {
        RequestAndResponseFilter beforeSecurityRequestFilter = mock(RequestAndResponseFilter.class);
        doReturn(true).when(beforeSecurityRequestFilter).shouldExecuteBeforeSecurityValidation();
        return Arrays.asList(beforeSecurityRequestFilter);
    }
}