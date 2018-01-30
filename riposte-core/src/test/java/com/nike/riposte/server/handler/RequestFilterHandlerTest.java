package com.nike.riposte.server.handler;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessageSendFullResponseInfo;
import com.nike.riposte.server.error.exception.InvalidHttpRequestException;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.filter.RequestAndResponseFilter;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;

import static com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute.DO_CHANNEL_READ;
import static com.nike.riposte.server.handler.base.PipelineContinuationBehavior.CONTINUE;
import static com.nike.riposte.server.handler.base.PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link RequestFilterHandler}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class RequestFilterHandlerTest {

    private RequestFilterHandler handlerSpy;
    private RequestAndResponseFilter filter1Mock;
    private RequestAndResponseFilter filter2Mock;
    private List<RequestAndResponseFilter> filtersList;

    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;

    private HttpRequest firstChunkMsgMock;
    private LastHttpContent lastChunkMsgMock;

    private RequestInfo<?> requestInfoMock;

    @Before
    public void beforeMethod() {
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();

        firstChunkMsgMock = mock(HttpRequest.class);
        lastChunkMsgMock = mock(LastHttpContent.class);

        filter1Mock = mock(RequestAndResponseFilter.class);
        filter2Mock = mock(RequestAndResponseFilter.class);
        filtersList = Arrays.asList(filter1Mock, filter2Mock);

        handlerSpy = spy(new RequestFilterHandler(filtersList));

        requestInfoMock = mock(RequestInfo.class);

        state.setRequestInfo(requestInfoMock);
    }

    @Test
    public void constructor_uses_provided_list_if_not_empty() {
        // given
        List<RequestAndResponseFilter> filters = Collections.singletonList(mock(RequestAndResponseFilter.class));

        // when
        RequestFilterHandler handler = new RequestFilterHandler(filters);

        // then
        assertThat(handler.filters).isEqualTo(filters);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void constructor_uses_empty_list_if_passed_null_or_empty(boolean isNullList) {
        // given
        List<RequestAndResponseFilter> badFiltersList = (isNullList) ? null : Collections.emptyList();

        // when
        RequestFilterHandler handler = new RequestFilterHandler(badFiltersList);

        // then
        assertThat(handler.filters)
            .isNotNull()
            .isEmpty();
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void doChannelRead_HttpRequest_creates_and_sets_RequestInfo_on_state_only_if_state_not_already_set(
        boolean requestInfoAlreadySetOnState
    ) throws Exception {
        // given
        doReturn(CONTINUE).when(handlerSpy).handleFilterLogic(any(), any(), any(), any(), any());

        RequestInfo<?> requestInfoAlreadyOnState = (requestInfoAlreadySetOnState) ? requestInfoMock : null;
        state.setRequestInfo(requestInfoAlreadyOnState);

        HttpProcessingState stateSpy = spy(state);
        doReturn(stateSpy).when(stateAttributeMock).get();

        String uri = "/some/url";
        HttpHeaders headers = new DefaultHttpHeaders();
        doReturn(uri).when(firstChunkMsgMock).getUri();
        doReturn(headers).when(firstChunkMsgMock).headers();
        doReturn(HttpVersion.HTTP_1_1).when(firstChunkMsgMock).getProtocolVersion();

        // when
        handlerSpy.doChannelRead(ctxMock, firstChunkMsgMock);

        // then
        if (requestInfoAlreadySetOnState) {
            verify(stateSpy, never()).setRequestInfo(any(RequestInfo.class));
            assertThat(stateSpy.getRequestInfo()).isSameAs(requestInfoMock);
        }
        else {
            verify(stateSpy).setRequestInfo(any(RequestInfo.class));
            assertThat(stateSpy.getRequestInfo()).isNotEqualTo(requestInfoMock);
            assertThat(stateSpy.getRequestInfo().getUri()).isEqualTo(uri);
        }
    }

    @Test
    public void doChannelRead_HttpRequest_throws_exception_when_failed_decoder_result() {
        // given
        DecoderResult decoderResult = mock(DecoderResult.class);
        doReturn(true).when(decoderResult).isFailure();
        doReturn(decoderResult).when(firstChunkMsgMock).getDecoderResult();
        state.setRequestInfo(null);

        // when
        Throwable thrownException = Assertions.catchThrowable(() -> handlerSpy.doChannelRead(ctxMock, firstChunkMsgMock));

        // then
        assertThat(thrownException).isExactlyInstanceOf(InvalidHttpRequestException.class);
    }

    @DataProvider(value = {
        "CONTINUE",
        "DO_NOT_FIRE_CONTINUE_EVENT"
    }, splitBy = "\\|")
    @Test
    public void doChannelRead_delegates_to_handleFilterLogic_with_first_chunk_method_references_when_msg_is_HttpRequest(
        PipelineContinuationBehavior expectedPipelineContinuationBehavior
    ) throws Exception {
        // given
        doReturn(expectedPipelineContinuationBehavior).when(handlerSpy).handleFilterLogic(any(), any(), any(), any(), any());

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, firstChunkMsgMock);

        // then
        assertThat(result).isEqualTo(expectedPipelineContinuationBehavior);

        ArgumentCaptor<BiFunction> normalFilterCallCaptor = ArgumentCaptor.forClass(BiFunction.class);
        ArgumentCaptor<BiFunction> shortCircuitFilterCallCaptor = ArgumentCaptor.forClass(BiFunction.class);
        verify(handlerSpy).handleFilterLogic(eq(ctxMock),
                                             eq(firstChunkMsgMock),
                                             eq(state),
                                             normalFilterCallCaptor.capture(),
                                             shortCircuitFilterCallCaptor.capture());

        BiFunction<RequestAndResponseFilter, RequestInfo, RequestInfo> normalFilterCall = normalFilterCallCaptor.getValue();
        BiFunction<RequestAndResponseFilter, RequestInfo, Pair<RequestInfo, Optional<ResponseInfo<?>>>> shortCircuitFilterCall = shortCircuitFilterCallCaptor.getValue();

        RequestAndResponseFilter filterForNormalCallMock = mock(RequestAndResponseFilter.class);
        normalFilterCall.apply(filterForNormalCallMock, requestInfoMock);
        verify(filterForNormalCallMock).filterRequestFirstChunkNoPayload(requestInfoMock, ctxMock);

        RequestAndResponseFilter filterForShortCircuitCallMock = mock(RequestAndResponseFilter.class);
        shortCircuitFilterCall.apply(filterForShortCircuitCallMock, requestInfoMock);
        verify(filterForShortCircuitCallMock).filterRequestFirstChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);
    }

    @DataProvider(value = {
        "CONTINUE",
        "DO_NOT_FIRE_CONTINUE_EVENT"
    }, splitBy = "\\|")
    @Test
    public void doChannelRead_delegates_to_handleFilterLogic_with_last_chunk_method_references_when_msg_is_LastHttpContent(
        PipelineContinuationBehavior expectedPipelineContinuationBehavior
    ) throws Exception {
        // given
        doReturn(expectedPipelineContinuationBehavior).when(handlerSpy).handleFilterLogic(any(), any(), any(), any(), any());

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, lastChunkMsgMock);

        // then
        assertThat(result).isEqualTo(expectedPipelineContinuationBehavior);

        ArgumentCaptor<BiFunction> normalFilterCallCaptor = ArgumentCaptor.forClass(BiFunction.class);
        ArgumentCaptor<BiFunction> shortCircuitFilterCallCaptor = ArgumentCaptor.forClass(BiFunction.class);
        verify(handlerSpy).handleFilterLogic(eq(ctxMock),
                                             eq(lastChunkMsgMock),
                                             eq(state),
                                             normalFilterCallCaptor.capture(),
                                             shortCircuitFilterCallCaptor.capture());

        BiFunction<RequestAndResponseFilter, RequestInfo, RequestInfo> normalFilterCall = normalFilterCallCaptor.getValue();
        BiFunction<RequestAndResponseFilter, RequestInfo, Pair<RequestInfo, Optional<ResponseInfo<?>>>> shortCircuitFilterCall = shortCircuitFilterCallCaptor.getValue();

        RequestAndResponseFilter filterForNormalCallMock = mock(RequestAndResponseFilter.class);
        normalFilterCall.apply(filterForNormalCallMock, requestInfoMock);
        verify(filterForNormalCallMock).filterRequestLastChunkWithFullPayload(requestInfoMock, ctxMock);

        RequestAndResponseFilter filterForShortCircuitCallMock = mock(RequestAndResponseFilter.class);
        shortCircuitFilterCall.apply(filterForShortCircuitCallMock, requestInfoMock);
        verify(filterForShortCircuitCallMock).filterRequestLastChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);
    }

    @Test
    public void doChannelRead_does_nothing_and_returns_CONTINUE_when_msg_is_not_first_or_last_chunk() throws Exception {
        // given
        HttpContent contentChunkMsg = mock(HttpContent.class);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, contentChunkMsg);

        // then
        assertThat(result).isEqualTo(CONTINUE);
        verify(handlerSpy, never()).handleFilterLogic(any(), any(), any(), any(), any());
    }

    private class HandleFilterLogicMethodCallArgs {
        public final Object msg;
        public final HttpProcessingState httpState;
        public final BiFunction<RequestAndResponseFilter, RequestInfo, RequestInfo> normalFilterCall;
        public final BiFunction<RequestAndResponseFilter, RequestInfo, Pair<RequestInfo, Optional<ResponseInfo<?>>>> shortCircuitFilterCall;

        private HandleFilterLogicMethodCallArgs(boolean isFirstChunk) {
            this.msg = (isFirstChunk) ? firstChunkMsgMock : lastChunkMsgMock;
            this.httpState = state;
            this.normalFilterCall = (isFirstChunk)
                                    ? (filter, request) -> filter.filterRequestFirstChunkNoPayload(request, ctxMock)
                                    : (filter, request) -> filter.filterRequestLastChunkWithFullPayload(request, ctxMock);
            this.shortCircuitFilterCall = (isFirstChunk)
                                          ? (filter, request) -> filter.filterRequestFirstChunkWithOptionalShortCircuitResponse(request, ctxMock)
                                          : (filter, request) -> filter.filterRequestLastChunkWithOptionalShortCircuitResponse(request, ctxMock);
        }
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void handleFilterLogic_executes_all_filters_and_uses_original_request_when_filters_are_not_short_circuiting_and_return_null(boolean isFirstChunk) {
        // given
        HandleFilterLogicMethodCallArgs args = new HandleFilterLogicMethodCallArgs(isFirstChunk);
        filtersList.forEach(filter -> doReturn(false).when(filter).isShortCircuitRequestFilter());

        // when
        PipelineContinuationBehavior result = handlerSpy.handleFilterLogic(ctxMock, args.msg, args.httpState, args.normalFilterCall, args.shortCircuitFilterCall);

        // then
        assertThat(result).isEqualTo(CONTINUE);
        filtersList.forEach(filter -> {
            if (isFirstChunk)
                verify(filter).filterRequestFirstChunkNoPayload(requestInfoMock, ctxMock);
            else
                verify(filter).filterRequestLastChunkWithFullPayload(requestInfoMock, ctxMock);
        });
        assertThat(state.getRequestInfo()).isSameAs(requestInfoMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void handleFilterLogic_executes_all_filters_and_uses_original_request_when_filters_are_short_circuiting_and_return_null(boolean isFirstChunk) {
        // given
        HandleFilterLogicMethodCallArgs args = new HandleFilterLogicMethodCallArgs(isFirstChunk);
        filtersList.forEach(filter -> doReturn(true).when(filter).isShortCircuitRequestFilter());

        // when
        PipelineContinuationBehavior result = handlerSpy.handleFilterLogic(ctxMock, args.msg, args.httpState, args.normalFilterCall, args.shortCircuitFilterCall);

        // then
        assertThat(result).isEqualTo(CONTINUE);
        filtersList.forEach(filter -> {
            if (isFirstChunk)
                verify(filter).filterRequestFirstChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);
            else
                verify(filter).filterRequestLastChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);
        });
        assertThat(state.getRequestInfo()).isSameAs(requestInfoMock);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void handleFilterLogic_executes_all_filters_and_uses_original_request_when_filters_are_mixed_type_and_return_null(boolean isFirstChunk) {
        // given
        HandleFilterLogicMethodCallArgs args = new HandleFilterLogicMethodCallArgs(isFirstChunk);
        doReturn(true).when(filtersList.get(0)).isShortCircuitRequestFilter();
        doReturn(false).when(filtersList.get(1)).isShortCircuitRequestFilter();

        // when
        PipelineContinuationBehavior result = handlerSpy.handleFilterLogic(ctxMock, args.msg, args.httpState, args.normalFilterCall, args.shortCircuitFilterCall);

        // then
        assertThat(result).isEqualTo(CONTINUE);
        filtersList.forEach(filter -> {
            boolean shortCircuiting = filter.isShortCircuitRequestFilter();

            if (isFirstChunk) {
                if (shortCircuiting)
                    verify(filter).filterRequestFirstChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);
                else
                    verify(filter).filterRequestFirstChunkNoPayload(requestInfoMock, ctxMock);
            }
            else {
                if (shortCircuiting)
                    verify(filter).filterRequestLastChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);
                else
                    verify(filter).filterRequestLastChunkWithFullPayload(requestInfoMock, ctxMock);
            }
        });
        assertThat(state.getRequestInfo()).isSameAs(requestInfoMock);
    }

    @DataProvider(value = {
        "true   |   0",
        "true   |   1",
        "false  |   0",
        "false  |   1"
    }, splitBy = "\\|")
    @Test
    public void handleFilterLogic_gracefully_handles_a_filter_throwing_an_exception_and_continues_processing_other_filters(boolean isFirstChunk, int explodingFilterIndex) {
        // given
        HandleFilterLogicMethodCallArgs args = new HandleFilterLogicMethodCallArgs(isFirstChunk);
        doThrow(new RuntimeException("kaboom")).when(filtersList.get(explodingFilterIndex)).filterRequestFirstChunkNoPayload(any(), any());
        doThrow(new RuntimeException("kaboom")).when(filtersList.get(explodingFilterIndex)).filterRequestLastChunkWithFullPayload(any(), any());

        // when
        handlerSpy.handleFilterLogic(ctxMock, args.msg, args.httpState, args.normalFilterCall, args.shortCircuitFilterCall);

        // then
        filtersList.forEach(filter -> {
            if (isFirstChunk)
                verify(filter).filterRequestFirstChunkNoPayload(requestInfoMock, ctxMock);
            else
                verify(filter).filterRequestLastChunkWithFullPayload(requestInfoMock, ctxMock);
        });
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void handleFilterLogic_executes_all_filters_and_uses_requestInfo_returned_by_non_short_circuiting_filters(boolean isFirstChunk) {
        // given
        HandleFilterLogicMethodCallArgs args = new HandleFilterLogicMethodCallArgs(isFirstChunk);

        RequestInfo<?> firstFilterResult = mock(RequestInfo.class);
        RequestInfo<?> secondFilterResult = mock(RequestInfo.class);

        doReturn(firstFilterResult).when(filter1Mock).filterRequestFirstChunkNoPayload(any(), any());
        doReturn(firstFilterResult).when(filter1Mock).filterRequestLastChunkWithFullPayload(any(), any());

        doReturn(secondFilterResult).when(filter2Mock).filterRequestFirstChunkNoPayload(any(), any());
        doReturn(secondFilterResult).when(filter2Mock).filterRequestLastChunkWithFullPayload(any(), any());

        // when
        PipelineContinuationBehavior result = handlerSpy.handleFilterLogic(ctxMock, args.msg, args.httpState, args.normalFilterCall, args.shortCircuitFilterCall);

        // then
        assertThat(result).isEqualTo(CONTINUE);

        // First filter should have been passed the original request.
        if (isFirstChunk)
            verify(filter1Mock).filterRequestFirstChunkNoPayload(requestInfoMock, ctxMock);
        else
            verify(filter1Mock).filterRequestLastChunkWithFullPayload(requestInfoMock, ctxMock);

        // Second filter should have been passed the result of the first filter.
        if (isFirstChunk)
            verify(filter2Mock).filterRequestFirstChunkNoPayload(firstFilterResult, ctxMock);
        else
            verify(filter2Mock).filterRequestLastChunkWithFullPayload(firstFilterResult, ctxMock);

        // The state should have been updated with the result of the second filter.
        assertThat(state.getRequestInfo()).isSameAs(secondFilterResult);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void handleFilterLogic_executes_all_filters_and_uses_requestInfo_returned_by_short_circuiting_filters_when_they_do_not_short_circuit(boolean isFirstChunk) {
        // given
        HandleFilterLogicMethodCallArgs args = new HandleFilterLogicMethodCallArgs(isFirstChunk);
        filtersList.forEach(filter -> doReturn(true).when(filter).isShortCircuitRequestFilter());

        RequestInfo<?> firstFilterResult = mock(RequestInfo.class);
        RequestInfo<?> secondFilterResult = mock(RequestInfo.class);

        // Do a mix of empty Optional vs null for the response to hit branch coverage (both indicate no short circuiting response)
        doReturn(Pair.of(firstFilterResult, Optional.empty())).when(filter1Mock).filterRequestFirstChunkWithOptionalShortCircuitResponse(any(), any());
        doReturn(Pair.of(firstFilterResult, null)).when(filter1Mock).filterRequestLastChunkWithOptionalShortCircuitResponse(any(), any());

        doReturn(Pair.of(secondFilterResult, null)).when(filter2Mock).filterRequestFirstChunkWithOptionalShortCircuitResponse(any(), any());
        doReturn(Pair.of(secondFilterResult, Optional.empty())).when(filter2Mock).filterRequestLastChunkWithOptionalShortCircuitResponse(any(), any());

        // when
        PipelineContinuationBehavior result = handlerSpy.handleFilterLogic(ctxMock, args.msg, args.httpState, args.normalFilterCall, args.shortCircuitFilterCall);

        // then
        assertThat(result).isEqualTo(CONTINUE);

        // First filter should have been passed the original request.
        if (isFirstChunk)
            verify(filter1Mock).filterRequestFirstChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);
        else
            verify(filter1Mock).filterRequestLastChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);

        // Second filter should have been passed the result of the first filter.
        if (isFirstChunk)
            verify(filter2Mock).filterRequestFirstChunkWithOptionalShortCircuitResponse(firstFilterResult, ctxMock);
        else
            verify(filter2Mock).filterRequestLastChunkWithOptionalShortCircuitResponse(firstFilterResult, ctxMock);

        // The state should have been updated with the result of the second filter.
        assertThat(state.getRequestInfo()).isSameAs(secondFilterResult);
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void handleFilterLogic_executes_all_filters_and_uses_requestInfo_returned_by_filters_when_filters_are_mixed_type_and_do_not_short_circuit(boolean isFirstChunk) {
        // given
        HandleFilterLogicMethodCallArgs args = new HandleFilterLogicMethodCallArgs(isFirstChunk);
        doReturn(true).when(filtersList.get(0)).isShortCircuitRequestFilter();
        doReturn(false).when(filtersList.get(1)).isShortCircuitRequestFilter();

        RequestInfo<?> firstFilterResult = mock(RequestInfo.class);
        RequestInfo<?> secondFilterResult = mock(RequestInfo.class);

        // Do a mix of empty Optional vs null for the response to hit branch coverage (both indicate no short circuiting response)
        doReturn(Pair.of(firstFilterResult, Optional.empty())).when(filter1Mock).filterRequestFirstChunkWithOptionalShortCircuitResponse(any(), any());
        doReturn(Pair.of(firstFilterResult, null)).when(filter1Mock).filterRequestLastChunkWithOptionalShortCircuitResponse(any(), any());

        doReturn(secondFilterResult).when(filter2Mock).filterRequestFirstChunkNoPayload(any(), any());
        doReturn(secondFilterResult).when(filter2Mock).filterRequestLastChunkWithFullPayload(any(), any());

        // when
        PipelineContinuationBehavior result = handlerSpy.handleFilterLogic(ctxMock, args.msg, args.httpState, args.normalFilterCall, args.shortCircuitFilterCall);

        // then
        assertThat(result).isEqualTo(CONTINUE);

        // First filter should have been passed the original request.
        if (isFirstChunk)
            verify(filter1Mock).filterRequestFirstChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);
        else
            verify(filter1Mock).filterRequestLastChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);

        // Second filter should have been passed the result of the first filter.
        if (isFirstChunk)
            verify(filter2Mock).filterRequestFirstChunkNoPayload(firstFilterResult, ctxMock);
        else
            verify(filter2Mock).filterRequestLastChunkWithFullPayload(firstFilterResult, ctxMock);

        // The state should have been updated with the result of the second filter.
        assertThat(state.getRequestInfo()).isSameAs(secondFilterResult);
    }

    @DataProvider(value = {
        "true   |   0   |   true",
        "true   |   0   |   false",
        "true   |   1   |   true",
        "true   |   1   |   false",
        "false  |   0   |   true",
        "false  |   0   |   false",
        "false  |   1   |   true",
        "false  |   1   |   false"
    }, splitBy = "\\|")
    @Test
    public void handleFilterLogic_short_circuits_as_expected_if_filter_returns_valid_response(
        boolean isFirstChunk, int shortCircuitingFilterIndex, boolean filterReturnsModifiedRequestInfo
    ) {
        // given
        HandleFilterLogicMethodCallArgs args = new HandleFilterLogicMethodCallArgs(isFirstChunk);
        RequestAndResponseFilter shortCircuitingFilter = filtersList.get(shortCircuitingFilterIndex);
        doReturn(true).when(shortCircuitingFilter).isShortCircuitRequestFilter();

        RequestInfo<?> modifiedRequestInfoMock = mock(RequestInfo.class);
        RequestInfo<?> returnedRequestInfo = (filterReturnsModifiedRequestInfo) ? modifiedRequestInfoMock : null;

        ResponseInfo<?> returnedResponseInfoMock = mock(ResponseInfo.class);

        doReturn(Pair.of(returnedRequestInfo, Optional.of(returnedResponseInfoMock)))
            .when(shortCircuitingFilter).filterRequestFirstChunkWithOptionalShortCircuitResponse(any(), any());
        doReturn(Pair.of(returnedRequestInfo, Optional.of(returnedResponseInfoMock)))
            .when(shortCircuitingFilter).filterRequestLastChunkWithOptionalShortCircuitResponse(any(), any());

        // when
        PipelineContinuationBehavior result = handlerSpy.handleFilterLogic(ctxMock, args.msg, args.httpState, args.normalFilterCall, args.shortCircuitFilterCall);

        // then
        // Pipeline stops for the given msg event.
        assertThat(result).isEqualTo(DO_NOT_FIRE_CONTINUE_EVENT);

        // The filter's short-circuit-capable method was called.
        if (isFirstChunk)
            verify(shortCircuitingFilter).filterRequestFirstChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);
        else
            verify(shortCircuitingFilter).filterRequestLastChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);

        // The state is updated with the correct RequestInfo depending on what the filter returned.
        if (filterReturnsModifiedRequestInfo)
            assertThat(state.getRequestInfo()).isSameAs(modifiedRequestInfoMock);
        else
            assertThat(state.getRequestInfo()).isSameAs(requestInfoMock);

        // The state is updated with the ResponseInfo returned by the filter.
        assertThat(state.getResponseInfo()).isSameAs(returnedResponseInfoMock);

        // The short circuiting "we're all done, return the response to the caller" event is fired down the pipeline.
        verify(ctxMock).fireChannelRead(LastOutboundMessageSendFullResponseInfo.INSTANCE);
    }

    @DataProvider(value = {
        "true   |   0   |   true",
        "true   |   0   |   false",
        "true   |   1   |   true",
        "true   |   1   |   false",
        "false  |   0   |   true",
        "false  |   0   |   false",
        "false  |   1   |   true",
        "false  |   1   |   false"
    }, splitBy = "\\|")
    @Test
    public void handleFilterLogic_does_not_short_circuit_if_responseInfo_is_chunked(
        boolean isFirstChunk, int shortCircuitingFilterIndex, boolean filterReturnsModifiedRequestInfo
    ) {
        // given
        HandleFilterLogicMethodCallArgs args = new HandleFilterLogicMethodCallArgs(isFirstChunk);
        RequestAndResponseFilter shortCircuitingFilter = filtersList.get(shortCircuitingFilterIndex);
        doReturn(true).when(shortCircuitingFilter).isShortCircuitRequestFilter();

        RequestInfo<?> modifiedRequestInfoMock = mock(RequestInfo.class);
        RequestInfo<?> returnedRequestInfo = (filterReturnsModifiedRequestInfo) ? modifiedRequestInfoMock : null;

        ResponseInfo<?> chunkedResponseInfoMock = mock(ResponseInfo.class);
        doReturn(true).when(chunkedResponseInfoMock).isChunkedResponse();

        doReturn(Pair.of(returnedRequestInfo, Optional.of(chunkedResponseInfoMock)))
            .when(shortCircuitingFilter).filterRequestFirstChunkWithOptionalShortCircuitResponse(any(), any());
        doReturn(Pair.of(returnedRequestInfo, Optional.of(chunkedResponseInfoMock)))
            .when(shortCircuitingFilter).filterRequestLastChunkWithOptionalShortCircuitResponse(any(), any());

        // when
        PipelineContinuationBehavior result = handlerSpy.handleFilterLogic(ctxMock, args.msg, args.httpState, args.normalFilterCall, args.shortCircuitFilterCall);

        // then
        // Pipeline continues - no short circuit.
        assertThat(result).isEqualTo(CONTINUE);

        // The filter's short-circuit-capable method was called.
        if (isFirstChunk)
            verify(shortCircuitingFilter).filterRequestFirstChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);
        else
            verify(shortCircuitingFilter).filterRequestLastChunkWithOptionalShortCircuitResponse(requestInfoMock, ctxMock);

        // The state is updated with the correct RequestInfo
        if (filterReturnsModifiedRequestInfo)
            assertThat(state.getRequestInfo()).isSameAs(modifiedRequestInfoMock);
        else
            assertThat(state.getRequestInfo()).isSameAs(requestInfoMock);

        // The state is NOT updated with the ResponseInfo returned by the filter.
        assertThat(state.getResponseInfo()).isNull();

        // The short circuiting "we're all done, return the response to the caller" event is NOT fired down the pipeline.
        verify(ctxMock, never()).fireChannelRead(LastOutboundMessageSendFullResponseInfo.INSTANCE);
    }

    @Test
    public void argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo_only_returns_true_for_HttpRequest_or_LastHttpContent() {
        // given
        Object httpRequestMsg = mock(HttpRequest.class);
        Object lastHttpContentMsg = mock(LastHttpContent.class);
        Object httpMessageMsg = mock(HttpMessage.class);

        // expect
        assertThat(handlerSpy.argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
            DO_CHANNEL_READ, ctxMock, httpRequestMsg, null)
        ).isTrue();
        assertThat(handlerSpy.argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
            DO_CHANNEL_READ, ctxMock, lastHttpContentMsg, null)
        ).isTrue();
        assertThat(handlerSpy.argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
            DO_CHANNEL_READ, ctxMock, httpMessageMsg, null)
        ).isFalse();
    }

}
