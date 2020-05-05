package com.nike.riposte.client.asynchttp.netty;

import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.ObjectHolder;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.StreamingCallback;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.StreamingChannel;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.ProxyRouterSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Deque;
import java.util.Map;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import static com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.CHANNEL_IS_BROKEN_ATTR;
import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the functionality of {@link StreamingAsyncHttpClient}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class StreamingAsyncHttpClientTest {

    private Channel channelMock;
    private ChannelPool channelPoolMock;
    private EventLoop eventLoopMock;
    private ObjectHolder<Boolean> callActiveHolder;
    private ObjectHolder<Boolean> downstreamLastChunkSentHolder;
    private StreamingChannel streamingChannelSpy;
    private HttpContent contentChunkMock;
    private ChannelFuture writeAndFlushChannelFutureMock;
    private Span spanForDownstreamCallMock;
    private ProxyRouterSpanNamingAndTaggingStrategy<Span> proxySpanTaggingStrategyMock;

    private Attribute<Boolean> channelIsBrokenAttrMock;

    private ChannelPromise streamChunkChannelPromiseMock;

    private ChannelFuture failedFutureMock;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        channelPoolMock = mock(ChannelPool.class);
        eventLoopMock = mock(EventLoop.class);

        contentChunkMock = mock(HttpContent.class);

        callActiveHolder = new ObjectHolder<>();
        callActiveHolder.heldObject = true;

        downstreamLastChunkSentHolder = new ObjectHolder<>();
        downstreamLastChunkSentHolder.heldObject = false;

        spanForDownstreamCallMock = mock(Span.class);
        proxySpanTaggingStrategyMock = mock(ProxyRouterSpanNamingAndTaggingStrategy.class);

        streamingChannelSpy = spy(new StreamingChannel(
            channelMock, channelPoolMock, callActiveHolder, downstreamLastChunkSentHolder, null, null,
            spanForDownstreamCallMock, proxySpanTaggingStrategyMock
        ));

        writeAndFlushChannelFutureMock = mock(ChannelFuture.class);

        doReturn(eventLoopMock).when(channelMock).eventLoop();

        doReturn(writeAndFlushChannelFutureMock).when(channelMock).writeAndFlush(contentChunkMock);

        channelIsBrokenAttrMock = mock(Attribute.class);
        doReturn(channelIsBrokenAttrMock).when(channelMock).attr(CHANNEL_IS_BROKEN_ATTR);

        streamChunkChannelPromiseMock = mock(ChannelPromise.class);
        doReturn(streamChunkChannelPromiseMock).when(channelMock).newPromise();

        failedFutureMock = mock(ChannelFuture.class);
        doReturn(failedFutureMock).when(channelMock).newFailedFuture(any(Throwable.class));

        resetTracing();
    }

    @After
    public void afterMethod() {
        resetTracing();
    }

    private void resetTracing() {
        Tracer.getInstance().completeRequestSpan();
    }

    @Test
    public void StreamingChannel_constructor_sets_fields_as_expected() {
        // given
        Deque<Span> spanStackMock = mock(Deque.class);
        Map<String, String> mdcInfoMock = mock(Map.class);

        // when
        StreamingChannel sc = new StreamingChannel(
            channelMock, channelPoolMock, callActiveHolder, downstreamLastChunkSentHolder, spanStackMock, mdcInfoMock,
            spanForDownstreamCallMock, proxySpanTaggingStrategyMock
        );

        // then
        assertThat(sc.channel).isSameAs(channelMock);
        assertThat(sc.getChannel()).isSameAs(sc.channel);
        assertThat(sc.pool).isSameAs(channelPoolMock);
        assertThat(sc.callActiveHolder).isSameAs(callActiveHolder);
        assertThat(sc.downstreamLastChunkSentHolder).isSameAs(downstreamLastChunkSentHolder);
        assertThat(sc.distributedTracingSpanStack).isSameAs(spanStackMock);
        assertThat(sc.distributedTracingMdcInfo).isSameAs(mdcInfoMock);
        assertThat(sc.spanForDownstreamCall).isSameAs(spanForDownstreamCallMock);
        assertThat(sc.proxySpanTaggingStrategy).isSameAs(proxySpanTaggingStrategyMock);
    }

    @Test
    public void StreamingChannel_streamChunk_sets_up_task_in_event_loop_to_call_doStreamChunk_and_adds_listener_to_complete_promise()
            throws Exception {
        // given
        ChannelFuture doStreamChunkFutureMock = mock(ChannelFuture.class);
        doReturn(doStreamChunkFutureMock).when(streamingChannelSpy).doStreamChunk(any(HttpContent.class));

        // when
        ChannelFuture result = streamingChannelSpy.streamChunk(contentChunkMock);

        // then
        assertThat(result).isSameAs(streamChunkChannelPromiseMock);
        verifyNoInteractions(streamChunkChannelPromiseMock); // not yet completed
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(eventLoopMock).execute(taskCaptor.capture());
        Runnable task = taskCaptor.getValue();

        // and given
        verify(streamingChannelSpy, never()).doStreamChunk(any(HttpContent.class)); // not yet called

        // when
        task.run();

        // then
        verify(streamingChannelSpy).doStreamChunk(contentChunkMock);
        ArgumentCaptor<GenericFutureListener> listenerCaptor = ArgumentCaptor.forClass(GenericFutureListener.class);
        verify(doStreamChunkFutureMock).addListener(listenerCaptor.capture());
        GenericFutureListener listener = listenerCaptor.getValue();
        assertThat(listener).isNotNull();

        // and when
        listener.operationComplete(getFutureForCase(true, false, null));

        // then
        verify(streamChunkChannelPromiseMock).cancel(true);
        verifyNoMoreInteractions(streamChunkChannelPromiseMock);

        // and when
        listener.operationComplete(getFutureForCase(false, true, null));

        // then
        verify(streamChunkChannelPromiseMock).setSuccess();
        verifyNoMoreInteractions(streamChunkChannelPromiseMock);

        // and when
        Throwable normalFutureFailure = new RuntimeException("normal future failure");
        listener.operationComplete(getFutureForCase(false, false, normalFutureFailure));

        // then
        verify(streamChunkChannelPromiseMock).setFailure(normalFutureFailure);
        verifyNoMoreInteractions(streamChunkChannelPromiseMock);

        // and when
        reset(streamChunkChannelPromiseMock);
        listener.operationComplete(getFutureForCase(false, false, null));

        // then
        ArgumentCaptor<Throwable> throwableCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(streamChunkChannelPromiseMock).setFailure(throwableCaptor.capture());
        assertThat(throwableCaptor.getValue()).hasMessage("Received ChannelFuture that was in an impossible state");
        verifyNoMoreInteractions(streamChunkChannelPromiseMock);
    }

    private Future getFutureForCase(boolean isCanceled, boolean isSuccess, Throwable failureCause) {
        Future futureMock = mock(Future.class);
        doReturn(isCanceled).when(futureMock).isCancelled();
        doReturn(isSuccess).when(futureMock).isSuccess();
        doReturn(failureCause).when(futureMock).cause();
        return futureMock;
    }

    @Test
    public void StreamingChannel_streamChunk_fails_promise_with_unexpected_exception() {
        // given
        Throwable crazyEx = new RuntimeException("kaboom");
        doThrow(crazyEx).when(eventLoopMock).execute(any(Runnable.class));

        // when
        ChannelFuture result = streamingChannelSpy.streamChunk(contentChunkMock);

        // then
        verifyFailedChannelFuture(result, "StreamingChannel.streamChunk() threw an exception", crazyEx);
    }

    private void verifyFailedChannelFuture(ChannelFuture result,
                                           String expectedExceptionMessagePrefix,
                                           Throwable expectedExCause) {
        assertThat(result).isSameAs(failedFutureMock);
        ArgumentCaptor<Throwable> exCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(channelMock).newFailedFuture(exCaptor.capture());
        Throwable exThatFailedTheFuture = exCaptor.getValue();
        assertThat(exThatFailedTheFuture).hasMessageStartingWith(expectedExceptionMessagePrefix);
        if (expectedExCause != null)
            assertThat(exThatFailedTheFuture).hasCause(expectedExCause);
    }

    @Test
    public void StreamingChannel_doStreamChunk_works_as_expected_when_last_chunk_already_sent_downstream_and_incoming_chunk_is_empty_last_chunk() {
        // given
        streamingChannelSpy.downstreamLastChunkSentHolder.heldObject = true;

        LastHttpContent contentChunkMock = mock(LastHttpContent.class);
        ByteBuf contentByteBufMock = mock(ByteBuf.class);
        doReturn(contentByteBufMock).when(contentChunkMock).content();
        doReturn(0).when(contentByteBufMock).readableBytes();

        ChannelFuture successFutureMock = mock(ChannelFuture.class);
        doReturn(successFutureMock).when(channelMock).newSucceededFuture();

        // when
        ChannelFuture result = streamingChannelSpy.doStreamChunk(contentChunkMock);

        // then
        verify(channelMock, never()).writeAndFlush(any(Object.class));
        verify(contentChunkMock).release();
        verify(channelMock).newSucceededFuture();
        assertThat(result).isSameAs(successFutureMock);
    }

    @DataProvider(value = {
            "false  |   0",
            "true   |   42"
    }, splitBy = "\\|")
    @Test
    public void StreamingChannel_doStreamChunk_works_as_expected_when_last_chunk_already_sent_downstream_and_incoming_chunk_does_not_match_requirements(
            boolean chunkIsLastChunk, int readableBytes
    ) {
        // given
        streamingChannelSpy.downstreamLastChunkSentHolder.heldObject = true;
        streamingChannelSpy.callActiveHolder.heldObject = true;
        streamingChannelSpy.channelClosedDueToUnrecoverableError = false;

        HttpContent contentChunkMock = (chunkIsLastChunk) ? mock(LastHttpContent.class) : mock(HttpContent.class);
        ByteBuf contentByteBufMock = mock(ByteBuf.class);
        doReturn(contentByteBufMock).when(contentChunkMock).content();
        doReturn(readableBytes).when(contentByteBufMock).readableBytes();

        doReturn(writeAndFlushChannelFutureMock).when(channelMock).writeAndFlush(contentChunkMock);

        // when
        ChannelFuture result = streamingChannelSpy.doStreamChunk(contentChunkMock);

        // then
        verify(channelMock).writeAndFlush(contentChunkMock);
        assertThat(result).isSameAs(writeAndFlushChannelFutureMock);
    }

    @Test
    public void StreamingChannel_doStreamChunk_works_as_expected_when_downstream_call_is_not_active() {
        // given
        streamingChannelSpy.callActiveHolder.heldObject = false;

        // when
        ChannelFuture result = streamingChannelSpy.doStreamChunk(contentChunkMock);

        // then
        verify(channelMock, never()).writeAndFlush(any(Object.class));
        verify(contentChunkMock).release();

        verifyFailedChannelFuture(
                result, "Unable to stream chunk - downstream call is no longer active.", null
        );
    }

    @Test
    public void StreamingChannel_doStreamChunk_works_as_expected_when_closeChannelDueToUnrecoverableError_was_called_previously() {
        // given
        streamingChannelSpy.channelClosedDueToUnrecoverableError = true;

        // when
        ChannelFuture result = streamingChannelSpy.doStreamChunk(contentChunkMock);

        // then
        verify(channelMock, never()).writeAndFlush(any(Object.class));
        verify(contentChunkMock).release();

        verifyFailedChannelFuture(
                result, "Unable to stream chunks downstream - the channel was closed previously due to an unrecoverable error", null
        );
    }

    @Test
    public void StreamingChannel_doStreamChunk_works_as_expected_when_crazy_exception_is_thrown() {
        // given
        Throwable crazyEx = new RuntimeException("kaboom");
        doThrow(crazyEx).when(channelMock).writeAndFlush(any(Object.class));

        // when
        ChannelFuture result = streamingChannelSpy.doStreamChunk(contentChunkMock);

        // then
        verify(channelMock).writeAndFlush(any(Object.class));
        verify(contentChunkMock, never()).release();

        verifyFailedChannelFuture(
                result, "StreamingChannel.doStreamChunk() threw an exception", crazyEx
        );
    }

    @Test
    public void StreamingChannel_closeChannelDueToUnrecoverableError_calls_the_do_method_and_sets_field_to_true_when_not_closed_and_call_active() {
        // given
        Throwable unrecoverableError = new RuntimeException("kaboom");
        streamingChannelSpy.channelClosedDueToUnrecoverableError = false;
        streamingChannelSpy.callActiveHolder.heldObject = true;

        // when
        streamingChannelSpy.closeChannelDueToUnrecoverableError(unrecoverableError);

        // then
        assertThat(streamingChannelSpy.channelClosedDueToUnrecoverableError).isTrue();
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(eventLoopMock).execute(taskCaptor.capture());
        Runnable task = taskCaptor.getValue();
        assertThat(task).isNotNull();

        // and given
        verify(streamingChannelSpy, never()).doCloseChannelDueToUnrecoverableError(any(Throwable.class));

        // when
        task.run();

        // then
        verify(streamingChannelSpy).doCloseChannelDueToUnrecoverableError(unrecoverableError);
    }

    @DataProvider(value = {
            "true   |   true",
            "false  |   false",
            "true   |   false"
    }, splitBy = "\\|")
    @Test
    public void StreamingChannel_closeChannelDueToUnrecoverableError_sets_field_to_true_but_otherwise_does_nothing_if_already_closed_or_call_inactive(
            boolean alreadyClosed, boolean callActive
    ) {
        // given
        Throwable unrecoverableError = new RuntimeException("kaboom");
        streamingChannelSpy.channelClosedDueToUnrecoverableError = alreadyClosed;
        streamingChannelSpy.callActiveHolder.heldObject = callActive;

        // when
        streamingChannelSpy.closeChannelDueToUnrecoverableError(unrecoverableError);

        // then
        assertThat(streamingChannelSpy.channelClosedDueToUnrecoverableError).isTrue();
        verifyNoInteractions(channelMock);
    }

    @Test
    public void StreamingChannel_closeChannelDueToUnrecoverableError_sets_field_to_true_even_if_crazy_exception_occurs() {
        // given
        streamingChannelSpy.channelClosedDueToUnrecoverableError = false;
        streamingChannelSpy.callActiveHolder.heldObject = true;
        Throwable crazyEx = new RuntimeException("kaboom");
        doThrow(crazyEx).when(channelMock).eventLoop();

        // when
        Throwable caughtEx = catchThrowable(
                () -> streamingChannelSpy.closeChannelDueToUnrecoverableError(new RuntimeException("some other error"))
        );

        // then
        assertThat(caughtEx).isSameAs(crazyEx);
        assertThat(streamingChannelSpy.channelClosedDueToUnrecoverableError).isTrue();
    }

    @DataProvider(value = {
            "true",
            "false"
    })
    @Test
    public void StreamingChannel_doCloseChannelDueToUnrecoverableError_works_as_expected(boolean callActive) {
        // given
        streamingChannelSpy.callActiveHolder.heldObject = callActive;
        Throwable unrecoverableError = new RuntimeException("kaboom");

        // when
        streamingChannelSpy.doCloseChannelDueToUnrecoverableError(unrecoverableError);

        // then
        if (callActive) {
            verify(channelIsBrokenAttrMock).set(true);
            verifyChannelReleasedBackToPool(streamingChannelSpy.callActiveHolder, channelPoolMock, channelMock);
            verify(channelMock).close();
        }
        else {
            verify(channelIsBrokenAttrMock, never()).set(anyBoolean());
            verify(channelMock, never()).close();
        }

    }

    @DataProvider(value = {
            "80   | false | localhost | localhost",
            "80   | true  | localhost | localhost:80",
            "8080 | false | localhost | localhost:8080",
            "443  | true  | localhost | localhost",
            "443  | false | localhost | localhost:443",
            "8080 | true  | localhost | localhost:8080",
    }, splitBy = "\\|")
    @Test
    public void streamDownstreamCall_setsHostHeaderCorrectly(int downstreamPort, boolean isSecure, String downstreamHost, String expectedHostHeader) {
        // given
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "");
        ChannelHandlerContext ctx = mockChannelHandlerContext();
        StreamingCallback streamingCallback = mock(StreamingCallback.class);

        ProxyRouterProcessingState proxyState = ChannelAttributes.getProxyRouterProcessingStateForChannel(ctx).get();
        RequestInfo<?> requestInfoMock = mock(RequestInfo.class);

        // when
        new StreamingAsyncHttpClient(
            200, 200, true, mock(DistributedTracingConfig.class)
        ).streamDownstreamCall(
            downstreamHost, downstreamPort, request, isSecure, false, streamingCallback, 200, true, true,
            proxyState, requestInfoMock, ctx
        );

        // then
        assertThat(request.headers().get(HOST)).isEqualTo(expectedHostHeader);
    }

    private ChannelHandlerContext mockChannelHandlerContext() {
        ChannelHandlerContext mockContext = mock(ChannelHandlerContext.class);
        when(mockContext.channel()).thenReturn(mock(Channel.class));
        @SuppressWarnings("unchecked")
        Attribute<HttpProcessingState> mockHttpProcessingStateAttribute = mock(Attribute.class);
        Attribute<ProxyRouterProcessingState> mockProxyStateAttribute = mock(Attribute.class);
        when(mockContext.channel().attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY)).thenReturn(mockHttpProcessingStateAttribute);
        when(mockContext.channel().attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY).get()).thenReturn(mock(HttpProcessingState.class));
        when(mockContext.channel().attr(ChannelAttributes.PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY)).thenReturn(mockProxyStateAttribute);
        when(mockContext.channel().attr(ChannelAttributes.PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY).get()).thenReturn(mock(ProxyRouterProcessingState.class));
        return mockContext;
    }

    private void verifyChannelReleasedBackToPool(ObjectHolder<Boolean> callActiveHolder,
                                                 ChannelPool theChannelPoolMock,
                                                 Channel theChannelMock) {
        assertThat(callActiveHolder.heldObject).isFalse();
        verify(theChannelPoolMock).release(theChannelMock);
    }

    private enum GetSubspanSpanNameScenario {
        STRATEGY_INITIAL_AND_OVERRIDE_SPAN_NAMES_EXIST(
            "someInitialName", "someOverrideName", false, "notUsed", "someOverrideName"
        ),
        STRATEGY_INITIAL_AND_OVERRIDE_SPAN_NAMES_EXIST_WITH_NULL_OVERALL_SPAN(
            "someInitialName", "someOverrideName", true, "notUsed", "someOverrideName"
        ),
        STRATEGY_INITAL_NAME_EXISTS_AND_OVERRIDE_IS_NULL(
            "someInitialName", null, false, "notUsed", "someInitialName"
        ),
        STRATEGY_INITAL_NAME_EXISTS_AND_OVERRIDE_IS_BLANK(
            "someInitialName", "   ", false, "notUsed", "someInitialName"
        ),
        STRATEGY_INITAL_NAME_IS_NULL_AND_OVERRIDE_EXISTS(
            null, "someOverrideName", false, "notUsed", "someOverrideName"
        ),
        STRATEGY_INITAL_NAME_IS_BLANK_AND_OVERRIDE_EXISTS(
            "   ", "someOverrideName", false, "notUsed", "someOverrideName"
        ),
        STRATEGY_INITAL_NAME_IS_MISSING_AND_OVERRIDE_EXISTS_WITH_NULL_OVERALL_SPAN(
            null, "someOverrideName", true, "notUsed", "someOverrideName"
        ),
        STRATEGY_AND_OVERRIDE_SPAN_NAMES_ARE_NULL(
            null, null, false, "someFallbackName", "someFallbackName"
        ),
        STRATEGY_AND_OVERRIDE_SPAN_NAMES_ARE_BLANK(
            "   ", "   ", false, "someFallbackName", "someFallbackName"
        );

        public final String strategyInitialName;
        public final String strategyInitialNameOverride;
        public final boolean overallRequestSpanIsNull;
        public final String fallbackSpanName;
        public final String expectedResult;

        GetSubspanSpanNameScenario(
            String strategyInitialName,
            String strategyInitialNameOverride,
            boolean overallRequestSpanIsNull,
            String fallbackSpanName,
            String expectedResult
        ) {
            this.strategyInitialName = strategyInitialName;
            this.strategyInitialNameOverride = strategyInitialNameOverride;
            this.overallRequestSpanIsNull = overallRequestSpanIsNull;
            this.fallbackSpanName = fallbackSpanName;
            this.expectedResult = expectedResult;
        }
    }

    @DataProvider(value = {
        "STRATEGY_INITIAL_AND_OVERRIDE_SPAN_NAMES_EXIST",
        "STRATEGY_INITIAL_AND_OVERRIDE_SPAN_NAMES_EXIST_WITH_NULL_OVERALL_SPAN",
        "STRATEGY_INITAL_NAME_EXISTS_AND_OVERRIDE_IS_NULL",
        "STRATEGY_INITAL_NAME_EXISTS_AND_OVERRIDE_IS_BLANK",
        "STRATEGY_INITAL_NAME_IS_NULL_AND_OVERRIDE_EXISTS",
        "STRATEGY_INITAL_NAME_IS_BLANK_AND_OVERRIDE_EXISTS",
        "STRATEGY_INITAL_NAME_IS_MISSING_AND_OVERRIDE_EXISTS_WITH_NULL_OVERALL_SPAN",
        "STRATEGY_AND_OVERRIDE_SPAN_NAMES_ARE_NULL",
        "STRATEGY_AND_OVERRIDE_SPAN_NAMES_ARE_BLANK",
    })
    @Test
    public void getSubspanSpanName_works_as_expected(GetSubspanSpanNameScenario scenario) {
        // given
        String overallRequestSpanName = null;

        if (scenario.overallRequestSpanIsNull) {
            assertThat(Tracer.getInstance().getCurrentSpan()).isNull();
        }
        else {
            overallRequestSpanName = "overall-request-spanName_" + UUID.randomUUID().toString();
            Tracer.getInstance().startRequestWithRootSpan(overallRequestSpanName);
        }

        DummyProxyRouterSpanNamingAndTaggingStrategy namingStrategySpy = spy(
            new DummyProxyRouterSpanNamingAndTaggingStrategy(
                scenario.strategyInitialName, scenario.strategyInitialNameOverride
            )
        );

        HttpRequest nettyRequestMock = mock(HttpRequest.class);
        RequestInfo<?> riposteRequestMock = mock(RequestInfo.class);

        StreamingAsyncHttpClient implSpy = spy(new StreamingAsyncHttpClient(
            200, 200, true, mock(DistributedTracingConfig.class)
        ));

        doReturn(scenario.fallbackSpanName).when(implSpy).getFallbackSpanName(nettyRequestMock);

        // when
        String result = implSpy.getSubspanSpanName(nettyRequestMock, riposteRequestMock, namingStrategySpy);

        // then
        verify(namingStrategySpy).doGetInitialSpanNameOverride(
            nettyRequestMock, riposteRequestMock, scenario.strategyInitialName, overallRequestSpanName
        );
        assertThat(result).isEqualTo(scenario.expectedResult);
    }

    private enum GetFallbackSpanNameScenario {
        NORMAL(HttpMethod.GET, false, "async_downstream_call-GET"),
        NULL_HTTP_METHOD(null, false, "async_downstream_call-UNKNOWN_HTTP_METHOD"),
        UNEXPECTED_EXCEPTION_IS_THROWN(HttpMethod.GET, true, "async_downstream_call-UNKNOWN_HTTP_METHOD");

        public final HttpMethod httpMethod;
        public final boolean throwUnexpectedException;
        public final String expectedResult;

        GetFallbackSpanNameScenario(
            HttpMethod httpMethod, boolean throwUnexpectedException, String expectedResult
        ) {
            this.httpMethod = httpMethod;
            this.throwUnexpectedException = throwUnexpectedException;
            this.expectedResult = expectedResult;
        }
    }

    @DataProvider(value = {
        "NORMAL",
        "NULL_HTTP_METHOD",
        "UNEXPECTED_EXCEPTION_IS_THROWN",
    })
    @Test
    public void getFallbackSpanName_works_as_expected(GetFallbackSpanNameScenario scenario) {
        // given
        HttpRequest nettyRequestMock = mock(HttpRequest.class);
        HttpMethod httpMethodSpy = (scenario.httpMethod == null) ? null : spy(scenario.httpMethod);
        if (scenario.throwUnexpectedException) {
            doThrow(new RuntimeException("intentional test exception")).when(httpMethodSpy).name();
        }
        doReturn(httpMethodSpy).when(nettyRequestMock).method();
        
        StreamingAsyncHttpClient impl = new StreamingAsyncHttpClient(
            200, 200, true, mock(DistributedTracingConfig.class)
        );

        // when
        String result = impl.getFallbackSpanName(nettyRequestMock);

        // then
        assertThat(result).isEqualTo(scenario.expectedResult);
    }

    private static class DummyProxyRouterSpanNamingAndTaggingStrategy extends ProxyRouterSpanNamingAndTaggingStrategy<Span> {

        public final String initialSpanName;
        public final String initialSpanNameOverride;

        private DummyProxyRouterSpanNamingAndTaggingStrategy(
            String initialSpanName, String initialSpanNameOverride
        ) {
            this.initialSpanName = initialSpanName;
            this.initialSpanNameOverride = initialSpanNameOverride;
        }

        @Override
        protected @Nullable String doGetInitialSpanName(
            @NotNull HttpRequest request
        ) {
            return initialSpanName;
        }

        @Override
        protected @Nullable String doGetInitialSpanNameOverride(
            @NotNull HttpRequest downstreamRequest, @NotNull RequestInfo<?> overallRequest,
            @Nullable String initialSpanName, @Nullable String overallRequestSpanName
        ) {
            return initialSpanNameOverride;
        }

        @Override
        protected void doChangeSpanName(@NotNull Span span, @NotNull String newName) { }

        @Override
        protected void doHandleRequestTagging(@NotNull Span span, @NotNull HttpRequest request) { }

        @Override
        protected void doHandleResponseTaggingAndFinalSpanName(
            @NotNull Span span, @Nullable HttpRequest request, @Nullable HttpResponse response,
            @Nullable Throwable error
        ) { }
    }

}