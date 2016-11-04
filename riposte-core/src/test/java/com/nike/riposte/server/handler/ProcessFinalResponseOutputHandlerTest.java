package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.impl.FullResponseInfo;

import org.junit.Before;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link ProcessFinalResponseOutputHandler}
 *
 * @author Nic Munroe
 */
public class ProcessFinalResponseOutputHandlerTest {

    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private ChannelPromise promiseMock;
    private ResponseInfo<?> responseInfo;
    private ProcessFinalResponseOutputHandler handler = new ProcessFinalResponseOutputHandler();

    @Before
    public void beforeMethod() {
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        promiseMock = mock(ChannelPromise.class);
        channelMock = mock(Channel.class);
        stateAttrMock = mock(Attribute.class);
        responseInfo = new FullResponseInfo<>();

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(responseInfo).when(stateMock).getResponseInfo();
    }

    @Test
    public void write_calls_setActualResponseObject_on_state_if_msg_is_HttpResponse() throws Exception {
        // given
        HttpResponse msgMock = mock(HttpResponse.class);

        // when
        handler.write(ctxMock, msgMock, promiseMock);

        // then
        verify(stateMock).setActualResponseObject(msgMock);
    }

    @Test
    public void write_does_not_do_anything_with_state_if_msg_is_HttpResponse_but_state_is_null() throws Exception {
        // given
        HttpResponse msgMock = mock(HttpResponse.class);
        doReturn(null).when(stateAttrMock).get();

        // when
        handler.write(ctxMock, msgMock, promiseMock);

        // then
        verifyNoMoreInteractions(stateMock);
    }

    @Test
    public void write_sets_finalContentLength_if_msg_is_HttpContent_and_finalContentLength_is_null() throws Exception {
        // given
        HttpContent msgMock = mock(HttpContent.class);
        ByteBuf contentMock = mock(ByteBuf.class);
        int contentBytes = (int)(Math.random() * 10000);

        doReturn(contentMock).when(msgMock).content();
        doReturn(contentBytes).when(contentMock).readableBytes();

        assertThat(responseInfo.getFinalContentLength()).isNull();

        // when
        handler.write(ctxMock, msgMock, promiseMock);

        // then
        assertThat(responseInfo.getFinalContentLength()).isEqualTo(contentBytes);
    }

    @Test
    public void write_adds_to_finalContentLength_if_msg_is_HttpContent_and_finalContentLength_is_not_null() throws Exception {
        // given
        HttpContent msgMock = mock(HttpContent.class);
        ByteBuf contentMock = mock(ByteBuf.class);
        int contentBytes = (int)(Math.random() * 10000);

        doReturn(contentMock).when(msgMock).content();
        doReturn(contentBytes).when(contentMock).readableBytes();

        int initialFinalContentLengthValue = (int)(Math.random() * 10000);
        responseInfo.setFinalContentLength((long)initialFinalContentLengthValue);
        assertThat(responseInfo.getFinalContentLength()).isEqualTo(initialFinalContentLengthValue);

        // when
        handler.write(ctxMock, msgMock, promiseMock);

        // then
        assertThat(responseInfo.getFinalContentLength()).isEqualTo(initialFinalContentLengthValue + contentBytes);
    }

    @Test
    public void write_does_nothing_to_finalContentLength_if_msg_is_HttpContent_but_state_is_null() throws Exception {
        // given
        HttpContent msgMock = mock(HttpContent.class);
        ByteBuf contentMock = mock(ByteBuf.class);
        int contentBytes = (int)(Math.random() * 10000);

        doReturn(contentMock).when(msgMock).content();
        doReturn(contentBytes).when(contentMock).readableBytes();
        doReturn(null).when(stateAttrMock).get();

        assertThat(responseInfo.getFinalContentLength()).isNull();

        // when
        handler.write(ctxMock, msgMock, promiseMock);

        // then
        assertThat(responseInfo.getFinalContentLength()).isNull();
    }

    @Test
    public void write_does_nothing_to_finalContentLength_if_msg_is_HttpContent_but_responseInfo_is_null() throws Exception {
        // given
        HttpContent msgMock = mock(HttpContent.class);
        ByteBuf contentMock = mock(ByteBuf.class);
        int contentBytes = (int)(Math.random() * 10000);

        doReturn(contentMock).when(msgMock).content();
        doReturn(contentBytes).when(contentMock).readableBytes();
        doReturn(null).when(stateMock).getResponseInfo();

        assertThat(responseInfo.getFinalContentLength()).isNull();

        // when
        handler.write(ctxMock, msgMock, promiseMock);

        // then
        assertThat(responseInfo.getFinalContentLength()).isNull();
    }
}