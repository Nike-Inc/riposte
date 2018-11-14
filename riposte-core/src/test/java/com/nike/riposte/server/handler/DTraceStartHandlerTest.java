package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.config.distributedtracing.DefaultRiposteServerSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfigImpl;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.InitialSpanNameArgs;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.RequestTaggingArgs;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.ResponseTaggingArgs;
import com.nike.wingtips.Span;
import com.nike.wingtips.Span.TimestampedAnnotation;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;
import com.nike.wingtips.tags.HttpTagAndSpanNamingStrategy;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.MDC;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link DTraceStartHandler}
 */
@RunWith(DataProviderRunner.class)
public class DTraceStartHandlerTest {

    private DTraceStartHandler handler;
    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;
    private HttpRequest httpRequest;
    private RequestInfo<?> requestInfoMock;
    private DistributedTracingConfig<Span> distributedTracingConfig;

    private HttpTagAndSpanNamingStrategy<RequestInfo<?>, ResponseInfo<?>> tagAndNamingStrategy;
    private HttpTagAndSpanNamingAdapter<RequestInfo<?>, ResponseInfo<?>> tagAndNamingAdapterMock;
    private AtomicReference<String> initialSpanNameFromStrategy;
    private AtomicBoolean strategyInitialSpanNameMethodCalled;
    private AtomicBoolean strategyRequestTaggingMethodCalled;
    private AtomicBoolean strategyResponseTaggingAndFinalSpanNameMethodCalled;
    private AtomicReference<InitialSpanNameArgs> strategyInitialSpanNameArgs;
    private AtomicReference<RequestTaggingArgs> strategyRequestTaggingArgs;
    private AtomicReference<ResponseTaggingArgs> strategyResponseTaggingArgs;

    private boolean shouldAddWireReceiveStartAnnotation = true;
    private boolean shouldAddWireReceiveFinishAnnotation = true;

    public static final String USER_ID_HEADER_KEY = "someUserId";
    public static final String OTHER_USER_ID_HEADER_KEY = "someOtherUserId";

    public static final List<String> userIdHeaderKeys = Arrays.asList(USER_ID_HEADER_KEY, OTHER_USER_ID_HEADER_KEY);

    private void resetTracingAndMdc() {
        MDC.clear();
        Tracer.getInstance().completeRequestSpan();
    }

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/uri");
        requestInfoMock = mock(RequestInfo.class);
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();
        state.setRequestInfo(requestInfoMock);

        initialSpanNameFromStrategy = new AtomicReference<>("span-name-from-strategy-" + UUID.randomUUID().toString());
        strategyInitialSpanNameMethodCalled = new AtomicBoolean(false);
        strategyRequestTaggingMethodCalled = new AtomicBoolean(false);
        strategyResponseTaggingAndFinalSpanNameMethodCalled = new AtomicBoolean(false);
        strategyInitialSpanNameArgs = new AtomicReference<>(null);
        strategyRequestTaggingArgs = new AtomicReference<>(null);
        strategyResponseTaggingArgs = new AtomicReference<>(null);
        tagAndNamingStrategy = new ArgCapturingHttpTagAndSpanNamingStrategy(
            initialSpanNameFromStrategy, strategyInitialSpanNameMethodCalled, strategyRequestTaggingMethodCalled,
            strategyResponseTaggingAndFinalSpanNameMethodCalled, strategyInitialSpanNameArgs,
            strategyRequestTaggingArgs, strategyResponseTaggingArgs
        );
        tagAndNamingAdapterMock = mock(HttpTagAndSpanNamingAdapter.class);

        distributedTracingConfig = new DistributedTracingConfigImpl<>(
            new DefaultRiposteServerSpanNamingAndTaggingStrategy(tagAndNamingStrategy, tagAndNamingAdapterMock) {
                @Override
                public boolean shouldAddWireReceiveStartAnnotation() {
                    return shouldAddWireReceiveStartAnnotation;
                }

                @Override
                public boolean shouldAddWireReceiveFinishAnnotation() {
                    return shouldAddWireReceiveFinishAnnotation;
                }
            },
            Span.class
        );

        handler = new DTraceStartHandler(userIdHeaderKeys, distributedTracingConfig);

        resetTracingAndMdc();
    }

    @After
    public void afterMethod() {
        resetTracingAndMdc();
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_distributedTracingConfig_is_null() {
        // when
        Throwable ex = catchThrowable(
            () -> new DTraceStartHandler(userIdHeaderKeys, null)
        );

        // then
        Assertions.assertThat(ex)
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("distributedTracingConfig cannot be null");
    }

    @Test
    public void doChannelRead_calls_startTrace_if_msg_is_HttpRequest_and_then_returns_CONTINUE() throws Exception {
        // given
        DTraceStartHandler handlerSpy = spy(handler);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, httpRequest);

        // then
        verify(handlerSpy).startTrace(httpRequest, ctxMock);
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void doChannelRead_does_not_call_startTrace_if_msg_is_not_HttpRequest_but_it_does_return_CONTINUE() throws Exception {
        // given
        DTraceStartHandler handlerSpy = spy(handler);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, new Object());

        // then
        verify(handlerSpy, times(0)).startTrace(any(), any());
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void doChannelRead_does_not_propagate_exception_if_startTrace_throws_an_error() {
        // given
        DTraceStartHandler handlerSpy = spy(handler);
        doThrow(new RuntimeException("intentional exception")).when(handlerSpy).startTrace(any(), any());

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, httpRequest);

        // then
        verify(handlerSpy).startTrace(any(), any());
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void startTrace_creates_trace_from_parent_if_available_and_sets_expected_span_name_and_tags_and_annotations() {
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

        assertThat(Tracer.getInstance().getCurrentSpan(), nullValue());

        // when
        long nanosBefore = System.nanoTime();
        handler.startTrace(httpRequest, ctxMock);
        long nanosAfter = System.nanoTime();

        // then
        Span span = Tracer.getInstance().getCurrentSpan();
        assertThat(span.getTraceId(), is(parentTraceId));
        assertThat(span.getParentSpanId(), is(parentSpanId));
        assertThat(span.getSpanId(), notNullValue());
        assertThat(span.getSpanId(), not(parentSpanId));
        assertThat(span.getSpanName(), is(initialSpanNameFromStrategy.get()));
        assertThat(span.getUserId(), is(parentUserId));
        assertThat(span.isSampleable(), is(Boolean.valueOf(parentTraceEnabled)));

        strategyInitialSpanNameArgs.get().verifyArgs(requestInfoMock, tagAndNamingAdapterMock);
        strategyRequestTaggingArgs.get().verifyArgs(span, requestInfoMock, tagAndNamingAdapterMock);

        long expectedMaxWireReceiveStartTimestamp =
            span.getSpanStartTimeEpochMicros() + TimeUnit.NANOSECONDS.toMicros(nanosAfter - nanosBefore);
        TimestampedAnnotation wireReceiveStartAnnotation = findAnnotationInSpan(span, "wr.start");
        Assertions.assertThat(wireReceiveStartAnnotation.getTimestampEpochMicros())
                  .isBetween(span.getSpanStartTimeEpochMicros(), expectedMaxWireReceiveStartTimestamp);
    }

    private TimestampedAnnotation findAnnotationInSpan(Span span, String annotationValue) {
        return span
            .getTimestampedAnnotations()
            .stream()
            .filter(ta -> annotationValue.equals(ta.getValue()))
            .findFirst()
            .orElseThrow(() -> new RuntimeException(
                "Expected to find annotation with value: \"" + annotationValue + "\", but none was found"
            ));
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true",
        "false  |   false"
    }, splitBy = "\\|")
    @Test
    public void startTrace_creates_new_trace_if_no_parent_available_and_sets_expected_span_name_and_tags_and_annotations(
        boolean addWireReceiveStartAnnotation, boolean stateIsNull
    ) {
        // given
        assertThat(Tracer.getInstance().getCurrentSpan(), nullValue());
        shouldAddWireReceiveStartAnnotation = addWireReceiveStartAnnotation;
        if (stateIsNull) {
            doReturn(null).when(stateAttributeMock).get();
        }

        DTraceStartHandler handlerSpy = spy(handler);
        String fallbackSpanName = "fallback-span-name-" + UUID.randomUUID().toString();
        doReturn(fallbackSpanName).when(handlerSpy).getFallbackSpanName(any());

        String expectedSpanName = (stateIsNull) ? fallbackSpanName : initialSpanNameFromStrategy.get();

        // when
        long nanosBefore = System.nanoTime();
        handlerSpy.startTrace(httpRequest, ctxMock);
        long nanosAfter = System.nanoTime();

        // then
        Span span = Tracer.getInstance().getCurrentSpan();
        assertThat(span.getTraceId(), notNullValue());
        assertThat(span.getParentSpanId(), nullValue());
        assertThat(span.getSpanId(), notNullValue());
        assertThat(span.getSpanName(), is(expectedSpanName));
        assertThat(span.getUserId(), nullValue());

        if (!stateIsNull) {
            strategyInitialSpanNameArgs.get().verifyArgs(requestInfoMock, tagAndNamingAdapterMock);
            strategyRequestTaggingArgs.get().verifyArgs(span, requestInfoMock, tagAndNamingAdapterMock);
        }

        if (addWireReceiveStartAnnotation) {
            long expectedMaxWireReceiveStartTimestamp =
                span.getSpanStartTimeEpochMicros() + TimeUnit.NANOSECONDS.toMicros(nanosAfter - nanosBefore);
            TimestampedAnnotation wireReceiveStartAnnotation = findAnnotationInSpan(
                span, distributedTracingConfig.getServerSpanNamingAndTaggingStrategy().wireReceiveStartAnnotationName()
            );
            Assertions.assertThat(wireReceiveStartAnnotation.getTimestampEpochMicros())
                      .isBetween(span.getSpanStartTimeEpochMicros(), expectedMaxWireReceiveStartTimestamp);
        }
    }

    @DataProvider(value = {
        "true   |   true    |   true",
        "true   |   false   |   false",
        "false  |   true    |   false",
        "false  |   false   |   false",
    }, splitBy = "\\|")
    @Test
    public void doChannelRead_adds_wire_receive_finish_annotation_whem_msg_is_LastHttpContent_if_desired(
        boolean addWireReceiveFinishAnnotationConfigValue, boolean overallRequestSpanExists, boolean expectAnnotation
    ) {
        // given
        DTraceStartHandler handlerSpy = spy(handler);

        HttpProcessingState stateSpy = spy(state);
        doReturn(stateSpy).when(stateAttributeMock).get();

        Span spanMock = (overallRequestSpanExists) ? mock(Span.class) : null;
        doReturn(spanMock).when(stateSpy).getOverallRequestSpan();

        shouldAddWireReceiveFinishAnnotation = addWireReceiveFinishAnnotationConfigValue;

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, new DefaultLastHttpContent());

        // then
        if (expectAnnotation) {
            verify(spanMock).addTimestampedAnnotationForCurrentTime(
                distributedTracingConfig.getServerSpanNamingAndTaggingStrategy().wireReceiveFinishAnnotationName()
            );

            verifyNoMoreInteractions(spanMock);
        }
        else if (spanMock != null) {
            verify(spanMock, never()).addTimestampedAnnotationForCurrentTime(anyString());
        }

        Assertions.assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    private enum GetSpanNameScenario {
        NAMING_STRATEGY_RESULT_IS_VALID(false, "spanNameFromStrategy", true),
        NAMING_STRATEGY_RESULT_IS_NULL(false, null, false),
        NAMING_STRATEGY_RESULT_IS_EMPTY(false, "", false),
        NAMING_STRATEGY_RESULT_IS_BLANK(false, "  \r\n\t  ", false),
        RIPOSTE_REQUEST_INFO_IS_NULL(true, "doesnotmatter", false);

        public final String fallbackSpanName = "fallback-span-name-" + UUID.randomUUID().toString();
        public final boolean riposteRequestInfoIsNull;
        public final String strategySpanName;
        public final boolean expectStrategyResult;

        GetSpanNameScenario(
            boolean riposteRequestInfoIsNull, String strategySpanName, boolean expectStrategyResult
        ) {
            this.riposteRequestInfoIsNull = riposteRequestInfoIsNull;
            this.strategySpanName = strategySpanName;
            this.expectStrategyResult = expectStrategyResult;
        }
    }

    @DataProvider
    public static List<List<GetSpanNameScenario>> getSpanNameScenarioDataProvider() {
        return Arrays.stream(GetSpanNameScenario.values()).map(Collections::singletonList).collect(Collectors.toList());
    }

    @UseDataProvider("getSpanNameScenarioDataProvider")
    @Test
    public void getSpanName_works_as_expected(GetSpanNameScenario scenario) {
        // given
        DTraceStartHandler handlerSpy = spy(handler);

        initialSpanNameFromStrategy.set(scenario.strategySpanName);
        doReturn(scenario.fallbackSpanName).when(handlerSpy).getFallbackSpanName(any());

        if (scenario.riposteRequestInfoIsNull) {
            requestInfoMock = null;
        }

        String expectedResult = (scenario.expectStrategyResult)
                                ? scenario.strategySpanName
                                : scenario.fallbackSpanName;

        // when
        String result = handlerSpy.getSpanName(
            httpRequest, requestInfoMock, distributedTracingConfig.getServerSpanNamingAndTaggingStrategy()
        );

        // then
        Assertions.assertThat(result).isEqualTo(expectedResult);

        if (!scenario.riposteRequestInfoIsNull) {
            strategyInitialSpanNameArgs.get().verifyArgs(requestInfoMock, tagAndNamingAdapterMock);
        }

        if (!scenario.expectStrategyResult) {
            verify(handlerSpy).getFallbackSpanName(httpRequest);
        }
    }

    @DataProvider(value = {
        "foo            |   foo",
        "null           |   UNKNOWN_HTTP_METHOD",
        "               |   UNKNOWN_HTTP_METHOD",
        "[whitespace]   |   UNKNOWN_HTTP_METHOD",
    }, splitBy = "\\|")
    @Test
    public void getFallbackSpanName_works_as_expected(String httpMethodStr, String expectedResult) {
        // given
        if ("[whitespace]".equals(httpMethodStr)) {
            httpMethodStr = "  \r\n\t  ";
        }
        
        HttpMethod httpMethodMock = (httpMethodStr == null) ? null : mock(HttpMethod.class);
        HttpRequest nettyHttpRequestMock = mock(HttpRequest.class);

        doReturn(httpMethodMock).when(nettyHttpRequestMock).method();
        if (httpMethodMock != null) {
            doReturn(httpMethodStr).when(httpMethodMock).name();
        }

        // when
        String result = handler.getFallbackSpanName(nettyHttpRequestMock);

        // then
        Assertions.assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void getFallbackSpanName_returns_UNKNOWN_HTTP_METHOD_if_unexpected_exception_occurs() {
        // given
        HttpRequest nettyHttpRequestMock = mock(HttpRequest.class);
        doThrow(new RuntimeException("intentional exception")).when(nettyHttpRequestMock).method();

        // when
        String result = handler.getFallbackSpanName(nettyHttpRequestMock);

        // then
        Assertions.assertThat(result).isEqualTo("UNKNOWN_HTTP_METHOD");
        verify(nettyHttpRequestMock).method();
    }
}