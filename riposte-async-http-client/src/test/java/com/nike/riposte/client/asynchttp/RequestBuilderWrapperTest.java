package com.nike.riposte.client.asynchttp;

import com.nike.fastbreak.CircuitBreaker;
import com.nike.fastbreak.CircuitBreakerImpl;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;


public class RequestBuilderWrapperTest {

    private RequestBuilderWrapper requestBuilderWrapper;
    private String url;
    private String httpMethod;
    private BoundRequestBuilder requestBuilder;
    private Optional<CircuitBreaker<Response>> customCircuitBreaker;
    private boolean disableCircuitBreaker;

    @Before
    public void setup() {
        url = "http://localhost.com";
        httpMethod = HttpMethod.GET.name();
        requestBuilder = mock(BoundRequestBuilder.class);
        customCircuitBreaker = Optional.of(new CircuitBreakerImpl<Response>());
        disableCircuitBreaker = true;
    }

    @Test
    public void constructor_sets_values_as_expected() {
        // given
        requestBuilderWrapper = new RequestBuilderWrapper(
                url,
                httpMethod,
                requestBuilder,
                customCircuitBreaker,
                disableCircuitBreaker);

        // then
        assertThat(requestBuilderWrapper.url).isEqualTo(url);
        assertThat(requestBuilderWrapper.httpMethod).isEqualTo(httpMethod);
        assertThat(requestBuilderWrapper.customCircuitBreaker).isEqualTo(customCircuitBreaker);
        assertThat(requestBuilderWrapper.disableCircuitBreaker).isEqualTo(disableCircuitBreaker);
    }

    @Test
    public void get_set_ChannelHandlerContext_works_as_expected() {
        // given
        requestBuilderWrapper = new RequestBuilderWrapper(
                url,
                httpMethod,
                requestBuilder,
                customCircuitBreaker,
                disableCircuitBreaker);

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        // when
        requestBuilderWrapper.setCtx(ctx);

        // then
        assertThat(ctx).isEqualTo(requestBuilderWrapper.getCtx());
    }

    @Test
    public void get_set_Url_works_as_expected() {
        // given
        requestBuilderWrapper = new RequestBuilderWrapper(
                url,
                httpMethod,
                requestBuilder,
                customCircuitBreaker,
                disableCircuitBreaker);

        String alternateUrl = "http://alteredUrl.testing";

        // when
        requestBuilderWrapper.setUrl(alternateUrl);

        // then
        assertThat(alternateUrl).isEqualTo(requestBuilderWrapper.getUrl());
        verify(requestBuilder).setUrl(alternateUrl);
    }

    @Test
    public void get_set_HttpMethod_works_as_expected() {
        // given
        requestBuilderWrapper = new RequestBuilderWrapper(
                url,
                httpMethod,
                requestBuilder,
                customCircuitBreaker,
                disableCircuitBreaker);

        String alteredMethod = "POST";

        // when
        requestBuilderWrapper.setHttpMethod(alteredMethod);

        // then
        assertThat(alteredMethod).isEqualTo(requestBuilderWrapper.getHttpMethod());
        verify(requestBuilder).setMethod(alteredMethod);
    }

    @Test
    public void get_set_DisableCircuitBreaker_works_as_expected() {
        // given
        requestBuilderWrapper = new RequestBuilderWrapper(
                url,
                httpMethod,
                requestBuilder,
                customCircuitBreaker,
                disableCircuitBreaker);

        boolean alteredDisableCircuitBreaker = false;

        // when
        requestBuilderWrapper.setDisableCircuitBreaker(alteredDisableCircuitBreaker);

        // then
        assertThat(requestBuilderWrapper.isDisableCircuitBreaker()).isFalse();
    }

    @Test
    public void get_set_CustomCircuitBreaker_works_as_expected() {
        // given
        requestBuilderWrapper = new RequestBuilderWrapper(
                url,
                httpMethod,
                requestBuilder,
                customCircuitBreaker,
                disableCircuitBreaker);

        Optional<CircuitBreaker<Response>> alteredCircuitBreaker = Optional.of(new CircuitBreakerImpl<>());

        // when
        requestBuilderWrapper.setCustomCircuitBreaker(alteredCircuitBreaker);

        // then
        assertThat(requestBuilderWrapper.getCustomCircuitBreaker()).isEqualTo(alteredCircuitBreaker);
    }
}
