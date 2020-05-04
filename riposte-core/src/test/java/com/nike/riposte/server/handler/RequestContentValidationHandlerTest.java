package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.exception.MissingRequiredContentException;
import com.nike.riposte.server.error.validation.RequestValidator;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;

import org.junit.Before;
import org.junit.Test;
import com.nike.riposte.testutils.Whitebox;

import java.util.UUID;
import java.util.function.Function;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link RequestContentValidationHandler}
 *
 * @author Nic Munroe
 */
public class RequestContentValidationHandlerTest {

    private RequestContentValidationHandler handler;
    private RequestValidator requestValidatorMock;
    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private RequestInfo<String> requestInfoMock;
    private Endpoint<?> endpointMock;
    private LastHttpContent msg = mock(LastHttpContent.class);
    private String content = UUID.randomUUID().toString();

    @Before
    public void beforeMethod() {
        requestValidatorMock = mock(RequestValidator.class);
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        stateAttrMock = mock(Attribute.class);
        endpointMock = mock(Endpoint.class);
        requestInfoMock = mock(RequestInfo.class);

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(endpointMock).when(stateMock).getEndpointForExecution();
        doReturn(requestInfoMock).when(stateMock).getRequestInfo();
        doReturn(true).when(endpointMock).isValidateRequestContent(any());
        doReturn(content).when(requestInfoMock).getContent();

        doReturn(true).when(requestInfoMock).isCompleteRequestWithAllChunks();
        doReturn(true).when(requestInfoMock).isContentDeserializerSetup();
        doReturn(content.length()).when(requestInfoMock).getRawContentLengthInBytes();

        handler = new RequestContentValidationHandler(requestValidatorMock);
    }

    @Test
    public void constructor_uses_passed_in_arg() {
        // given
        RequestContentValidationHandler theHandler = new RequestContentValidationHandler(requestValidatorMock);

        // when
        RequestValidator validatorUsed = (RequestValidator) Whitebox.getInternalState(theHandler, "validationService");

        // expect
        assertThat(validatorUsed).isEqualTo(requestValidatorMock);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_passed_null() {
        // expect
        new RequestContentValidationHandler(null);
    }

    @Test
    public void doChannelRead_validates_without_validationGroups_if_validationGroups_is_null() throws Exception {
        // given
        doReturn(null).when(endpointMock).validationGroups(any());

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(requestValidatorMock).validateRequestContent(requestInfoMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_validates_with_validationGroups_if_validationGroups_is_not_null() throws Exception {
        // given
        Class<?>[] validationGroups = new Class[]{};
        doReturn(validationGroups).when(endpointMock).validationGroups(any());

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(requestValidatorMock).validateRequestContent(requestInfoMock, validationGroups);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_if_endpoint_is_null() throws Exception {
        // given
        doReturn(null).when(stateMock).getEndpointForExecution();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verifyNoMoreInteractions(requestValidatorMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_if_content_is_null_and_require_content_is_false() throws Exception {
        // given
        doReturn(null).when(requestInfoMock).getContent();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verifyNoMoreInteractions(requestValidatorMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_throws_exception_if_content_is_null_and_endpoint_requires_content() throws Exception {
        // given
        doReturn(0).when(requestInfoMock).getRawContentLengthInBytes();
        doReturn(true).when(endpointMock).isValidateRequestContent(requestInfoMock);
        doReturn(true).when(endpointMock).isRequireRequestContent();

        // when
        Throwable ex = catchThrowable(() -> handler.doChannelRead(ctxMock, msg));

        // then
        assertThat(ex)
                .isInstanceOf(MissingRequiredContentException.class);

        // then
        verifyNoMoreInteractions(requestValidatorMock);
    }

    @Test
    public void doChannelRead_does_nothing_if_endpoint_does_not_want_to_validate() throws Exception {
        // given
        doReturn(false).when(endpointMock).isValidateRequestContent(any());

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verifyNoMoreInteractions(requestValidatorMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_if_request_isCompleteRequestWithAllChunks_returns_false() throws Exception {
        // given
        doReturn(false).when(requestInfoMock).isCompleteRequestWithAllChunks();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verifyNoMoreInteractions(requestValidatorMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_if_request_isContentDeserializerSetup_returns_false() throws Exception {
        // given
        doReturn(false).when(requestInfoMock).isContentDeserializerSetup();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verifyNoMoreInteractions(requestValidatorMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_calls_request_getContent_method_if_endpoint_wants_validation() throws Exception {
        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(requestInfoMock).getContent();
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_not_call_request_getContent_method_if_endpoint_is_null() throws Exception {
        // given
        doReturn(null).when(stateMock).getEndpointForExecution();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(requestInfoMock, never()).getContent();
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_not_call_request_getContent_method_if_endpoint_does_not_want_validation() throws Exception {
        // given
        doReturn(false).when(endpointMock).isValidateRequestContent(any());

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(requestInfoMock, never()).getContent();
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_if_msg_is_not_LastHttpContent() throws Exception {
        // given
        HttpContent notLastContentMsg = mock(HttpContent.class);

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, notLastContentMsg);

        // then
        verifyZeroInteractions(requestInfoMock);
        verifyZeroInteractions(endpointMock);
        verifyZeroInteractions(stateMock);
        verifyZeroInteractions(requestValidatorMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_delegates_to_async_processing_when_requested_by_endpoint() throws Exception {
        // given
        doReturn(true).when(endpointMock).shouldValidateAsynchronously(requestInfoMock);

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(stateMock).addPreEndpointExecutionWorkChainSegment(any(Function.class));
        verifyZeroInteractions(requestValidatorMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_executes_validation_synchronously_when_requested_by_endpoint() throws Exception {
        // given
        doReturn(false).when(endpointMock).shouldValidateAsynchronously(requestInfoMock);

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msg);

        // then
        verify(stateMock, never()).addPreEndpointExecutionWorkChainSegment(any(Function.class));
        verify(requestValidatorMock).validateRequestContent(requestInfoMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }
}