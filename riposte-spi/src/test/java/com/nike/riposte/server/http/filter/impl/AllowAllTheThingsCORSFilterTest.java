package com.nike.riposte.server.http.filter.impl;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Optional;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link AllowAllTheThingsCORSFilter}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class AllowAllTheThingsCORSFilterTest {

    private AllowAllTheThingsCORSFilter filter;
    private RequestInfo requestMock;
    private ResponseInfo responseMock;
    private HttpHeaders responseHeadersMock;
    private ChannelHandlerContext ctxMock;

    @Before
    public void beforeMethod() {
        filter = new AllowAllTheThingsCORSFilter();
        requestMock = mock(RequestInfo.class);
        responseMock = mock(ResponseInfo.class);
        ctxMock = mock(ChannelHandlerContext.class);

        responseHeadersMock = mock(HttpHeaders.class);
        doReturn(responseHeadersMock).when(responseMock).getHeaders();
    }

    @DataProvider(value = {
        "OPTIONS    |   true",
        "GET        |   false",
        "POST       |   false",
        "PUT        |   false"
    }, splitBy = "\\|")
    @Test
    public void filterRequestFirstChunkWithOptionalShortCircuitResponse_short_circuits_on_CORS_preflight_OPTIONS_request(String httpMethodString, boolean expectShortCircuit) {
        // given
        HttpMethod method = HttpMethod.valueOf(httpMethodString);
        doReturn(method).when(requestMock).getMethod();

        // when
        Pair<RequestInfo<?>, Optional<ResponseInfo<?>>> result = filter.filterRequestFirstChunkWithOptionalShortCircuitResponse(requestMock, ctxMock);

        // then
        if (expectShortCircuit) {
            assertThat(result).isNotNull();
            assertThat(result.getLeft()).isSameAs(requestMock);
            assertThat(result.getRight()).isPresent();
            assertThat(result.getRight().get().getHttpStatusCode()).isEqualTo(200);
        }
        else
            assertThat(result).isNull();
    }

    @DataProvider(value = {
        "OPTIONS",
        "GET",
        "POST",
        "PUT"
    }, splitBy = "\\|")
    @Test
    public void filterRequestLastChunkWithOptionalShortCircuitResponse_always_returns_null(String httpMethodString) {
        // given
        HttpMethod method = HttpMethod.valueOf(httpMethodString);
        doReturn(method).when(requestMock).getMethod();

        // when
        Pair<RequestInfo<?>, Optional<ResponseInfo<?>>> result = filter.filterRequestLastChunkWithOptionalShortCircuitResponse(requestMock, ctxMock);

        // then
        assertThat(result).isNull();
    }

    @Test
    public void filterResponse_always_sets_wildcard_do_whatever_you_want_CORS_header() {
        // when
        ResponseInfo<?> result = filter.filterResponse(responseMock, requestMock, ctxMock);

        // then
        assertThat(result).isSameAs(responseMock);
        verify(responseHeadersMock).set("Access-Control-Allow-Origin", "*");
    }

}