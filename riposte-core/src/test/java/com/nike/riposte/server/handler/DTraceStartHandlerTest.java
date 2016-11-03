package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.wingtips.Span;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.Attribute;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link DTraceStartHandler}
 */
public class DTraceStartHandlerTest {

    private DTraceStartHandler handler;
    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;
    private HttpRequest httpRequest;

    public static final String USER_ID_HEADER_KEY = "someUserId";
    public static final String OTHER_USER_ID_HEADER_KEY = "someOtherUserId";

    public static final List<String> userIdHeaderKeys = Arrays.asList(USER_ID_HEADER_KEY, OTHER_USER_ID_HEADER_KEY);

    private void resetTracingAndMdc() {
        MDC.clear();
        Tracer.getInstance().completeRequestSpan();
    }

    @Before
    public void beforeMethod() {
        handler = new DTraceStartHandler(userIdHeaderKeys);
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/uri");
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();
        resetTracingAndMdc();
    }

    @After
    public void afterMethod() {
        resetTracingAndMdc();
    }

    @Test
    public void doChannelRead_calls_startTrace_if_msg_is_HttpRequest_and_then_returns_CONTINUE() throws Exception {
        // given
        DTraceStartHandler handlerSpy = spy(handler);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, httpRequest);

        // then
        verify(handlerSpy).startTrace(httpRequest);
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void doChannelRead_does_not_call_startTrace_if_msg_is_not_HttpRequest_but_it_does_return_CONTINUE() throws Exception {
        // given
        DTraceStartHandler handlerSpy = spy(handler);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, new Object());

        // then
        verify(handlerSpy, times(0)).startTrace(any());
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void startTrace_creates_trace_from_parent_if_available() {
        // given
        String parentTraceId = UUID.randomUUID().toString();
        String parentParentSpanId = UUID.randomUUID().toString();
        String parentSpanId = UUID.randomUUID().toString();
        String parentSpanName = UUID.randomUUID().toString();
        String parentTraceEnabled = "true";
        String parentUserId = UUID.randomUUID().toString();
        httpRequest.headers().set(TraceHeaders.TRACE_ID, parentTraceId);
        httpRequest.headers().set(TraceHeaders.PARENT_SPAN_ID, parentParentSpanId);
        httpRequest.headers().set(TraceHeaders.SPAN_ID, parentSpanId);
        httpRequest.headers().set(TraceHeaders.SPAN_NAME, parentSpanName);
        httpRequest.headers().set(TraceHeaders.TRACE_SAMPLED, parentTraceEnabled);
        httpRequest.headers().set(USER_ID_HEADER_KEY, parentUserId);

        String expectedSpanName = handler.getSpanName(httpRequest);

        assertThat(Tracer.getInstance().getCurrentSpan(), nullValue());

        // when
        handler.startTrace(httpRequest);

        // then
        Span span = Tracer.getInstance().getCurrentSpan();
        assertThat(span.getTraceId(), is(parentTraceId));
        assertThat(span.getParentSpanId(), is(parentSpanId));
        assertThat(span.getSpanId(), notNullValue());
        assertThat(span.getSpanId(), not(parentSpanId));
        assertThat(span.getSpanName(), is(expectedSpanName));
        assertThat(span.getUserId(), is(parentUserId));
        assertThat(span.isSampleable(), is(Boolean.valueOf(parentTraceEnabled)));
    }

    @Test
    public void startTrace_creates_new_trace_if_no_parent_available() {
        // given
        String expectedSpanName = handler.getSpanName(httpRequest);

        assertThat(Tracer.getInstance().getCurrentSpan(), nullValue());

        // when
        handler.startTrace(httpRequest);

        // then
        Span span = Tracer.getInstance().getCurrentSpan();
        assertThat(span.getTraceId(), notNullValue());
        assertThat(span.getParentSpanId(), nullValue());
        assertThat(span.getSpanId(), notNullValue());
        assertThat(span.getSpanName(), is(expectedSpanName));
        assertThat(span.getUserId(), nullValue());
    }
}