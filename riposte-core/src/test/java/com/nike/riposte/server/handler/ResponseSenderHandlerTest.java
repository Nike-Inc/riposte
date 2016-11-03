package com.nike.riposte.server.handler;

import com.nike.riposte.metrics.MetricsListener;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.ResponseSender;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.UUID;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link ResponseSenderHandler}
 *
 * @author Nic Munroe
 */
public class ResponseSenderHandlerTest {

    private ResponseSenderHandler handlerSpy;
    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private MetricsListener metricsListenerMock;
    private HttpRequest msgMock;
    private ResponseSender responseSenderMock;
    private ResponseInfo<?> responseInfo;
    private Endpoint<?> endpointExecutedMock;
    private ObjectMapper customSerializerMock;

    @Before
    public void beforeMethod() {
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        stateAttrMock = mock(Attribute.class);
        metricsListenerMock = mock(MetricsListener.class);
        msgMock = mock(HttpRequest.class);
        responseSenderMock = mock(ResponseSender.class);
        responseInfo = ResponseInfo.newBuilder(UUID.randomUUID().toString()).build();
        endpointExecutedMock = mock(Endpoint.class);
        customSerializerMock = mock(ObjectMapper.class);

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(responseInfo).when(stateMock).getResponseInfo();
        doReturn(endpointExecutedMock).when(stateMock).getEndpointForExecution();
        doReturn(customSerializerMock).when(endpointExecutedMock).customResponseContentSerializer(any(RequestInfo.class));

        handlerSpy = spy(new ResponseSenderHandler(responseSenderMock));
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
    public void doChannelRead_calls_sendResponse_and_returns_CONTINUE() throws Exception {
        // given
        Object msg = new Object();

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy).sendResponse(ctxMock, msg);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doExceptionCaught_calls_sendResponse_and_returns_CONTINUE() throws Exception {
        // when
        PipelineContinuationBehavior result = handlerSpy.doExceptionCaught(ctxMock, new Exception("intentional test exception"));

        // then
        verify(handlerSpy).sendResponse(ctxMock, null);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void sendResponse_calls_responseSender_sendResponse_for_non_error_content() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        Whitebox.setInternalState(responseInfo, "contentForFullResponse", UUID.randomUUID().toString());

        // when
        handlerSpy.sendResponse(ctxMock, null);

        // then
        verify(responseSenderMock).sendFullResponse(ctxMock, requestInfo, responseInfo, customSerializerMock);
    }

    @Test
    public void sendResponse_calls_responseSender_sendResponse_for_null_content() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        Whitebox.setInternalState(responseInfo, "contentForFullResponse", null);

        // when
        handlerSpy.sendResponse(ctxMock, null);

        // then
        verify(responseSenderMock).sendFullResponse(ctxMock, requestInfo, responseInfo, customSerializerMock);
    }

    @Test
    public void sendResponse_uses_dummy_RequestInfo_if_state_is_missing_requestInfo() throws JsonProcessingException {
        // given
        doReturn(null).when(stateMock).getRequestInfo();

        // when
        handlerSpy.sendResponse(ctxMock, null);

        // then
        ArgumentCaptor<RequestInfo> requestInfoArgumentCaptor = ArgumentCaptor.forClass(RequestInfo.class);
        verify(responseSenderMock).sendFullResponse(eq(ctxMock), requestInfoArgumentCaptor.capture(), eq(responseInfo), eq(customSerializerMock));
        RequestInfo requestInfoUsed = requestInfoArgumentCaptor.getValue();
        assertThat(requestInfoUsed.getUri()).isEqualTo(RequestInfo.NONE_OR_UNKNOWN_TAG);
    }

    @Test
    public void sendResponse_passes_null_serializer_if_endpoint_has_no_custom_serializer() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        doReturn(null).when(endpointExecutedMock).customResponseContentSerializer(any(RequestInfo.class));

        // when
        handlerSpy.sendResponse(ctxMock, null);

        // then
        verify(responseSenderMock).sendFullResponse(ctxMock, requestInfo, responseInfo, null);
    }

    @Test
    public void sendResponse_passes_null_serializer_if_endpoint_is_null() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        doReturn(null).when(stateMock).getEndpointForExecution();

        // when
        handlerSpy.sendResponse(ctxMock, null);

        // then
        verify(responseSenderMock).sendFullResponse(ctxMock, requestInfo, responseInfo, null);
    }

    @Test
    public void sendResponse_calls_responseSender_sendErrorResponse_for_error_content() throws JsonProcessingException {
        // given
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
        ErrorResponseBody errorContentMock = mock(ErrorResponseBody.class);
        Whitebox.setInternalState(responseInfo, "contentForFullResponse", errorContentMock);

        // when
        handlerSpy.sendResponse(ctxMock, null);

        // then
        verify(responseSenderMock).sendErrorResponse(ctxMock, requestInfo, (ResponseInfo<ErrorResponseBody>) responseInfo);
    }

}