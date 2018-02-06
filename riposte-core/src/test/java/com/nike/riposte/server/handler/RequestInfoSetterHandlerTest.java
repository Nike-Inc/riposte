package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.exception.InvalidHttpRequestException;
import com.nike.riposte.server.error.exception.RequestTooBigException;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link RequestInfoSetterHandler}
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class RequestInfoSetterHandlerTest {

    private RequestInfoSetterHandler handler;
    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private Endpoint<?> endpointMock;
    private HttpContent httpContentMock;
    private ByteBuf byteBufMock;
    private int maxRequestSizeInBytes;
    private RequestInfo<?> requestInfo;

    @Before
    public void beforeMethod() {
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        stateAttrMock = mock(Attribute.class);
        endpointMock = mock(Endpoint.class);
        maxRequestSizeInBytes = 10;
        httpContentMock = mock(HttpContent.class);
        byteBufMock = mock(ByteBuf.class);
        requestInfo = mock(RequestInfo.class);

        handler = new RequestInfoSetterHandler(maxRequestSizeInBytes);

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(endpointMock).when(stateMock).getEndpointForExecution();
        doReturn(byteBufMock).when(httpContentMock).content();
        doReturn(null).when(endpointMock).maxRequestSizeInBytesOverride();
        doReturn(requestInfo).when(stateMock).getRequestInfo();
    }

    @Test
    public void argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo_should_return_false() {
        assertThat(handler.argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(null, null, null, null)).isFalse();
    }

    @Test
    public void doChannelRead_creates_and_sets_RequestInfo_on_state_and_RequestInfo_is_marked_as_complete_with_all_chunks_if_msg_is_FullHttpRequest() {
        // given
        FullHttpRequest msgMock = mock(FullHttpRequest.class);
        String uri = "/some/url";
        HttpHeaders headers = new DefaultHttpHeaders();
        doReturn(uri).when(msgMock).getUri();
        doReturn(headers).when(msgMock).headers();
        doReturn(headers).when(msgMock).trailingHeaders();
        doReturn(byteBufMock).when(msgMock).content();
        doReturn(false).when(byteBufMock).isReadable();
        doReturn(HttpVersion.HTTP_1_1).when(msgMock).getProtocolVersion();
        doReturn(null).when(stateMock).getRequestInfo();

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
    public void doChannelRead_uses_existing_RequestInfo_on_state_if_available_and_does_not_recreate_it() {
        // given
        HttpRequest msgMock = mock(HttpRequest.class);
        String uri = "/some/url";
        HttpHeaders headers = new DefaultHttpHeaders();
        doReturn(uri).when(msgMock).getUri();
        doReturn(headers).when(msgMock).headers();
        doReturn(HttpVersion.HTTP_1_1).when(msgMock).getProtocolVersion();
        doReturn(requestInfo).when(stateMock).getRequestInfo();

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, msgMock);

        // then
        verify(stateMock, never()).setRequestInfo(any(RequestInfo.class));
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

    @DataProvider(value = {
        "true   |   false",
        "false  |   true",
        "true   |   true"
    }, splitBy = "\\|")
    @Test
    public void doChannelRead_releases_content_and_returns_do_not_fire_when_state_is_null_or_response_already_sent(
        boolean stateIsNull, boolean responseAlreadySent
    ) {
        // given
        if (stateIsNull)
            doReturn(null).when(stateAttrMock).get();

        if (responseAlreadySent) {
            doReturn(true).when(stateMock).isResponseSendingLastChunkSent();
        }

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, httpContentMock);

        // then
        verify(httpContentMock).release();
        assertThat(result).isEqualTo(PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT);
    }

    @Test
    public void doChannelRead_throws_exception_and_releases_content_when_no_request_info_when_HttpContent_message() {
        // given
        doReturn(null).when(stateMock).getRequestInfo();

        // when
        Throwable thrownException = Assertions.catchThrowable(() -> handler.doChannelRead(ctxMock, httpContentMock));

        // then
        assertThat(thrownException).isExactlyInstanceOf(IllegalStateException.class);
        verify(httpContentMock).release();
    }

    @Test
    public void doChannelRead_does_not_throw_exception_when_exceeding_global_max_size_when_request_validation_is_turned_off() {
        // given
        maxRequestSizeInBytes = 10;
        handler = new RequestInfoSetterHandler(maxRequestSizeInBytes);
        doReturn(0).when(endpointMock).maxRequestSizeInBytesOverride();
        doReturn(100).when(requestInfo).addContentChunk(anyObject());

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, httpContentMock);

        // then
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
        verify(httpContentMock).release();
    }

    @Test
    public void doChannelRead_allows_endpoints_to_override_max_request_size_setting_lower() {
        // given
        maxRequestSizeInBytes = 10;
        handler = new RequestInfoSetterHandler(maxRequestSizeInBytes);
        doReturn(1).when(endpointMock).maxRequestSizeInBytesOverride();
        doReturn(2).when(requestInfo).addContentChunk(anyObject());

        // when
        Throwable thrownException = Assertions.catchThrowable(() -> handler.doChannelRead(ctxMock, httpContentMock));

        // then
        assertThat(thrownException).isExactlyInstanceOf(RequestTooBigException.class);
        verify(httpContentMock).release();
    }

    @Test
    public void doChannelRead_allows_endpoints_to_override_max_request_size_setting_higher() {
        // given
        maxRequestSizeInBytes = 10;
        handler = new RequestInfoSetterHandler(maxRequestSizeInBytes);
        doReturn(100).when(endpointMock).maxRequestSizeInBytesOverride();
        doReturn(99).when(requestInfo).addContentChunk(anyObject());

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, httpContentMock);

        // then
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
        verify(httpContentMock).release();
    }

    @DataProvider(value = {
        "true   |   false",
        "false  |   true"
    }, splitBy = "\\|")
    @Test
    public void doChannelRead_override_uses_global_configured_max_request_size_when_necessary_no_error(
        boolean endpointIsNull, boolean endpointReturnsNullOverride
    ) {
        // given
        if (endpointIsNull)
            doReturn(null).when(stateMock).getEndpointForExecution();

        if (endpointReturnsNullOverride)
            doReturn(null).when(endpointMock).maxRequestSizeInBytesOverride();

        maxRequestSizeInBytes = 10;
        handler = new RequestInfoSetterHandler(maxRequestSizeInBytes);
        doReturn(5).when(requestInfo).addContentChunk(anyObject());

        // when
        PipelineContinuationBehavior result = handler.doChannelRead(ctxMock, httpContentMock);

        // then
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
        verify(httpContentMock).release();
    }

    @DataProvider(value = {
        "true   |   false",
        "false  |   true"
    }, splitBy = "\\|")
    @Test
    public void doChannelRead_no_endpoint_override_uses_global_configured_max_request_size_when_necessary_with_error(
        boolean endpointIsNull, boolean endpointReturnsNullOverride
    ) {
        // given
        // given
        if (endpointIsNull)
            doReturn(null).when(stateMock).getEndpointForExecution();

        if (endpointReturnsNullOverride)
            doReturn(null).when(endpointMock).maxRequestSizeInBytesOverride();
        
        maxRequestSizeInBytes = 10;
        handler = new RequestInfoSetterHandler(maxRequestSizeInBytes);

        doReturn(11).when(requestInfo).addContentChunk(anyObject());

        // when
        Throwable thrownException = Assertions.catchThrowable(() -> handler.doChannelRead(ctxMock, httpContentMock));

        // then
        assertThat(thrownException).isExactlyInstanceOf(RequestTooBigException.class);
        verify(httpContentMock).release();
    }

    @Test
    public void doChannelRead_HttpRequest_throws_exception_when_failed_decoder_result() {
        // given
        HttpRequest msgMock = mock(HttpRequest.class);
        DecoderResult decoderResult = mock(DecoderResult.class);
        doReturn(true).when(decoderResult).isFailure();
        doReturn(decoderResult).when(msgMock).getDecoderResult();
        doReturn(null).when(stateMock).getRequestInfo();

        // when
        Throwable thrownException = Assertions.catchThrowable(() -> handler.doChannelRead(ctxMock, msgMock));

        // then
        assertThat(thrownException).isExactlyInstanceOf(InvalidHttpRequestException.class);
    }

    @Test
    public void doChannelRead_HttpContent_throws_exception_when_failed_decoder_result() {
        // given
        DecoderResult decoderResult = mock(DecoderResult.class);
        doReturn(true).when(decoderResult).isFailure();
        doReturn(decoderResult).when(httpContentMock).getDecoderResult();

        // when
        Throwable thrownException = Assertions.catchThrowable(() -> handler.doChannelRead(ctxMock, httpContentMock));

        // then
        assertThat(thrownException).isExactlyInstanceOf(InvalidHttpRequestException.class);
        verify(httpContentMock).release();
    }
}