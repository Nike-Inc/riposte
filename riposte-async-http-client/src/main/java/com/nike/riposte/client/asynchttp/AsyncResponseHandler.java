package com.nike.riposte.client.asynchttp;

import org.asynchttpclient.Response;

/**
 * Interface representing a handler for an async downstream HTTP call's response. Used by {@link AsyncHttpClientHelper}.
 *
 * @author Nic Munroe
 */
public interface AsyncResponseHandler<T> {

    /**
     * @return The result of handling the given response.
     */
    T handleResponse(Response response) throws Throwable;

}
