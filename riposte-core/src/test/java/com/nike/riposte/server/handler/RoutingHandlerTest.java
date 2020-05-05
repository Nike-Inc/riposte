package com.nike.riposte.server.handler;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.config.distributedtracing.DistributedTracingConfig;
import com.nike.riposte.server.config.distributedtracing.ServerSpanNamingAndTaggingStrategy;
import com.nike.riposte.server.error.exception.InvalidHttpRequestException;
import com.nike.riposte.server.error.exception.MethodNotAllowed405Exception;
import com.nike.riposte.server.error.exception.MultipleMatchingEndpointsException;
import com.nike.riposte.server.error.exception.PathNotFound404Exception;
import com.nike.riposte.server.error.exception.RequestTooBigException;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.util.Matcher;
import com.nike.wingtips.Span;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.assertj.core.api.Assertions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import com.nike.riposte.testutils.Whitebox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.Attribute;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaders.Values.CHUNKED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link RoutingHandler}
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class RoutingHandlerTest {

    private RoutingHandler handlerSpy;
    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private RequestInfo requestInfoMock;
    private StandardEndpoint<?, ?> endpointMock;
    private Matcher matcherMock;
    private Collection<Endpoint<?>> endpoints;
    private String defaultPath = "/*/*/{vipname}/**";
    private HttpHeaders httpHeaders;
    private int maxRequestSizeInBytes;
    private HttpRequest msg;
    private DistributedTracingConfig<Span> distributedTracingConfigMock;
    private DummyServerSpanNamingAndTaggingStrategy spanNamingStrategySpy;
    private String initialSpanNameFromStrategy;

    @Before
    public void beforeMethod() {
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        stateAttrMock = mock(Attribute.class);
        requestInfoMock = mock(RequestInfo.class);
        endpointMock = mock(StandardEndpoint.class);
        matcherMock = mock(Matcher.class);
        endpoints = new ArrayList<>(Collections.singleton(endpointMock));
        httpHeaders = new DefaultHttpHeaders();
        maxRequestSizeInBytes = 10;
        msg = mock(HttpRequest.class);
        distributedTracingConfigMock = mock(DistributedTracingConfig.class);
        initialSpanNameFromStrategy = "someSpan_" + UUID.randomUUID().toString();
        spanNamingStrategySpy = spy(new DummyServerSpanNamingAndTaggingStrategy(initialSpanNameFromStrategy));

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(endpointMock).when(stateMock).getEndpointForExecution();
        doReturn(matcherMock).when(endpointMock).requestMatcher();
        doReturn(Optional.of(defaultPath)).when(matcherMock).matchesPath(any(RequestInfo.class));
        doReturn(true).when(matcherMock).matchesMethod(any(RequestInfo.class));
        doReturn(requestInfoMock).when(stateMock).getRequestInfo();
        doReturn(httpHeaders).when(msg).headers();
        doReturn(spanNamingStrategySpy).when(distributedTracingConfigMock).getServerSpanNamingAndTaggingStrategy();

        handlerSpy = spy(new RoutingHandler(endpoints, maxRequestSizeInBytes, distributedTracingConfigMock));
    }

    @Test
    public void constructor_sets_fields_based_on_incoming_args() {
        // when
        RoutingHandler theHandler = new RoutingHandler(endpoints, maxRequestSizeInBytes, distributedTracingConfigMock);

        // then
        Collection<Endpoint<?>>
            actualEndpoints = (Collection<Endpoint<?>>) Whitebox.getInternalState(theHandler, "endpoints");
        assertThat(actualEndpoints).isSameAs(endpoints);
        assertThat(theHandler.globalConfiguredMaxRequestSizeInBytes).isEqualTo(maxRequestSizeInBytes);
        assertThat(theHandler.spanNamingAndTaggingStrategy).isSameAs(spanNamingStrategySpy);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void constructor_throws_IllegalArgumentException_if_endpoints_arg_is_null_or_empty(
        boolean endpointsIsNull
    ) {
        // given
        Collection<Endpoint<?>> nullOrEmptyEndpoints = (endpointsIsNull) ? null : Collections.emptyList();

        // when
        Throwable ex = catchThrowable(
            () -> new RoutingHandler(nullOrEmptyEndpoints, maxRequestSizeInBytes, distributedTracingConfigMock)
        );

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("endpoints cannot be empty");
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_distributedTracingConfig_arg_is_null() {
        // when
        Throwable ex = catchThrowable(() -> new RoutingHandler(endpoints, maxRequestSizeInBytes, null));

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("distributedTracingConfig cannot be null");
    }

    @Test
    public void doChannelRead_calls_findSingleEndpointForExecution_then_sets_path_params_and_endpoint_on_state_then_returns_CONTINUE_if_msg_is_HttpRequest() {
        // given
        doReturn(Arrays.asList(defaultPath)).when(matcherMock).matchingPathTemplates();
        HttpRequest msg = mock(HttpRequest.class);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy).findSingleEndpointForExecution(requestInfoMock);
        verify(requestInfoMock).setPathParamsBasedOnPathTemplate(defaultPath);
        verify(stateMock).setEndpointForExecution(endpointMock, defaultPath);
        verify(handlerSpy).handleSpanNameUpdateForRequestWithPathTemplate(msg, requestInfoMock, stateMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    private enum SpanNameUpdateScenario {
        NEW_SPAN_NAME(
            "newSpanName-" + UUID.randomUUID().toString(), "origSpanName", false, true
        ),
        NEW_SPAN_NAME_EQUALS_ORIG_SPAN_NAME(
            "someOrigSpanName", "someOrigSpanName", false, false
        ),
        OVERALL_REQUEST_SPAN_IS_NULL(
            "new-doesnotmatter", "orig-doesnotmatter", true, false
        );

        public final String newSpanName;
        public final String origSpanName;
        public final boolean overallRequestSpanIsNull;
        public final boolean expectSpanNameChange;

        SpanNameUpdateScenario(
            String newSpanName, String origSpanName, boolean overallRequestSpanIsNull, boolean expectSpanNameChange
        ) {
            this.newSpanName = newSpanName;
            this.origSpanName = origSpanName;
            this.overallRequestSpanIsNull = overallRequestSpanIsNull;
            this.expectSpanNameChange = expectSpanNameChange;
        }
    }

    @DataProvider(value = {
        "NEW_SPAN_NAME",
        "NEW_SPAN_NAME_EQUALS_ORIG_SPAN_NAME",
        "OVERALL_REQUEST_SPAN_IS_NULL"
    })
    @Test
    public void handleSpanNameUpdateForRequestWithPathTemplate_works_as_expected(
        SpanNameUpdateScenario scenario
    ) {
        RiposteHandlerInternalUtil handlerUtilSpy = spy(new RiposteHandlerInternalUtil());
        Whitebox.setInternalState(handlerSpy, "handlerUtils", handlerUtilSpy);

        HttpRequest nettyRequestMock = mock(HttpRequest.class);

        Span spanMock = (scenario.overallRequestSpanIsNull) ? null : mock(Span.class);

        if (spanMock != null) {
            doReturn(scenario.origSpanName).when(spanMock).getSpanName();
        }

        doReturn(spanMock).when(handlerUtilSpy).getOverallRequestSpan(stateMock);
        doReturn(scenario.newSpanName).when(handlerUtilSpy).determineOverallRequestSpanName(
            nettyRequestMock, requestInfoMock, spanNamingStrategySpy
        );

        // when
        handlerSpy.handleSpanNameUpdateForRequestWithPathTemplate(nettyRequestMock, requestInfoMock, stateMock);

        // then
        if (scenario.expectSpanNameChange) {
            verify(spanNamingStrategySpy).doChangeSpanName(spanMock, scenario.newSpanName);
        }
        else {
            verify(spanNamingStrategySpy, never()).doChangeSpanName(any(Span.class), anyString());
        }
    }

    @Test
    public void doChannelRead_does_nothing_if_msg_is_not_HttpRequest() {
        // given
        String pathTemplate = "/some/path/with/{id}";
        Collection<String> pathTemplates = new ArrayList<String>() {{ add(pathTemplate); }};
        doReturn(pathTemplates).when(matcherMock).matchingPathTemplates();
        HttpObject msg = mock(HttpObject.class);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy).doChannelRead(ctxMock, msg);
        verifyNoMoreInteractions(handlerSpy);
        verifyNoMoreInteractions(requestInfoMock);
        verifyNoMoreInteractions(stateMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void findSingleEndpointForExecution_returns_matching_endpoint() {
        // given
        doReturn(Optional.of(defaultPath)).when(matcherMock).matchesPath(any(RequestInfo.class));
        doReturn(true).when(matcherMock).matchesMethod(any(RequestInfo.class));

        // when
        Pair<Endpoint<?>, String> result = handlerSpy.findSingleEndpointForExecution(requestInfoMock);

        // then
        assertThat(result.getKey()).isSameAs(endpointMock);
        assertThat(result.getValue()).isSameAs(defaultPath);
    }

    @Test(expected = PathNotFound404Exception.class)
    public void findSingleEndpointForExecution_throws_PathNotFound404Exception_if_no_matching_path() {
        // given
        doReturn(Optional.empty()).when(matcherMock).matchesPath(any(RequestInfo.class));

        // expect
        handlerSpy.findSingleEndpointForExecution(requestInfoMock);
    }

    @Test(expected = MethodNotAllowed405Exception.class)
    public void findSingleEndpointForExecution_throws_MethodNotAllowed405Exception_if_path_matches_but_method_does_not() {
        // given
        doReturn(Optional.of(defaultPath)).when(matcherMock).matchesPath(any(RequestInfo.class));
        doReturn(false).when(matcherMock).matchesMethod(any(RequestInfo.class));

        // expect
        handlerSpy.findSingleEndpointForExecution(requestInfoMock);
    }

    @Test(expected = MultipleMatchingEndpointsException.class)
    public void findSingleEndpointForExecution_throws_MultipleMatchingEndpointsException_if_multiple_endpoints_fully_match() {
        // given
        doReturn(Optional.of(defaultPath)).when(matcherMock).matchesPath(any(RequestInfo.class));
        doReturn(true).when(matcherMock).matchesMethod(any(RequestInfo.class));

        Endpoint<?> alsoMatchingEndpointMock = mock(Endpoint.class);
        doReturn(matcherMock).when(alsoMatchingEndpointMock).requestMatcher();

        endpoints.add(alsoMatchingEndpointMock);

        // when
        handlerSpy.findSingleEndpointForExecution(requestInfoMock);
    }

    @Test
    public void doChannelRead_HttpRequest_throws_exception_when_content_length_header_greater_than_configured_global_request_limit() {
        // given
        doReturn(null).when(endpointMock).maxRequestSizeInBytesOverride();

        maxRequestSizeInBytes = 10;
        httpHeaders.set(CONTENT_LENGTH, 100);
        handlerSpy = spy(new RoutingHandler(endpoints, maxRequestSizeInBytes, distributedTracingConfigMock));

        // when
        Throwable thrownException = Assertions.catchThrowable(() -> handlerSpy.doChannelRead(ctxMock, msg));

        // then
        assertThat(thrownException).isExactlyInstanceOf(RequestTooBigException.class);
        assertThat(thrownException.getMessage()).isEqualTo("Content-Length header value exceeded configured max request size of 10");
    }

    @DataProvider(value = {
            "99",
            "101"
    })
    @Test
    public void doChannelRead_HttpRequest_endpoint_overridden_max_request_size_throws_exception(int maxRequestSize) {
        // given
        doReturn(100).when(endpointMock).maxRequestSizeInBytesOverride();

        httpHeaders.set(CONTENT_LENGTH, 101);

        handlerSpy = spy(new RoutingHandler(endpoints, maxRequestSize, distributedTracingConfigMock));

        // when
        Throwable thrownException = Assertions.catchThrowable(() -> handlerSpy.doChannelRead(ctxMock, msg));

        // then
        assertThat(thrownException).isExactlyInstanceOf(RequestTooBigException.class);
        assertThat(thrownException.getMessage()).isEqualTo("Content-Length header value exceeded configured max request size of 100");
    }

    @Test
    public void doChannelRead_HttpRequest_under_max_global_request_size_processed_successfully() {
        // given
        doReturn(null).when(endpointMock).maxRequestSizeInBytesOverride();

        maxRequestSizeInBytes = 101;
        httpHeaders.set(CONTENT_LENGTH, 100);
        handlerSpy = spy(new RoutingHandler(endpoints, maxRequestSizeInBytes, distributedTracingConfigMock));

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @DataProvider(value = {
            "true",
            "false"
    })
    @Test
    public void doChannelRead_HttpRequest_does_not_throw_TooLongFrameException_if_content_length_header_is_missing(
            boolean isChunkedTransferEncoding
    ) {
        // given
        if (isChunkedTransferEncoding) {
            httpHeaders.set(TRANSFER_ENCODING, CHUNKED);
        }

        doReturn(null).when(endpointMock).maxRequestSizeInBytesOverride();

        maxRequestSizeInBytes = 101;
        handlerSpy = spy(new RoutingHandler(endpoints, maxRequestSizeInBytes, distributedTracingConfigMock));

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @DataProvider(value = {
            "99",
            "100"
    })
    @Test
    public void doChannelRead_HttpRequest_does_not_throw_TooLongFrameException_if_content_length_is_less_than_endpoint_overridden_value(
            int maxRequestSize
    ) {
        // given
        doReturn(100).when(endpointMock).maxRequestSizeInBytesOverride();

        httpHeaders.set(CONTENT_LENGTH, 100);

        handlerSpy = spy(new RoutingHandler(endpoints, maxRequestSize, distributedTracingConfigMock));

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo_returns_false() {
        assertThat(handlerSpy.argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(null,null,null,null)).isFalse();
    }

    @Test
    public void doChannelRead_HttpRequest_throws_exception_when_failed_decoder_result() {
        // given
        HttpRequest msgMock = mock(HttpRequest.class);
        Throwable decoderFailureCauseMock = mock(Throwable.class);
        DecoderResult decoderResult = DecoderResult.failure(decoderFailureCauseMock);
        doReturn(decoderResult).when(msgMock).decoderResult();
        doReturn(null).when(stateMock).getRequestInfo();

        // when
        Throwable thrownException = Assertions.catchThrowable(() -> handlerSpy.doChannelRead(ctxMock, msgMock));

        // then
        assertThat(thrownException).isExactlyInstanceOf(InvalidHttpRequestException.class);
        assertThat(thrownException.getCause()).isSameAs(decoderFailureCauseMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void doChannelRead_creates_and_sets_RequestInfo_on_state_only_if_state_not_already_set(
        boolean requestInfoAlreadySetOnState
    ) {
        // given
        HttpRequest msgMock = mock(HttpRequest.class);
        String uri = "/some/url";
        HttpHeaders headers = new DefaultHttpHeaders();
        doReturn(uri).when(msgMock).uri();
        doReturn(headers).when(msgMock).headers();
        doReturn(HttpVersion.HTTP_1_1).when(msgMock).protocolVersion();
        RequestInfo<?> requestInfoAlreadyOnState = (requestInfoAlreadySetOnState) ? requestInfoMock : null;
        doReturn(Optional.of(uri)).when(matcherMock).matchesPath(any(RequestInfo.class));
        doReturn(requestInfoAlreadyOnState).when(stateMock).getRequestInfo();

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msgMock);

        // then
        if (requestInfoAlreadySetOnState) {
            verify(stateMock, never()).setRequestInfo(any(RequestInfo.class));
        }
        else {
            ArgumentCaptor<RequestInfo> requestInfoArgumentCaptor = ArgumentCaptor.forClass(RequestInfo.class);
            verify(stateMock).setRequestInfo(requestInfoArgumentCaptor.capture());
            RequestInfo requestInfo = requestInfoArgumentCaptor.getValue();
            assertThat(requestInfo.getUri()).isEqualTo(uri);
            assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
        }
    }

    private static class DummyServerSpanNamingAndTaggingStrategy extends ServerSpanNamingAndTaggingStrategy<Span> {

        public String initialSpanName;

        private DummyServerSpanNamingAndTaggingStrategy(String initialSpanName) {
            this.initialSpanName = initialSpanName;
        }

        @Override
        protected @Nullable String doGetInitialSpanName(@NotNull RequestInfo<?> request) {
            return initialSpanName;
        }

        @Override
        protected void doChangeSpanName(@NotNull Span span, @NotNull String newName) { }

        @Override
        protected void doHandleRequestTagging(@NotNull Span span, @NotNull RequestInfo<?> request) { }

        @Override
        protected void doHandleResponseTaggingAndFinalSpanName(
            @NotNull Span span, @Nullable RequestInfo<?> request, @Nullable ResponseInfo<?> response,
            @Nullable Throwable error
        ) { }
    }

}