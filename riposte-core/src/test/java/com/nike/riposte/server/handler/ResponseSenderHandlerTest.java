package com.nike.riposte.server.handler;

import com.nike.backstopper.apierror.sample.SampleCoreApiError;
import com.nike.backstopper.model.DefaultErrorDTO;
import com.nike.backstopper.model.riposte.ErrorResponseBodyImpl;
import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.ChunkedOutboundMessage;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.http.impl.RequestInfoImpl;
import com.nike.riposte.testutils.Whitebox;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.tags.KnownZipkinTags;
import com.nike.wingtips.util.TracingState;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link ResponseSenderHandler}
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class ResponseSenderHandlerTest {

    private ResponseSenderHandler handlerSpy;
    private HttpProcessingState stateMock;
    private ProxyRouterProcessingState proxyStateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private Attribute<ProxyRouterProcessingState> proxyAttrMock;
    private MetricsListener metricsListenerMock;
    private HttpRequest msgMock;
    private ResponseSender responseSenderMock;
    private RequestInfo<?> requestInfoMock;
    private ResponseInfo<?> responseInfo;
    private Endpoint<?> endpointExecutedMock;
    private ObjectMapper customSerializerMock;

    @Before
    public void beforeMethod() {
        stateMock = mock(HttpProcessingState.class);
        proxyStateMock = mock(ProxyRouterProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        stateAttrMock = mock(Attribute.class);
        proxyAttrMock = mock(Attribute.class);
        metricsListenerMock = mock(MetricsListener.class);
        msgMock = mock(HttpRequest.class);
        responseSenderMock = mock(ResponseSender.class);
        requestInfoMock = mock(RequestInfo.class);
        responseInfo = ResponseInfo.newBuilder(UUID.randomUUID().toString()).build();
        endpointExecutedMock = mock(Endpoint.class);
        customSerializerMock = mock(ObjectMapper.class);

        doReturn(true).when(channelMock).isActive();
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(proxyAttrMock).when(channelMock).attr(ChannelAttributes.PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(proxyStateMock).when(proxyAttrMock).get();
        doReturn(requestInfoMock).when(stateMock).getRequestInfo();
        doReturn(responseInfo).when(stateMock).getResponseInfo();
        doReturn(endpointExecutedMock).when(stateMock).getEndpointForExecution();
        doReturn(customSerializerMock).when(endpointExecutedMock).customResponseContentSerializer(any(RequestInfo.class));

        handlerSpy = spy(new ResponseSenderHandler(responseSenderMock));

        resetTracing();
    }

    @After
    public void afterMethod() {
        resetTracing();
    }

    private void resetTracing() {
        MDC.clear();
        Tracer.getInstance().unregisterFromThread();
    }

    @Test
    public void constructor_sets_responseSender_to_arg_value() {
        // when
        ResponseSenderHandler theHandler = new ResponseSenderHandler(responseSenderMock);

        // then
        ResponseSender actualResponseSender = (ResponseSender) Whitebox.getInternalState(theHandler, "responseSender");
        assertThat(actualResponseSender).isEqualTo(responseSenderMock);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_arg_is_null() {
        // expect
        new ResponseSenderHandler(null);
    }

    @Test
    public void doChannelRead_calls_sendResponse_with_expected_args_and_returns_CONTINUE() throws Exception {
        // given
        Object msg = new Object();

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy).sendResponse(ctxMock, msg, false);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doExceptionCaught_calls_sendResponse_with_expected_args_and_returns_CONTINUE() throws Exception {
        // when
        PipelineContinuationBehavior result = handlerSpy.doExceptionCaught(ctxMock, new Exception("intentional test exception"));

        // then
        verify(handlerSpy).sendResponse(ctxMock, null, true);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
        verify(channelMock, never()).close();
    }

    @Test
    public void doExceptionCaught_closes_channel_but_does_not_propagate_exception_from_sendResponse(
    ) throws JsonProcessingException {
        // given
        doThrow(new RuntimeException("intentional test exception"))
            .when(handlerSpy).sendResponse(any(), any(), anyBoolean());

        // when
        PipelineContinuationBehavior result = handlerSpy.doExceptionCaught(ctxMock, mock(Throwable.class));

        // then
        verify(handlerSpy).sendResponse(ctxMock, null, true);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
        verify(channelMock).close();
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void sendResponse_calls_doSendResponse_and_does_nothing_else_if_no_exception_is_thrown(
        boolean sendLastDitchResponseInline
    ) throws JsonProcessingException {
        // given
        Object msgMock = mock(Object.class);
        doNothing().when(handlerSpy).doSendResponse(any(), any());

        // when
        handlerSpy.sendResponse(ctxMock, msgMock, sendLastDitchResponseInline);

        // then
        verify(handlerSpy).sendResponse(ctxMock, msgMock, sendLastDitchResponseInline);
        verify(handlerSpy).doSendResponse(ctxMock, msgMock);
        verifyNoMoreInteractions(handlerSpy);
        verifyNoInteractions(ctxMock, msgMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void sendResponse_should_setup_a_last_ditch_error_response_if_an_exception_occurs_in_doSendResponse(
        boolean sendLastDitchResponseInline
    ) throws JsonProcessingException {
        // given
        Object msg = new Object();
        AtomicInteger numTimesDoSendResponseCalled = new AtomicInteger(0);
        RuntimeException expectedExceptionFromDoSendResponse = new RuntimeException("intentional test exception");
        List<ResponseInfo<?>> responseInfosPassedToDoSendResponse = new ArrayList<>();
        doAnswer(invocation -> {
            int numTimesCalled = numTimesDoSendResponseCalled.incrementAndGet();

            // Capture the response data for this call so it can be asserted on later.
            ChannelHandlerContext ctx = invocation.getArgument(0);
            HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            responseInfosPassedToDoSendResponse.add(state.getResponseInfo());

            // Only throw the exception the first time.
            if (numTimesCalled == 1) {
                throw expectedExceptionFromDoSendResponse;
            }

            return null;
        }).when(handlerSpy).doSendResponse(any(), any());

        // Use a real HttpProcessingState so that we can verify the last-ditch response gets set on it properly.
        HttpProcessingState state = new HttpProcessingState();
        state.setResponseInfo(responseInfo, null);
        doReturn(state).when(stateAttrMock).get();

        // when
        Throwable propagatedEx = catchThrowable(
            () -> handlerSpy.sendResponse(ctxMock, msg, sendLastDitchResponseInline)
        );

        // then
        // The initial call should have been made to doSendResponse, using the original responseInfo.
        assertThat(responseInfosPassedToDoSendResponse.get(0)).isSameAs(responseInfo);
        // But now, after the exception handling, the state should contain a new ResponseInfo
        //      representing the last ditch error response.
        ResponseInfo<?> lastDitchResponseInfo = state.getResponseInfo();
        assertThat(lastDitchResponseInfo).isNotEqualTo(responseInfo);
        assertThat(lastDitchResponseInfo.getContentForFullResponse()).isInstanceOf(ErrorResponseBodyImpl.class);
        ErrorResponseBodyImpl lastDitchContent =
            (ErrorResponseBodyImpl)lastDitchResponseInfo.getContentForFullResponse();
        assertThat(lastDitchContent).isNotNull();
        assertThat(lastDitchContent.errors).hasSize(1);
        DefaultErrorDTO errorDto = lastDitchContent.errors.get(0);
        assertThat(errorDto.code).isEqualTo(SampleCoreApiError.GENERIC_SERVICE_ERROR.getErrorCode());
        assertThat(errorDto.message).isEqualTo(SampleCoreApiError.GENERIC_SERVICE_ERROR.getMessage());
        assertThat(errorDto.metadata).isEqualTo(SampleCoreApiError.GENERIC_SERVICE_ERROR.getMetadata());
        String errorId = lastDitchContent.errorId();
        assertThat(errorId).isNotBlank();
        assertThat(lastDitchResponseInfo.getHeaders().get("error_uid")).isEqualTo(errorId);
        assertThat(lastDitchResponseInfo.getHttpStatusCode()).isEqualTo(500);
        // The request should have been marked as setting the last ditch response, so we only ever try this once.
        verify(handlerSpy).markTriedSendingLastDitchResponse(state);

        if (sendLastDitchResponseInline) {
            // Verify that the last ditch response was sent inline, and matches expected properties.
            assertThat(numTimesDoSendResponseCalled.get()).isEqualTo(2);
            assertThat(responseInfosPassedToDoSendResponse.get(1)).isSameAs(lastDitchResponseInfo);

            // Since the last ditch response was sent inline, and we made sure it would complete successfully,
            //      then no exception should have been propagated.
            assertThat(propagatedEx).isNull();
        }
        else {
            // Verify that the last ditch response was not sent inline.
            assertThat(numTimesDoSendResponseCalled.get()).isEqualTo(1);

            // Since the last ditch response was not sent inline, the doSendResponse exception should have been
            //      propagated.
            assertThat(propagatedEx).isSameAs(expectedExceptionFromDoSendResponse);
        }

        // Proxy streaming should be canceled.
        verify(proxyStateMock).cancelRequestStreaming(expectedExceptionFromDoSendResponse, ctxMock);
        verify(proxyStateMock).cancelDownstreamRequest(expectedExceptionFromDoSendResponse);

        // But since the last-ditch response was sent successfully, the channel shouldn't have been closed.
        verify(channelMock, never()).close();
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void sendResponse_should_not_setup_a_last_ditch_error_response_when_last_ditch_has_already_been_tried(
        boolean sendLastDitchResponseInline
    ) throws JsonProcessingException {
        // given
        Object msg = new Object();
        RuntimeException expectedExceptionFromDoSendResponse = new RuntimeException("intentional test exception");
        doThrow(expectedExceptionFromDoSendResponse).when(handlerSpy).doSendResponse(any(), any());

        doReturn(true).when(handlerSpy).alreadyTriedSendingLastDitchResponse(any());

        // Use a real HttpProcessingState so that we can verify the ResponseInfo doesn't change.
        HttpProcessingState state = new HttpProcessingState();
        state.setResponseInfo(responseInfo, null);
        doReturn(state).when(stateAttrMock).get();

        // when
        Throwable propagatedEx = catchThrowable(
            () -> handlerSpy.sendResponse(ctxMock, msg, sendLastDitchResponseInline)
        );

        // then
        verify(handlerSpy, times(1)).doSendResponse(any(), any());
        verify(handlerSpy).alreadyTriedSendingLastDitchResponse(state);

        // Since we marked the last ditch as having already been tried, the state's ResponseInfo should not have
        //      changed, and the doSendResponse() exception should have been rethrown.
        assertThat(state.getResponseInfo()).isSameAs(responseInfo);
        assertThat(propagatedEx).isSameAs(expectedExceptionFromDoSendResponse);

        // Proxy streaming should be canceled.
        verify(proxyStateMock).cancelRequestStreaming(expectedExceptionFromDoSendResponse, ctxMock);
        verify(proxyStateMock).cancelDownstreamRequest(expectedExceptionFromDoSendResponse);

        verify(channelMock, never()).close();
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void sendResponse_should_close_the_connection_and_rethrow_the_doSendResponse_ex_when_response_sending_has_already_started(
        boolean sendLastDitchResponseInline
    ) throws JsonProcessingException {
        // given
        Object msg = new Object();
        RuntimeException expectedExceptionFromDoSendResponse = new RuntimeException("intentional test exception");
        doThrow(expectedExceptionFromDoSendResponse).when(handlerSpy).doSendResponse(any(), any());

        doReturn(true).when(stateMock).isResponseSendingStarted();

        // when
        Throwable propagatedEx = catchThrowable(
            () -> handlerSpy.sendResponse(ctxMock, msg, sendLastDitchResponseInline)
        );

        // then
        assertThat(propagatedEx).isSameAs(expectedExceptionFromDoSendResponse);
        verify(handlerSpy, times(1)).doSendResponse(any(), any());
        verify(stateMock).isResponseSendingStarted();
        verify(proxyStateMock).cancelRequestStreaming(expectedExceptionFromDoSendResponse, ctxMock);
        verify(proxyStateMock).cancelDownstreamRequest(expectedExceptionFromDoSendResponse);
        verify(channelMock).close();
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true",
        "false  |   false",
    }, splitBy = "\\|")
    @Test
    public void sendResponse_adds_error_tag_to_current_span_when_response_sending_has_already_started_but_only_if_error_tag_does_not_already_exist(
        boolean errorTagAlreadyExists, boolean exceptionHasMessage
    ) throws JsonProcessingException {
        // given
        Object msg = new Object();
        RuntimeException expectedExceptionFromDoSendResponse =
            (exceptionHasMessage)
            ? new RuntimeException("intentional test exception")
            : new RuntimeException();
        doThrow(expectedExceptionFromDoSendResponse).when(handlerSpy).doSendResponse(any(), any());

        doReturn(true).when(stateMock).isResponseSendingStarted();

        Span currentSpan = Tracer.getInstance().startRequestWithRootSpan("fooSpan");
        TracingState tracingState = TracingState.getCurrentThreadTracingState();
        doReturn(tracingState.spanStack).when(stateMock).getDistributedTraceStack();
        doReturn(tracingState.mdcInfo).when(stateMock).getLoggerMdcContextMap();

        String addedErrorTagValue = (exceptionHasMessage)
                                    ? "intentional test exception"
                                    : expectedExceptionFromDoSendResponse.getClass().getSimpleName();

        String preexistingErrorTagValue = UUID.randomUUID().toString();
        if (errorTagAlreadyExists) {
            currentSpan.putTag(KnownZipkinTags.ERROR, preexistingErrorTagValue);
        }

        String expectedErrorTagValue = (errorTagAlreadyExists)
                                       ? preexistingErrorTagValue
                                       : addedErrorTagValue;

        // when
        Throwable propagatedEx = catchThrowable(
            () -> handlerSpy.sendResponse(ctxMock, msg, false)
        );

        // then
        // Standard assertions for this method call.
        assertThat(propagatedEx).isSameAs(expectedExceptionFromDoSendResponse);
        verify(handlerSpy, times(1)).doSendResponse(any(), any());
        verify(stateMock).isResponseSendingStarted();
        verify(proxyStateMock).cancelRequestStreaming(expectedExceptionFromDoSendResponse, ctxMock);
        verify(proxyStateMock).cancelDownstreamRequest(expectedExceptionFromDoSendResponse);
        verify(channelMock).close();

        // What we're actually testing in this test.
        assertThat(currentSpan.getTags().get(KnownZipkinTags.ERROR)).isEqualTo(expectedErrorTagValue);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void markTriedSendingLastDitchResponse_works_as_expected(
        boolean requestInfoIsNull
    ) {
        // given
        if (requestInfoIsNull) {
            doReturn(null).when(stateMock).getRequestInfo();
        }

        // when
        handlerSpy.markTriedSendingLastDitchResponse(stateMock);

        // then
        if (requestInfoIsNull) {
            verifyNoInteractions(requestInfoMock);
        }
        else {
            verify(requestInfoMock).addRequestAttribute(handlerSpy.LAST_DITCH_RESPONSE_SET_REQ_ATTR_KEY, Boolean.TRUE);
        }
    }

    private enum AlreadyTriedSendingLastDitchResponseAttrScenario {
        REQUEST_INFO_IS_NULL(true, Boolean.TRUE, false),
        ATTR_IS_BOOLEAN_TRUE(false, Boolean.TRUE, true),
        ATTR_IS_BOOLEAN_FALSE(false, Boolean.FALSE, false),
        ATTR_IS_PRIMITIVE_TRUE(false, true, true),
        ATTR_IS_PRIMITIVE_FALSE(false, false, false),
        ATTR_IS_STRING_TRUE(false, "true", false),
        ATTR_IS_STRING_FALSE(false, "false", false),
        ATTR_IS_NULL(false, null, false),
        ATTR_IS_JUNK(false, new Object(), false);

        public final boolean requestIsNull;
        public final Object attr;
        public final boolean expectedResult;

        AlreadyTriedSendingLastDitchResponseAttrScenario(boolean requestIsNull, Object attr, boolean expectedResult) {
            this.requestIsNull = requestIsNull;
            this.attr = attr;
            this.expectedResult = expectedResult;
        }
    }

    @DataProvider
    public static List<List<AlreadyTriedSendingLastDitchResponseAttrScenario>> alreadyTriedSendingLastDitchResponseDataProvider() {
        return Arrays
            .stream(AlreadyTriedSendingLastDitchResponseAttrScenario.values())
            .map(Collections::singletonList)
            .collect(Collectors.toList());
    }

    @UseDataProvider("alreadyTriedSendingLastDitchResponseDataProvider")
    @Test
    public void alreadyTriedSendingLastDitchResponse_works_as_expected(
        AlreadyTriedSendingLastDitchResponseAttrScenario scenario
    ) {
        // given
        if (scenario.requestIsNull) {
            doReturn(null).when(stateMock).getRequestInfo();
        }

        Map<String, Object> attrs = new HashMap<>();
        doReturn(attrs).when(requestInfoMock).getRequestAttributes();
        attrs.put(handlerSpy.LAST_DITCH_RESPONSE_SET_REQ_ATTR_KEY, scenario.attr);

        // when
        boolean result = handlerSpy.alreadyTriedSendingLastDitchResponse(stateMock);

        // then
        assertThat(result).isEqualTo(scenario.expectedResult);
    }

    @Test
    public void doSendResponse_calls_responseSender_sendFullResponse_for_non_error_content() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        Whitebox.setInternalState(responseInfo, "contentForFullResponse", UUID.randomUUID().toString());

        // when
        handlerSpy.doSendResponse(ctxMock, null);

        // then
        verify(responseSenderMock).sendFullResponse(ctxMock, requestInfo, responseInfo, customSerializerMock);
    }

    @Test
    public void doSendResponse_calls_responseSender_sendFullResponse_for_null_content() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        Whitebox.setInternalState(responseInfo, "contentForFullResponse", null);

        // when
        handlerSpy.doSendResponse(ctxMock, null);

        // then
        verify(responseSenderMock).sendFullResponse(ctxMock, requestInfo, responseInfo, customSerializerMock);
    }

    @Test
    public void doSendResponse_uses_dummy_RequestInfo_if_state_is_missing_requestInfo() throws JsonProcessingException {
        // given
        doReturn(null).when(stateMock).getRequestInfo();

        // when
        handlerSpy.doSendResponse(ctxMock, null);

        // then
        ArgumentCaptor<RequestInfo> requestInfoArgumentCaptor = ArgumentCaptor.forClass(RequestInfo.class);
        verify(responseSenderMock).sendFullResponse(eq(ctxMock), requestInfoArgumentCaptor.capture(), eq(responseInfo), eq(customSerializerMock));
        RequestInfo requestInfoUsed = requestInfoArgumentCaptor.getValue();
        assertThat(requestInfoUsed.getUri()).isEqualTo(RequestInfo.NONE_OR_UNKNOWN_TAG);
    }

    @Test
    public void doSendResponse_passes_null_serializer_if_endpoint_has_no_custom_serializer() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        doReturn(null).when(endpointExecutedMock).customResponseContentSerializer(any(RequestInfo.class));

        // when
        handlerSpy.doSendResponse(ctxMock, null);

        // then
        verify(responseSenderMock).sendFullResponse(ctxMock, requestInfo, responseInfo, null);
    }

    @Test
    public void doSendResponse_passes_null_serializer_if_endpoint_is_null() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        doReturn(null).when(stateMock).getEndpointForExecution();

        // when
        handlerSpy.doSendResponse(ctxMock, null);

        // then
        verify(responseSenderMock).sendFullResponse(ctxMock, requestInfo, responseInfo, null);
    }

    @Test
    public void doSendResponse_calls_responseSender_sendErrorResponse_for_error_content() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        ErrorResponseBody errorContentMock = mock(ErrorResponseBody.class);
        Whitebox.setInternalState(responseInfo, "contentForFullResponse", errorContentMock);

        // when
        handlerSpy.doSendResponse(ctxMock, null);

        // then
        verify(responseSenderMock).sendErrorResponse(ctxMock, requestInfo, (ResponseInfo<ErrorResponseBody>) responseInfo);
    }

    @Test
    public void doSendResponse_calls_responseSender_sendResponseChunk_when_msg_is_a_ChunkedOutboundMessage(
    ) throws JsonProcessingException {
        // given
        ChunkedOutboundMessage chunkedMsgMock = mock(ChunkedOutboundMessage.class);
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();

        // when
        handlerSpy.doSendResponse(ctxMock, chunkedMsgMock);

        // then
        verify(responseSenderMock).sendResponseChunk(ctxMock, requestInfo, responseInfo, chunkedMsgMock);
    }

    @Test
    public void doSendResponse_does_nothing_if_channel_is_closed() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        Whitebox.setInternalState(responseInfo, "contentForFullResponse", UUID.randomUUID().toString());
        doReturn(false).when(channelMock).isActive();

        // when
        handlerSpy.doSendResponse(ctxMock, null);

        // then
        verifyNoInteractions(responseSenderMock);
    }

    @Test
    public void doSendResponse_does_nothing_if_response_is_already_sent() throws JsonProcessingException {
        // given
        Object msgMock = mock(Object.class);
        doReturn(true).when(stateMock).isResponseSendingLastChunkSent();

        // when
        handlerSpy.doSendResponse(ctxMock, msgMock);

        // then
        verifyNoInteractions(responseSenderMock);
    }

    private enum GetErrorResponseBodyIfPossibleScenario {
        RESPONSE_CONTENT_IS_ERROR_RESPONSE_BODY(false, false, mock(ErrorResponseBody.class), true),
        RESPONSE_INFO_IS_NULL(true, false, mock(ErrorResponseBody.class), false),
        RESPONSE_INFO_IS_CHUNKED(false, true, mock(ErrorResponseBody.class), false),
        RESPONSE_CONTENT_IS_NULL(false, false, null, false),
        RESPONSE_CONTENT_IS_NOT_ERROR_RESPONSE_BODY(false, false, new Object(), false);

        public final boolean responseInfoIsNull;
        public final boolean responseIsChunked;
        public final Object responseContent;
        public final Object expectedResult;

        GetErrorResponseBodyIfPossibleScenario(
            boolean responseInfoIsNull, boolean responseIsChunked, Object responseContent, boolean expectedResultIsContent
        ) {
            this.responseInfoIsNull = responseInfoIsNull;
            this.responseIsChunked = responseIsChunked;
            this.responseContent = responseContent;
            this.expectedResult = (expectedResultIsContent) ? responseContent : null;
        }
    }

    @DataProvider
    public static List<List<GetErrorResponseBodyIfPossibleScenario>> getErrorResponseBodyIfPossibleScenarioDataProvider() {
        return Arrays.stream(GetErrorResponseBodyIfPossibleScenario.values())
                     .map(Collections::singletonList)
                     .collect(Collectors.toList());
    }

    @UseDataProvider("getErrorResponseBodyIfPossibleScenarioDataProvider")
    @Test
    public void getErrorResponseBodyIfPossible_works_as_expected(GetErrorResponseBodyIfPossibleScenario scenario) {
        // given
        ResponseInfo<?> responseMock = mock(ResponseInfo.class);

        doReturn(scenario.responseIsChunked).when(responseMock).isChunkedResponse();
        doReturn(scenario.responseContent).when(responseMock).getContentForFullResponse();

        if (scenario.responseInfoIsNull) {
            responseMock = null;
        }

        // when
        ErrorResponseBody result = handlerSpy.getErrorResponseBodyIfPossible(responseMock);

        // then
        assertThat(result).isSameAs(scenario.expectedResult);
    }

    @Test
    public void argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo_returns_false() {
        // expect
        assertThat(
            handlerSpy.argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(null, null, null, null)
        ).isFalse();
    }

}