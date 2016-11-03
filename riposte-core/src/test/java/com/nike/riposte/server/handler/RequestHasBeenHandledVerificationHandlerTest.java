package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.ChunkedOutboundMessage;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessageSendFullResponseInfo;
import com.nike.riposte.server.error.exception.InvalidRipostePipelineException;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ResponseInfo;

import org.junit.Before;
import org.junit.Test;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;

import static com.nike.riposte.server.handler.base.PipelineContinuationBehavior.CONTINUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link RequestHasBeenHandledVerificationHandler}.
 *
 * @author Nic Munroe
 */
public class RequestHasBeenHandledVerificationHandlerTest {

    private RequestHasBeenHandledVerificationHandler handler;

    private Object msg;

    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;
    private ResponseInfo<?> responseInfoMock;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        responseInfoMock = mock(ResponseInfo.class);
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();
        state.setResponseInfo(responseInfoMock);

        handler = new RequestHasBeenHandledVerificationHandler();
        msg = mock(LastOutboundMessageSendFullResponseInfo.class);
    }

    @Test
    public void doChannelRead_returns_CONTINUE_when_response_is_not_chunked_and_msg_is_LastOutboundMessageSendFullResponseInfo()
        throws Exception {
        // expect
        assertThat(handler.doChannelRead(ctxMock, msg)).isEqualTo(CONTINUE);
    }

    @Test
    public void doChannelRead_returns_CONTINUE_when_response_is_chunked_and_msg_is_ChunkedOutboundMessage()
        throws Exception {
        // given
        doReturn(true).when(responseInfoMock).isChunkedResponse();
        msg = mock(ChunkedOutboundMessage.class);

        // expect
        assertThat(handler.doChannelRead(ctxMock, msg)).isEqualTo(CONTINUE);
    }

    @Test
    public void doChannelRead_throws_InvalidRipostePipelineException_when_msg_is_null() {
        // when
        Throwable ex = catchThrowable(() -> handler.doChannelRead(ctxMock, null));

        // then
        assertThat(ex).isInstanceOf(InvalidRipostePipelineException.class);
    }

    @Test
    public void doChannelRead_throws_InvalidRipostePipelineException_when_msg_is_not_OutboundMessage() {
        // when
        Throwable ex = catchThrowable(() -> handler.doChannelRead(ctxMock, new Object()));

        // then
        assertThat(ex).isInstanceOf(InvalidRipostePipelineException.class);
    }

    @Test
    public void doChannelRead_throws_InvalidRipostePipelineException_when_state_is_null() {
        // given
        doReturn(null).when(stateAttributeMock).get();

        // when
        Throwable ex = catchThrowable(() -> handler.doChannelRead(ctxMock, msg));

        // then
        assertThat(ex).isInstanceOf(InvalidRipostePipelineException.class);
    }

    @Test
    public void doChannelRead_throws_InvalidRipostePipelineException_when_responseInfo_is_null() {
        // given
        state.setResponseInfo(null);

        // when
        Throwable ex = catchThrowable(() -> handler.doChannelRead(ctxMock, msg));

        // then
        assertThat(ex).isInstanceOf(InvalidRipostePipelineException.class);
    }

    @Test
    public void doChannelRead_throws_InvalidRipostePipelineException_when_responseInfo_is_chunked_but_msg_is_not_ChunkedOutboundMessage() {
        // given
        doReturn(true).when(responseInfoMock).isChunkedResponse();
        msg = mock(LastOutboundMessageSendFullResponseInfo.class);

        // when
        Throwable ex = catchThrowable(() -> handler.doChannelRead(ctxMock, msg));

        // then
        assertThat(ex).isInstanceOf(InvalidRipostePipelineException.class);
    }

    @Test
    public void doChannelRead_throws_InvalidRipostePipelineException_when_responseInfo_is_not_chunked_but_msg_is_not_LastOutboundMessageSendFullResponseInfo() {
        // given
        doReturn(false).when(responseInfoMock).isChunkedResponse();
        msg = mock(ChunkedOutboundMessage.class);

        // when
        Throwable ex = catchThrowable(() -> handler.doChannelRead(ctxMock, msg));

        // then
        assertThat(ex).isInstanceOf(InvalidRipostePipelineException.class);
    }

}