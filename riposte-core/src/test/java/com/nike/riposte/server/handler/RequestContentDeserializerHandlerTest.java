package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;
import com.nike.riposte.util.Matcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.UUID;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link RequestContentDeserializerHandler}
 *
 * @author Nic Munroe
 */
public class RequestContentDeserializerHandlerTest {

    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private RequestInfo<String> requestInfoSpy;
    private TypeReference<String> contentTypeRef = new TypeReference<String>() { };
    private Matcher endpointMatcher = Matcher.match("/some/url");
    private ObjectMapper defaultHandlerDeserializerMock;
    private RequestContentDeserializerHandler handler;
    private Endpoint<?> endpointMock;
    private LastHttpContent msg;

    @Before
    public void beforeMethod() {
        msg = mock(LastHttpContent.class);
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        stateAttrMock = mock(Attribute.class);
        endpointMock = mock(Endpoint.class);
        requestInfoSpy = spy((RequestInfo<String>) RequestInfoImpl.dummyInstanceForUnknownRequests());
        String rawContentString = UUID.randomUUID().toString();
        Whitebox.setInternalState(requestInfoSpy, "rawContent", rawContentString);
        Whitebox.setInternalState(requestInfoSpy, "rawContentBytes", rawContentString.getBytes());
        defaultHandlerDeserializerMock = mock(ObjectMapper.class);

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(endpointMock).when(stateMock).getEndpointForExecution();
        doReturn(requestInfoSpy).when(stateMock).getRequestInfo();
        doReturn(contentTypeRef).when(endpointMock).requestContentType();
        doReturn(endpointMatcher).when(endpointMock).requestMatcher();

        handler = new RequestContentDeserializerHandler(defaultHandlerDeserializerMock);
    }

    @Test
    public void constructor_uses_passed_in_arg_if_not_null() {
        // when
        RequestContentDeserializerHandler theHandler = new RequestContentDeserializerHandler(defaultHandlerDeserializerMock);

        // then
        ObjectMapper storedObjMapperDefault = (ObjectMapper) Whitebox.getInternalState(theHandler, "defaultRequestContentDeserializer");
        assertThat(storedObjMapperDefault).isEqualTo(defaultHandlerDeserializerMock);
    }

    @Test
    public void constructor_creates_new_default_objectMapper_if_passed_null() {
        // when
        RequestContentDeserializerHandler theHandler = new RequestContentDeserializerHandler(null);

        // then
        ObjectMapper storedObjMapperDefault = (ObjectMapper) Whitebox.getInternalState(theHandler, "defaultRequestContentDeserializer");
        assertThat(storedObjMapperDefault).isNotEqualTo(defaultHandlerDeserializerMock);
    }

    @Test
    public void doChannelRead_uses_default_deserializer_if_custom_endpoint_one_is_null() throws Exception {
        // given
        doReturn(null).when(endpointMock).customRequestContentDeserializer(any());

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(requestInfoSpy).setupContentDeserializer(defaultHandlerDeserializerMock, contentTypeRef);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_uses_custom_deserializer_if_custom_endpoint_one_is_not_null() throws Exception {
        // given
        ObjectMapper customDeserializerMock = mock(ObjectMapper.class);
        doReturn(customDeserializerMock).when(endpointMock).customRequestContentDeserializer(any());

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(requestInfoSpy).setupContentDeserializer(customDeserializerMock, contentTypeRef);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_uses_TypeReference_from_endpoint_requestContentType_method() throws Exception {
        // given
        TypeReference<String> customTypeReference = new TypeReference<String>() {};
        doReturn(customTypeReference).when(endpointMock).requestContentType();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        ArgumentCaptor<TypeReference> typeRefArgumentCaptor = ArgumentCaptor.forClass(TypeReference.class);
        verify(requestInfoSpy).setupContentDeserializer(eq(defaultHandlerDeserializerMock), typeRefArgumentCaptor.capture());
        TypeReference<String> actualTypeRef = typeRefArgumentCaptor.getValue();
        assertThat(actualTypeRef).isSameAs(customTypeReference);
        assertThat(actualTypeRef).isNotSameAs(contentTypeRef);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_if_endpoint_is_null() throws Exception {
        // given
        doReturn(null).when(stateMock).getEndpointForExecution();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(stateMock).getEndpointForExecution();
        verify(stateMock).getRequestInfo();
        verifyNoMoreInteractions(stateMock);
        verifyNoMoreInteractions(endpointMock);
        verifyNoMoreInteractions(requestInfoSpy);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_if_requestContentType_is_null() throws Exception {
        // given
        doReturn(null).when(endpointMock).requestContentType();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(stateMock).getEndpointForExecution();
        verify(stateMock).getRequestInfo();
        verifyNoMoreInteractions(stateMock);
        verify(endpointMock).requestContentType();
        verify(requestInfoSpy).isCompleteRequestWithAllChunks();
        verifyNoMoreInteractions(endpointMock);
        verifyNoMoreInteractions(requestInfoSpy);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_if_msg_is_not_LastHttpContent() throws Exception {
        // given
        Object wrongMsgType = new Object();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, wrongMsgType);

        // then
        verifyZeroInteractions(ctxMock);
        verifyZeroInteractions(stateMock);
        verifyZeroInteractions(endpointMock);
        verifyZeroInteractions(requestInfoSpy);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }
}