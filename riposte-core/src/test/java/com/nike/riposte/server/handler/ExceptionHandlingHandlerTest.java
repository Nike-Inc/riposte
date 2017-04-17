package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.exception.IncompleteHttpCallTimeoutException;
import com.nike.riposte.server.error.exception.TooManyOpenChannelsException;
import com.nike.riposte.server.error.exception.UnexpectedMajorErrorHandlingError;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.error.handler.ErrorResponseInfo;
import com.nike.riposte.server.error.handler.RiposteErrorHandler;
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.impl.FullResponseInfo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.Attribute;

import static com.nike.riposte.server.handler.base.PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link com.nike.riposte.server.handler.ExceptionHandlingHandler}
 */
@RunWith(DataProviderRunner.class)
public class ExceptionHandlingHandlerTest {

    private ExceptionHandlingHandler handler;
    private RiposteErrorHandler riposteErrorHandlerMock;
    private RiposteUnhandledErrorHandler riposteUnhandledErrorHandlerMock;
    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;

    @Before
    public void beforeMethod() {
        riposteErrorHandlerMock = mock(RiposteErrorHandler.class);
        riposteUnhandledErrorHandlerMock = mock(RiposteUnhandledErrorHandler.class);
        handler = new ExceptionHandlingHandler(riposteErrorHandlerMock, riposteUnhandledErrorHandlerMock);
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();
    }

    @Test
    public void constructor_works_with_valid_args() {
        // given
        ExceptionHandlingHandler handler = new ExceptionHandlingHandler(mock(RiposteErrorHandler.class), mock(RiposteUnhandledErrorHandler.class));

        // expect
        assertThat(handler, notNullValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_riposteErrorHandler_is_null() {
        // expect
        new ExceptionHandlingHandler(null, mock(RiposteUnhandledErrorHandler.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_riposteUnhandledErrorHandler_is_null() {
        // expect
        new ExceptionHandlingHandler(mock(RiposteErrorHandler.class), null);
    }

    @Test
    public void doExceptionCaught_should_call_getStateAndCreateIfNeeded_and_then_processError_and_then_set_response_on_state_and_return_CONTINUE() throws Exception {
        // given
        ExceptionHandlingHandler handlerSpy = spy(handler);
        Throwable cause = new Exception("intentional test exception");
        ResponseInfo<ErrorResponseBody> errorResponseMock = mock(ResponseInfo.class);
        doReturn(errorResponseMock).when(handlerSpy).processError(state, null, cause);
        assertThat(state.getResponseInfo(), nullValue());

        // when
        PipelineContinuationBehavior result = handlerSpy.doExceptionCaught(ctxMock, cause);

        // then
        verify(handlerSpy).getStateAndCreateIfNeeded(ctxMock, cause);
        verify(handlerSpy).processError(state, null, cause);
        assertThat(state.getResponseInfo(), is(errorResponseMock));
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void doExceptionCaught_should_do_nothing_and_return_DO_NOT_FIRE_CONTINUE_EVENT_if_response_sending_already_started()
        throws Exception {
        // given
        ExceptionHandlingHandler handlerSpy = spy(handler);
        Throwable cause = new Exception("intentional test exception");
        ResponseInfo<?> responseInfoMock = mock(ResponseInfo.class);
        doReturn(true).when(responseInfoMock).isResponseSendingStarted();
        state.setResponseInfo(responseInfoMock);

        // when
        PipelineContinuationBehavior result = handlerSpy.doExceptionCaught(ctxMock, cause);

        // then
        verify(handlerSpy, never()).processError(any(HttpProcessingState.class), any(Object.class), any(Throwable.class));
        Assertions.assertThat(result).isEqualTo(DO_NOT_FIRE_CONTINUE_EVENT);
    }

    @DataProvider
    public static List<List<Throwable>> exceptionsThatShouldForceCloseConnection() {
        return Arrays.asList(
            singletonList(mock(TooManyOpenChannelsException.class)),
            singletonList(mock(IncompleteHttpCallTimeoutException.class))
        );
    }

    @UseDataProvider("exceptionsThatShouldForceCloseConnection")
    @Test
    public void doExceptionCaught_should_setForceConnectionCloseAfterResponseSent_to_true_on_request_when_exception_matches_certain_types(
        Throwable exThatShouldForceCloseConnection
    ) throws Exception {
        // given
        ExceptionHandlingHandler handlerSpy = spy(handler);
        ResponseInfo<ErrorResponseBody> errorResponseMock = mock(ResponseInfo.class);
        doReturn(errorResponseMock).when(handlerSpy).processError(state, null, exThatShouldForceCloseConnection);
        assertThat(state.getResponseInfo(), nullValue());

        // when
        PipelineContinuationBehavior result = handlerSpy.doExceptionCaught(ctxMock, exThatShouldForceCloseConnection);

        // then
        verify(errorResponseMock).setForceConnectionCloseAfterResponseSent(true);

        verify(handlerSpy).getStateAndCreateIfNeeded(ctxMock, exThatShouldForceCloseConnection);
        verify(handlerSpy).processError(state, null, exThatShouldForceCloseConnection);
        assertThat(state.getResponseInfo(), is(errorResponseMock));
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void doChannelRead_should_return_CONTINUE_and_do_nothing_else_if_request_has_been_handled() throws Exception {
        // given
        ExceptionHandlingHandler handlerSpy = spy(handler);
        ResponseInfo<?> responseInfoMock = mock(ResponseInfo.class);
        Object msg = new Object();
        state.setResponseInfo(responseInfoMock);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy, times(0)).processUnhandledError(any(HttpProcessingState.class), any(Object.class), any(Throwable.class));
        assertThat(state.getResponseInfo(), is(responseInfoMock));
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void doChannelRead_should_call_processUnhandledError_and_set_response_on_state_and_return_CONTINUE_if_request_has_not_been_handled() throws Exception {
        // given
        ExceptionHandlingHandler handlerSpy = spy(handler);
        ResponseInfo<ErrorResponseBody> errorResponseMock = mock(ResponseInfo.class);
        Object msg = new Object();
        doReturn(errorResponseMock).when(handlerSpy).processUnhandledError(eq(state), eq(msg), any(Throwable.class));
        assertThat(state.isRequestHandled(), is(false));

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy).processUnhandledError(eq(state), eq(msg), any(Throwable.class));
        assertThat(state.getResponseInfo(), is(errorResponseMock));
        assertThat(result, is(PipelineContinuationBehavior.CONTINUE));
    }

    @Test
    public void getStateAndCreateIfNeeded_uses_state_from_ctx_if_available() {
        // expect
        assertThat(handler.getStateAndCreateIfNeeded(ctxMock, null), is(state));
    }

    @Test
    public void getStateAndCreateIfNeeded_creates_new_state_if_ctx_state_is_null() {
        // given
        doReturn(null).when(stateAttributeMock).get();

        // when
        HttpProcessingState result = handler.getStateAndCreateIfNeeded(ctxMock, null);

        // then
        assertThat(result, notNullValue());
        assertThat(result, not(state));
        verify(stateAttributeMock).set(result);
    }

    @Test
    public void getRequestInfo_uses_requestInfo_from_state_if_available() {
        // given
        RequestInfo<?> requestInfoMock = mock(RequestInfo.class);
        state.setRequestInfo(requestInfoMock);

        // when
        RequestInfo<?> result = handler.getRequestInfo(state, null);

        // then
        assertThat(result, is(requestInfoMock));
    }

    @Test
    public void getRequestInfo_uses_dummy_instance_if_state_does_not_have_requestInfo_and_msg_is_null() {
        // given
        assertThat(state.getRequestInfo(), nullValue());

        // when
        RequestInfo<?> result = handler.getRequestInfo(state, null);

        // then
        assertThat(result.getUri(), is(RequestInfo.NONE_OR_UNKNOWN_TAG));
    }

    @Test
    public void getRequestInfo_uses_dummy_instance_if_state_does_not_have_requestInfo_and_msg_is_not_a_FullHttpRequest() {
        // given
        assertThat(state.getRequestInfo(), nullValue());

        // when
        RequestInfo<?> result = handler.getRequestInfo(state, new Object());

        // then
        assertThat(result.getUri(), is(RequestInfo.NONE_OR_UNKNOWN_TAG));
    }

    @Test
    public void getRequestInfo_creates_new_RequestInfo_based_on_msg_if_state_requestInfo_is_null_and_msg_is_a_HttpRequest() {
        // given
        assertThat(state.getRequestInfo(), nullValue());
        String expectedUri = "/some/uri/" + UUID.randomUUID().toString();
        HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, expectedUri);

        // when
        RequestInfo<?> result = handler.getRequestInfo(state, httpRequest);

        // then
        assertThat(result.getUri(), is(expectedUri));
    }

    @Test
    public void processError_gets_requestInfo_then_calls_riposteErrorHandler_then_converts_to_response_using_setupResponseInfoBasedOnErrorResponseInfo() throws UnexpectedMajorErrorHandlingError, JsonProcessingException {
        // given
        HttpProcessingState stateMock = mock(HttpProcessingState.class);
        Object msg = new Object();
        Throwable cause = new Exception();

        ExceptionHandlingHandler handlerSpy = spy(handler);
        RequestInfo<?> requestInfoMock = mock(RequestInfo.class);
        ErrorResponseInfo errorResponseInfoMock = mock(ErrorResponseInfo.class);

        RiposteErrorHandler riposteErrorHandlerMock = mock(RiposteErrorHandler.class);
        Whitebox.setInternalState(handlerSpy, "riposteErrorHandler", riposteErrorHandlerMock);

        doReturn(requestInfoMock).when(handlerSpy).getRequestInfo(stateMock, msg);
        doReturn(errorResponseInfoMock).when(riposteErrorHandlerMock).maybeHandleError(cause, requestInfoMock);

        // when
        ResponseInfo<ErrorResponseBody> response = handlerSpy.processError(stateMock, msg, cause);

        // then
        verify(handlerSpy).getRequestInfo(stateMock, msg);
        verify(riposteErrorHandlerMock).maybeHandleError(cause, requestInfoMock);
        ArgumentCaptor<ResponseInfo> responseInfoArgumentCaptor = ArgumentCaptor.forClass(ResponseInfo.class);
        verify(handlerSpy).setupResponseInfoBasedOnErrorResponseInfo(responseInfoArgumentCaptor.capture(), eq(errorResponseInfoMock));
        ResponseInfo<ErrorResponseBody> responseInfoPassedIntoSetupMethod = responseInfoArgumentCaptor.getValue();
        assertThat(response, is(responseInfoPassedIntoSetupMethod));
    }

    @Test
    public void processError_returns_value_of_processUnhandledError_if_riposteErrorHandler_returns_null() throws UnexpectedMajorErrorHandlingError, JsonProcessingException {
        // given
        HttpProcessingState stateMock = mock(HttpProcessingState.class);
        Object msg = new Object();
        Throwable cause = new Exception();

        ExceptionHandlingHandler handlerSpy = spy(handler);

        ResponseInfo<ErrorResponseBody> responseInfoMockFromCatchallMethod = mock(ResponseInfo.class);
        doReturn(null).when(riposteErrorHandlerMock).maybeHandleError(any(), any());
        doReturn(responseInfoMockFromCatchallMethod).when(handlerSpy).processUnhandledError(stateMock, msg, cause);

        // when
        ResponseInfo<ErrorResponseBody> response = handlerSpy.processError(stateMock, msg, cause);

        // then
        verify(riposteErrorHandlerMock).maybeHandleError(any(), any());
        assertThat(response, is(responseInfoMockFromCatchallMethod));
    }

    @Test
    public void processError_returns_value_of_processUnhandledError_if_riposteErrorHandler_explodes() throws UnexpectedMajorErrorHandlingError, JsonProcessingException {
        // given
        HttpProcessingState stateMock = mock(HttpProcessingState.class);
        Object msg = new Object();
        Throwable cause = new Exception();

        ExceptionHandlingHandler handlerSpy = spy(handler);

        ResponseInfo<ErrorResponseBody> responseInfoMockFromCatchallMethod = mock(ResponseInfo.class);
        doThrow(new RuntimeException()).when(riposteErrorHandlerMock).maybeHandleError(any(), any());
        doReturn(responseInfoMockFromCatchallMethod).when(handlerSpy).processUnhandledError(stateMock, msg, cause);

        // when
        ResponseInfo<ErrorResponseBody> response = handlerSpy.processError(stateMock, msg, cause);

        // then
        verify(riposteErrorHandlerMock).maybeHandleError(any(), any());
        assertThat(response, is(responseInfoMockFromCatchallMethod));
    }

    @Test
    public void processUnhandledError_uses_getRequestInfo_and_calls_riposteUnhandledErrorHandler_and_returns_value_of_setupResponseInfoBasedOnErrorResponseInfo() throws JsonProcessingException, UnexpectedMajorErrorHandlingError {
        // given
        HttpProcessingState stateMock = mock(HttpProcessingState.class);
        Object msg = new Object();
        Throwable cause = new Exception();

        ExceptionHandlingHandler handlerSpy = spy(handler);

        RequestInfo<?> requestInfoMock = mock(RequestInfo.class);
        ErrorResponseInfo errorResponseInfoMock = mock(ErrorResponseInfo.class);

        doReturn(requestInfoMock).when(handlerSpy).getRequestInfo(stateMock, msg);
        doReturn(errorResponseInfoMock).when(riposteUnhandledErrorHandlerMock).handleError(cause, requestInfoMock);

        // when
        ResponseInfo<ErrorResponseBody> response = handlerSpy.processUnhandledError(stateMock, msg, cause);

        // then
        verify(handlerSpy).getRequestInfo(stateMock, msg);
        verify(riposteUnhandledErrorHandlerMock).handleError(cause, requestInfoMock);
        ArgumentCaptor<ResponseInfo> responseInfoArgumentCaptor = ArgumentCaptor.forClass(ResponseInfo.class);
        verify(handlerSpy).setupResponseInfoBasedOnErrorResponseInfo(responseInfoArgumentCaptor.capture(), eq(errorResponseInfoMock));
        ResponseInfo<ErrorResponseBody> responseInfoPassedIntoSetupMethod = responseInfoArgumentCaptor.getValue();
        assertThat(response, is(responseInfoPassedIntoSetupMethod));
    }

    @Test
    public void setupResponseInfoBasedOnErrorResponseInfo_sets_response_content_and_httpStatusCode_and_adds_extra_headers() {
        // given
        ResponseInfo<ErrorResponseBody> responseInfo = new FullResponseInfo<>();
        ErrorResponseBody errorResponseBodyMock = mock(ErrorResponseBody.class);
        int httpStatusCode = 42;
        Map<String, List<String>> extraHeaders = new HashMap<>();
        extraHeaders.put("key1", Arrays.asList("foo", "bar"));
        extraHeaders.put("key2", Arrays.asList("baz"));

        ErrorResponseInfo errorInfoMock = mock(ErrorResponseInfo.class);
        doReturn(errorResponseBodyMock).when(errorInfoMock).getErrorResponseBody();
        doReturn(httpStatusCode).when(errorInfoMock).getErrorHttpStatusCode();
        doReturn(extraHeaders).when(errorInfoMock).getExtraHeadersToAddToResponse();

        // when
        handler.setupResponseInfoBasedOnErrorResponseInfo(responseInfo, errorInfoMock);

        // then
        assertThat(responseInfo.getContentForFullResponse(), is(errorResponseBodyMock));
        assertThat(responseInfo.getHttpStatusCode(), is(httpStatusCode));
        int numIndividualValuesInHeaderMap = extraHeaders.entrySet().stream().map(entry -> entry.getValue()).mapToInt(list -> list.size()).sum();
        assertThat(responseInfo.getHeaders().entries().size(), is(numIndividualValuesInHeaderMap));
        extraHeaders.entrySet().stream().forEach(expectedEntry -> assertThat(responseInfo.getHeaders().getAll(expectedEntry.getKey()), is(expectedEntry.getValue())));
    }

    @Test
    public void setupResponseInfoBasedOnErrorResponseInfo_sets_response_content_and_httpStatusCode_and_ignores_extra_headers_if_extra_headers_is_null() {
        // given
        ResponseInfo<ErrorResponseBody> responseInfo = new FullResponseInfo<>();
        ErrorResponseBody errorResponseBodyMock = mock(ErrorResponseBody.class);
        int httpStatusCode = 42;

        ErrorResponseInfo errorInfoMock = mock(ErrorResponseInfo.class);
        doReturn(errorResponseBodyMock).when(errorInfoMock).getErrorResponseBody();
        doReturn(httpStatusCode).when(errorInfoMock).getErrorHttpStatusCode();
        doReturn(null).when(errorInfoMock).getExtraHeadersToAddToResponse();

        // when
        handler.setupResponseInfoBasedOnErrorResponseInfo(responseInfo, errorInfoMock);

        // then
        assertThat(responseInfo.getContentForFullResponse(), is(errorResponseBodyMock));
        assertThat(responseInfo.getHttpStatusCode(), is(httpStatusCode));
        assertThat(responseInfo.getHeaders().entries().size(), is(0));
    }

    
}