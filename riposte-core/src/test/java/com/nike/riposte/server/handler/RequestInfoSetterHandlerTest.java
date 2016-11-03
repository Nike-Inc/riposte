package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.Attribute;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link RequestInfoSetterHandler}
 *
 * @author Nic Munroe
 */
public class RequestInfoSetterHandlerTest {

    private RequestInfoSetterHandler handler = new RequestInfoSetterHandler();;
    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private Attribute<HttpProcessingState> stateAttrMock;

    @Before
    public void beforeMethod() {
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        stateAttrMock = mock(Attribute.class);

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
    }

    @Test
    public void doChannelRead_creates_and_sets_RequestInfo_on_state_and_RequestInfo_is_marked_as_complete_with_all_chunks_if_msg_is_FullHttpRequest() {
        // given
        FullHttpRequest msgMock = mock(FullHttpRequest.class);
        String uri = "/some/url";
        HttpHeaders headers = new DefaultHttpHeaders();
        ByteBuf byteBufMock = mock(ByteBuf.class);
        doReturn(uri).when(msgMock).getUri();
        doReturn(headers).when(msgMock).headers();
        doReturn(headers).when(msgMock).trailingHeaders();
        doReturn(byteBufMock).when(msgMock).content();
        doReturn(false).when(byteBufMock).isReadable();
        doReturn(HttpVersion.HTTP_1_1).when(msgMock).getProtocolVersion();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msgMock);

        // then
        ArgumentCaptor<RequestInfo> requestInfoArgumentCaptor = ArgumentCaptor.forClass(RequestInfo.class);
        verify(stateMock).setRequestInfo(requestInfoArgumentCaptor.capture());
        RequestInfo requestInfo = requestInfoArgumentCaptor.getValue();
        assertThat(requestInfo.getUri()).isEqualTo(uri);
        assertThat(requestInfo.isCompleteRequestWithAllChunks()).isTrue();
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_checks_for_fully_send_responses_but_does_nothing_else_if_msg_is_not_HttpRequest_or_HttpContent() {
        // given
        HttpObject msgMock = mock(HttpObject.class);

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msgMock);

        // then
        verify(ctxMock).channel();
        verifyNoMoreInteractions(ctxMock);
        verify(stateMock).isResponseSendingLastChunkSent();
        verifyNoMoreInteractions(stateMock);
        verifyNoMoreInteractions(msgMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

}