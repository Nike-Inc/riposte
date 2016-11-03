package com.nike.riposte.server.handler;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.exception.MethodNotAllowed405Exception;
import com.nike.riposte.server.error.exception.MultipleMatchingEndpointsException;
import com.nike.riposte.server.error.exception.PathNotFound404Exception;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.util.Matcher;

import org.junit.Before;
import org.junit.Test;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link RoutingHandler}
 *
 * @author Nic Munroe
 */
public class RoutingHandlerTest {

    private RoutingHandler handlerSpy;
    private HttpProcessingState stateMock;
    private ChannelHandlerContext ctxMock;
    private Channel channelMock;
    private Attribute<HttpProcessingState> stateAttrMock;
    private RequestInfo requestInfoMock;
    private StandardEndpoint<?, ?> endpointMock;
    private Matcher matcherMock;
    private Collection<Endpoint<?>> endpoints;
    private String defaultPath = "/*/*/{vipname}/**";

    @Before
    public void beforeMethod() {
        stateMock = mock(HttpProcessingState.class);
        ctxMock = mock(ChannelHandlerContext.class);
        channelMock = mock(Channel.class);
        stateAttrMock = mock(Attribute.class);
        requestInfoMock = mock(RequestInfo.class);
        endpointMock = mock(StandardEndpoint.class);
        matcherMock = mock(Matcher.class);
        endpoints = new ArrayList<>(Collections.singleton(endpointMock));

        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(stateMock).when(stateAttrMock).get();
        doReturn(endpointMock).when(stateMock).getEndpointForExecution();
        doReturn(matcherMock).when(endpointMock).requestMatcher();
        doReturn(Optional.of(defaultPath)).when(matcherMock).matchesPath(any(RequestInfo.class));
        doReturn(true).when(matcherMock).matchesMethod(any(RequestInfo.class));
        doReturn(requestInfoMock).when(stateMock).getRequestInfo();

        handlerSpy = spy(new RoutingHandler(endpoints));
    }

    @Test
    public void constructor_sets_fields_based_on_incoming_args() {
        // when
        RoutingHandler theHandler = new RoutingHandler(endpoints);

        // then
        Collection<Endpoint<?>>
            actualEndpoints = (Collection<Endpoint<?>>) Whitebox.getInternalState(theHandler, "endpoints");
        assertThat(actualEndpoints).isSameAs(endpoints);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_arg_is_null() {
        // expect
        new RoutingHandler(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_if_arg_is_empty() {
        // expect
        new RoutingHandler(Collections.emptyList());
    }

    @Test
    public void doChannelRead_calls_findSingleEndpointForExecution_then_sets_path_params_and_endpoint_on_state_then_returns_CONTINUE_if_msg_is_HttpRequest() {
        // given
        doReturn(Arrays.asList(defaultPath)).when(matcherMock).matchingPathTemplates();
        HttpRequest msg = mock(HttpRequest.class);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy).findSingleEndpointForExecution(requestInfoMock);
        verify(requestInfoMock).setPathParamsBasedOnPathTemplate(defaultPath);
        verify(stateMock).setEndpointForExecution(endpointMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void doChannelRead_does_nothing_if_msg_is_not_HttpRequest() {
        // given
        String pathTemplate = "/some/path/with/{id}";
        Collection<String> pathTemplates = new ArrayList<String>() {{ add(pathTemplate); }};
        doReturn(pathTemplates).when(matcherMock).matchingPathTemplates();
        HttpObject msg = mock(HttpObject.class);

        // when
        PipelineContinuationBehavior result = handlerSpy.doChannelRead(ctxMock, msg);

        // then
        verify(handlerSpy).doChannelRead(ctxMock, msg);
        verifyNoMoreInteractions(handlerSpy);
        verifyNoMoreInteractions(requestInfoMock);
        verifyNoMoreInteractions(stateMock);
        assertThat(result).isEqualTo(PipelineContinuationBehavior.CONTINUE);
    }

    @Test
    public void findSingleEndpointForExecution_returns_matching_endpoint() {
        // given
        doReturn(Optional.of(defaultPath)).when(matcherMock).matchesPath(any(RequestInfo.class));
        doReturn(true).when(matcherMock).matchesMethod(any(RequestInfo.class));

        // when
        Pair<Endpoint<?>, String> result = handlerSpy.findSingleEndpointForExecution(requestInfoMock);

        // then
        assertThat(result.getKey()).isSameAs(endpointMock);
        assertThat(result.getValue()).isSameAs(defaultPath);
    }

    @Test(expected = PathNotFound404Exception.class)
    public void findSingleEndpointForExecution_throws_PathNotFound404Exception_if_no_matching_path() {
        // given
        doReturn(Optional.empty()).when(matcherMock).matchesPath(any(RequestInfo.class));

        // expect
        handlerSpy.findSingleEndpointForExecution(requestInfoMock);
    }

    @Test(expected = MethodNotAllowed405Exception.class)
    public void findSingleEndpointForExecution_throws_MethodNotAllowed405Exception_if_path_matches_but_method_does_not() {
        // given
        doReturn(Optional.of(defaultPath)).when(matcherMock).matchesPath(any(RequestInfo.class));
        doReturn(false).when(matcherMock).matchesMethod(any(RequestInfo.class));

        // expect
        handlerSpy.findSingleEndpointForExecution(requestInfoMock);
    }

    @Test(expected = MultipleMatchingEndpointsException.class)
    public void findSingleEndpointForExecution_throws_MultipleMatchingEndpointsException_if_multiple_endpoints_fully_match() {
        // given
        doReturn(Optional.of(defaultPath)).when(matcherMock).matchesPath(any(RequestInfo.class));
        doReturn(true).when(matcherMock).matchesMethod(any(RequestInfo.class));

        Endpoint<?> alsoMatchingEndpointMock = mock(Endpoint.class);
        doReturn(matcherMock).when(alsoMatchingEndpointMock).requestMatcher();

        endpoints.add(alsoMatchingEndpointMock);

        // when
        handlerSpy.findSingleEndpointForExecution(requestInfoMock);
    }
}