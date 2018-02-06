package com.nike.riposte.client.asynchttp.netty;

import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.ObjectHolder;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.StreamingCallback;
import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.StreamingChannel;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.componenttest.VerifyResponseHttpStatusCodeHandlingRfcCorrectnessComponentTest;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.server.testutils.TestUtil;
import com.nike.riposte.util.Matcher;
import com.nike.wingtips.Span;

import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import static com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient.CHANNEL_IS_BROKEN_ATTR;
import static com.nike.wingtips.Span.SpanPurpose.SERVER;
import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
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

    private Attribute<Boolean> channelIsBrokenAttrMock;

    private ChannelPromise streamChunkChannelPromiseMock;

    private ChannelFuture failedFutureMock;

    private static Server backendServer;
    private static ServerConfig backendServerConfig;
    private static int backendServerPort;

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

        streamingChannelSpy = spy(new StreamingChannel(channelMock, channelPoolMock, callActiveHolder,
                                                       downstreamLastChunkSentHolder, null, null));

        writeAndFlushChannelFutureMock = mock(ChannelFuture.class);

        doReturn(eventLoopMock).when(channelMock).eventLoop();

        doReturn(writeAndFlushChannelFutureMock).when(channelMock).writeAndFlush(contentChunkMock);

        channelIsBrokenAttrMock = mock(Attribute.class);
        doReturn(channelIsBrokenAttrMock).when(channelMock).attr(CHANNEL_IS_BROKEN_ATTR);

        streamChunkChannelPromiseMock = mock(ChannelPromise.class);
        doReturn(streamChunkChannelPromiseMock).when(channelMock).newPromise();

        failedFutureMock = mock(ChannelFuture.class);
        doReturn(failedFutureMock).when(channelMock).newFailedFuture(any(Throwable.class));
    }

    @BeforeClass
    public static void startupServer() throws IOException, CertificateException, InterruptedException {
        backendServerPort = ComponentTestUtils.findFreePort();
        backendServerConfig = new BackendServerConfig(backendServerPort);
        backendServer = new Server(backendServerConfig);
        backendServer.startup();
    }

    @AfterClass
    public static void stopServer() throws InterruptedException {
        backendServerPort = -1;
        backendServer.shutdown();
    }

    @Test
    public void constructor_sets_fields_as_expected() {
        // given
        Deque<Span> spanStackMock = mock(Deque.class);
        Map<String, String> mdcInfoMock = mock(Map.class);

        // when
        StreamingChannel sc = new StreamingChannel(
            channelMock, channelPoolMock, callActiveHolder, downstreamLastChunkSentHolder, spanStackMock, mdcInfoMock
        );

        // then
        assertThat(sc.channel).isSameAs(channelMock);
        assertThat(sc.getChannel()).isSameAs(sc.channel);
        assertThat(sc.pool).isSameAs(channelPoolMock);
        assertThat(sc.callActiveHolder).isSameAs(callActiveHolder);
        assertThat(sc.downstreamLastChunkSentHolder).isSameAs(downstreamLastChunkSentHolder);
        assertThat(sc.distributedTracingSpanStack).isSameAs(spanStackMock);
        assertThat(sc.distributedTracingMdcInfo).isSameAs(mdcInfoMock);
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
        verifyZeroInteractions(streamChunkChannelPromiseMock); // not yet completed
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
        verifyZeroInteractions(channelMock);
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
        ChannelHandlerContext ctx = TestUtil.mockChannelHandlerContext().mockContext;
        StreamingCallback streamingCallback = mock(StreamingCallback.class);

        // when
        new StreamingAsyncHttpClient(200, 200, true)
                .streamDownstreamCall(downstreamHost, downstreamPort, request, isSecure, false, streamingCallback, 200, true, ctx);

        // then
        assertThat(request.headers().get(HOST)).isEqualTo(expectedHostHeader);
    }

    @DataProvider(value = {
            "false",
            "true"
    }, splitBy = "\\|")
    @Test
    public void streamDownstreamCall_createsSubSpanAroundDownstreamCallBasedOnFlag(boolean performSubSpanAroundDownstreamCalls) throws InterruptedException, ExecutionException {
        // given
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "");
        ChannelHandlerContext ctx = TestUtil.mockChannelHandlerContextWithTraceInfo().mockContext;
        StreamingCallback streamingCallback = mock(StreamingCallback.class);
        Span currentSpan = Tracer.getInstance().getCurrentSpan();

        // when
        new StreamingAsyncHttpClient(200, 200, true)
                .streamDownstreamCall("localhost", backendServerPort, request, false, false, streamingCallback, 200, performSubSpanAroundDownstreamCalls, ctx).get();

        // then
        if (performSubSpanAroundDownstreamCalls) {
            assertThat(request.headers().get(TraceHeaders.TRACE_SAMPLED)).isEqualTo(String.valueOf(currentSpan.isSampleable())); // should match
            assertThat(request.headers().get(TraceHeaders.TRACE_ID)).isEqualTo(currentSpan.getTraceId()); // should match
            assertThat(request.headers().get(TraceHeaders.SPAN_ID)).isNotEqualTo(currentSpan.getSpanId()); // should have generated a new one
            assertThat(request.headers().get(TraceHeaders.PARENT_SPAN_ID)).isEqualTo(currentSpan.getSpanId()); // parent equals our starting spanId
            assertThat(request.headers().get(TraceHeaders.SPAN_NAME)).isEqualTo("async_downstream_call-GET_localhost:" + backendServerPort); // generated span name for new SubSpan
        } else {
            assertDefaultTraceHeadersWereAddedToRequest(request, currentSpan);
        }

        // cleanup after this test
        Tracer.getInstance().completeRequestSpan();
    }

    private void assertDefaultTraceHeadersWereAddedToRequest(DefaultHttpRequest request, Span currentSpan) {
        assertThat(request.headers().get(TraceHeaders.TRACE_SAMPLED)).isEqualTo(String.valueOf(currentSpan.isSampleable()));
        assertThat(request.headers().get(TraceHeaders.TRACE_ID)).isEqualTo(currentSpan.getTraceId());
        assertThat(request.headers().get(TraceHeaders.SPAN_ID)).isEqualTo(currentSpan.getSpanId());
        assertThat(request.headers().get(TraceHeaders.PARENT_SPAN_ID)).isNullOrEmpty(); // no parent since only request span is started
        assertThat(request.headers().get(TraceHeaders.SPAN_NAME)).isEqualTo("mockChannelHandlerContext");
    }

    private void verifyChannelReleasedBackToPool(ObjectHolder<Boolean> callActiveHolder,
                                                 ChannelPool theChannelPoolMock,
                                                 Channel theChannelMock) {
        assertThat(callActiveHolder.heldObject).isFalse();
        verify(theChannelPoolMock).release(theChannelMock);
    }

    public static class BackendServerConfig implements ServerConfig {
        private final int port;
        private final Endpoint<?> backendEndpoint;

        public BackendServerConfig(int port) {
            this.port = port;
            this.backendEndpoint = new BackendEndpoint();
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return Collections.singleton(backendEndpoint);
        }

        @Override
        public int endpointsPort() {
            return port;
        }

        @Override
        public long workerChannelIdleTimeoutMillis() {
            return -1;
        }
    }

    public static class BackendEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/";

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                    ResponseInfo.newBuilder("{\"success\":true}")
                            .withHttpStatusCode(200)
                            .withDesiredContentWriterMimeType("application/json")
                            .build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }

    }
}