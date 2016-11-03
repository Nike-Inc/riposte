package com.nike.trace.netty;

import com.nike.wingtips.http.RequestWithHeaders;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

/**
 * Adapter for {@link HttpRequest} to be used as {@link RequestWithHeaders}.
 *
 * @author Nic Munroe
 */
public class RequestWithHeadersNettyAdapter implements RequestWithHeaders {

    @SuppressWarnings("WeakerAccess")
    protected final HttpRequest httpRequest;

    public RequestWithHeadersNettyAdapter(HttpRequest httpRequest) {
        if (httpRequest == null)
            throw new IllegalArgumentException("httpRequest cannot be null");

        this.httpRequest = httpRequest;
    }

    @Override
    public String getHeader(String headerName) {
        HttpHeaders headers = httpRequest.headers();
        if (headers == null)
            return null;

        return headers.get(headerName);
    }

    @Override
    public Object getAttribute(String name) {
        return null;
    }
}
