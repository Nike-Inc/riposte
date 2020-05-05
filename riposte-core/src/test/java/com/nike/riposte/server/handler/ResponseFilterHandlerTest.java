package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessageSendFullResponseInfo;
import com.nike.riposte.server.channelpipeline.message.OutboundMessage;
import com.nike.riposte.server.channelpipeline.message.OutboundMessageSendHeadersChunkFromResponseInfo;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.filter.RequestAndResponseFilter;
import com.nike.riposte.server.http.impl.ChunkedResponseInfo;
import com.nike.riposte.server.http.impl.FullResponseInfo;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;

import static com.nike.riposte.server.handler.base.PipelineContinuationBehavior.CONTINUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link ResponseFilterHandler}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class ResponseFilterHandlerTest {

    private ResponseFilterHandler handlerSpy;
    private RequestAndResponseFilter filter1Mock;
    private RequestAndResponseFilter filter2Mock;
    private List<RequestAndResponseFilter> filtersList;
    private List<RequestAndResponseFilter> reversedFiltersList;

    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;
    private RequestInfo<?> requestInfoMock;

    private OutboundMessageSendHeadersChunkFromResponseInfo chunkedResponseMsg;
    private LastOutboundMessageSendFullResponseInfo fullResponseMsg;

    private ChunkedResponseInfo chunkedResponseInfoMock;
    private FullResponseInfo<?> fullResponseInfoMock;

    private Throwable origErrorForOrigResponseInfoMock;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        requestInfoMock = mock(RequestInfo.class);

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();

        chunkedResponseMsg = mock(OutboundMessageSendHeadersChunkFromResponseInfo.class);
        fullResponseMsg = mock(LastOutboundMessageSendFullResponseInfo.class);

        filter1Mock = mock(RequestAndResponseFilter.class);
        filter2Mock = mock(RequestAndResponseFilter.class);
        filtersList = Arrays.asList(filter1Mock, filter2Mock);

        reversedFiltersList = new ArrayList<>(filtersList);
        Collections.reverse(reversedFiltersList);

        handlerSpy = spy(new ResponseFilterHandler(filtersList));

        chunkedResponseInfoMock = mock(ChunkedResponseInfo.class);
        fullResponseInfoMock = mock(FullResponseInfo.class);

        origErrorForOrigResponseInfoMock = mock(Throwable.class);

        state.setResponseInfo(fullResponseInfoMock, origErrorForOrigResponseInfoMock);
        state.setRequestInfo(requestInfoMock);
    }

    @Test
    public void constructor_uses_reversed_copy_of_passed_in_filter_list() {
        // given
        List<RequestAndResponseFilter> origListCopy = new ArrayList<>(filtersList);

        // when
        ResponseFilterHandler handler = new ResponseFilterHandler(filtersList);

        // then
        assertThat(handler.filtersInResponseProcessingOrder).isEqualTo(reversedFiltersList);
        assertThat(filtersList).isEqualTo(origListCopy);
        assertThat(filtersList).isNotEqualTo(reversedFiltersList);
    }

    @Test
    public void constructor_uses_empty_list_if_passed_null() {
        // when
        ResponseFilterHandler handler = new ResponseFilterHandler(null);

        // then
        assertThat(handler.filtersInResponseProcessingOrder).isEqualTo(Collections.emptyList());
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void doChannelRead_delegates_to_executeResponseFilters_and_returns_CONTINUE_if_msg_is_first_chunk_of_response(boolean chunkedResponse) throws Exception {
        // given
        OutboundMessage msg = (chunkedResponse)
                              ? mock(OutboundMessageSendHeadersChunkFromResponseInfo.class)
                              : mock(LastOutboundMessageSendFullResponseInfo.class);
        doNothing().when(handlerSpy).executeResponseFilters(any());

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        assertThat(result).isEqualTo(CONTINUE);
        verify(handlerSpy).executeResponseFilters(ctxMock);
    }

    @Test
    public void doChannelRead_does_nothing_and_returns_CONTINUE_if_msg_is_not_a_first_chunk() throws Exception {
        // given
        Object msg = new OutboundMessage() {};

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        assertThat(result).isEqualTo(CONTINUE);
        verify(handlerSpy, never()).executeResponseFilters(any());
    }

    @Test
    public void doExceptionCaught_delegates_to_executeResponseFilters_and_returns_CONTINUE() throws Exception {
        // given
        Throwable ex = mock(Throwable.class);
        doNothing().when(handlerSpy).executeResponseFilters(any());

        // when
        PipelineContinuationBehavior result = handlerSpy.doExceptionCaught(ctxMock, ex);

        // then
        assertThat(result).isEqualTo(CONTINUE);
        verify(handlerSpy).executeResponseFilters(ctxMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void executeResponseFilters_executes_all_filters_and_uses_original_response_and_orig_error_if_filters_return_null(boolean useChunkedResponse) throws Exception {
        // given
        ResponseInfo<?> responseInfoToUse = (useChunkedResponse) ? chunkedResponseInfoMock : fullResponseInfoMock;
        state.setResponseInfo(responseInfoToUse, origErrorForOrigResponseInfoMock);

        // when
        handlerSpy.executeResponseFilters(ctxMock);

        // then
        reversedFiltersList.forEach(filter -> verify(filter).filterResponse(responseInfoToUse, requestInfoMock, ctxMock));
        assertThat(state.getResponseInfo()).isSameAs(responseInfoToUse);
        assertThat(state.getErrorThatTriggeredThisResponse()).isSameAs(origErrorForOrigResponseInfoMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void executeResponseFilters_executes_all_filters_in_reverse_order_and_honors_result_and_orig_error_if_filters_return_non_null_responseInfo(boolean useChunkedResponse) throws Exception {
        // given
        ResponseInfo<?> origResponseInfo = (useChunkedResponse) ? chunkedResponseInfoMock : fullResponseInfoMock;
        state.setResponseInfo(origResponseInfo, origErrorForOrigResponseInfoMock);

        Class<? extends ResponseInfo> responseInfoClassToUse = (useChunkedResponse) ? ChunkedResponseInfo.class : FullResponseInfo.class;
        ResponseInfo<?> secondFilterResult = mock(responseInfoClassToUse);
        ResponseInfo<?> firstFilterResult = mock(responseInfoClassToUse);

        doReturn(secondFilterResult).when(filter2Mock).filterResponse(any(), any(), any());
        doReturn(firstFilterResult).when(filter1Mock).filterResponse(any(), any(), any());

        // when
        handlerSpy.executeResponseFilters(ctxMock);

        // then
        // Verify reverse order execution - filter 2 should get the original responseInfo, and filter 1 should get filter 2's result.
        verify(filter2Mock).filterResponse(origResponseInfo, requestInfoMock, ctxMock);
        verify(filter1Mock).filterResponse(secondFilterResult, requestInfoMock, ctxMock);
        // The final state should have filter 1's responseInfo since it executed last, and the original error should
        //      still be there.
        assertThat(state.getResponseInfo()).isSameAs(firstFilterResult);
        assertThat(state.getErrorThatTriggeredThisResponse()).isSameAs(origErrorForOrigResponseInfoMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void executeResponseFilters_does_nothing_if_the_first_chunk_of_the_response_is_already_sent(boolean useChunkedResponse) throws Exception {
        // given
        ResponseInfo<?> responseInfoToUse = (useChunkedResponse) ? chunkedResponseInfoMock : fullResponseInfoMock;
        state.setResponseInfo(responseInfoToUse, origErrorForOrigResponseInfoMock);

        doReturn(true).when(responseInfoToUse).isResponseSendingStarted();

        // when
        handlerSpy.executeResponseFilters(ctxMock);

        // then
        reversedFiltersList.forEach(filter -> verify(filter, never()).filterResponse(any(), any(), any()));
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void executeResponseFilters_does_nothing_if_something_blows_up_before_filters_are_executed(boolean useChunkedResponse) throws Exception {
        // given
        ResponseInfo<?> responseInfoToUse = (useChunkedResponse) ? chunkedResponseInfoMock : fullResponseInfoMock;
        state.setResponseInfo(responseInfoToUse, origErrorForOrigResponseInfoMock);

        doThrow(new RuntimeException("kaboom")).when(ctxMock).channel();

        // when
        handlerSpy.executeResponseFilters(ctxMock);

        // then
        reversedFiltersList.forEach(filter -> verify(filter, never()).filterResponse(any(), any(), any()));
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void executeResponseFilters_gracefully_handles_a_filter_throwing_an_exception_and_continues_processing_other_filters(boolean useChunkedResponse) throws Exception {
        // given
        ResponseInfo<?> origResponseInfo = (useChunkedResponse) ? chunkedResponseInfoMock : fullResponseInfoMock;
        state.setResponseInfo(origResponseInfo, origErrorForOrigResponseInfoMock);

        Class<? extends ResponseInfo> responseInfoClassToUse = (useChunkedResponse) ? ChunkedResponseInfo.class : FullResponseInfo.class;
        ResponseInfo<?> firstFilterResult = mock(responseInfoClassToUse);

        doThrow(new RuntimeException("kaboom")).when(filter2Mock).filterResponse(any(), any(), any());
        doReturn(firstFilterResult).when(filter1Mock).filterResponse(any(), any(), any());

        // when
        handlerSpy.executeResponseFilters(ctxMock);

        // then
        // Verify reverse order execution with the first filter executed (filter2Mock) blowing up - both filters should be executed,
        //      and both should be called with the orig response info (since filter2Mock didn't return a valid responseInfo).
        verify(filter2Mock).filterResponse(origResponseInfo, requestInfoMock, ctxMock);
        verify(filter1Mock).filterResponse(origResponseInfo, requestInfoMock, ctxMock);
        // The final state should have filter 1's responseInfo since it executed last and executed successfully, and
        //      the original error should still be there.
        assertThat(state.getResponseInfo()).isSameAs(firstFilterResult);
        assertThat(state.getErrorThatTriggeredThisResponse()).isSameAs(origErrorForOrigResponseInfoMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void executeResponseFilters_ignores_filter_responseInfo_if_its_class_is_not_the_same_as_the_previously_used_responseInfo(boolean useChunkedResponse) throws Exception {
        // given
        ResponseInfo<?> origResponseInfo = (useChunkedResponse) ? chunkedResponseInfoMock : fullResponseInfoMock;
        state.setResponseInfo(origResponseInfo, origErrorForOrigResponseInfoMock);

        Class<? extends ResponseInfo> badResponseInfoClassToUse = (useChunkedResponse) ? FullResponseInfo.class : ChunkedResponseInfo.class;
        ResponseInfo<?> secondFilterResult = mock(badResponseInfoClassToUse);
        ResponseInfo<?> firstFilterResult = mock(badResponseInfoClassToUse);

        doReturn(secondFilterResult).when(filter2Mock).filterResponse(any(), any(), any());
        doReturn(firstFilterResult).when(filter1Mock).filterResponse(any(), any(), any());

        // when
        handlerSpy.executeResponseFilters(ctxMock);

        // then
        // Verify that both filters were called, but since they return an incompatible ResponseInfo class compared to the original state,
        //      the original response info should be used each time.
        verify(filter2Mock).filterResponse(origResponseInfo, requestInfoMock, ctxMock);
        verify(filter1Mock).filterResponse(origResponseInfo, requestInfoMock, ctxMock);
        // The final state should have the original response info since none of the filters returned a compatible
        //      ResponseInfo class, and the original error should still be there.
        assertThat(state.getResponseInfo()).isSameAs(origResponseInfo);
        assertThat(state.getErrorThatTriggeredThisResponse()).isSameAs(origErrorForOrigResponseInfoMock);
    }

}