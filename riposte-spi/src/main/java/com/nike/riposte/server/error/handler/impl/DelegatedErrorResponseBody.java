package com.nike.riposte.server.error.handler.impl;

import com.nike.riposte.server.error.handler.ErrorResponseBody;

/**
 * A basic implementation of {@link ErrorResponseBody} that delegates serialization ({@link #bodyToSerialize()}) to
 * some other object you specify.
 *
 * @author Nic Munroe
 */
public class DelegatedErrorResponseBody implements ErrorResponseBody {

    protected final String errorId;
    protected final Object bodyToSerialize;

    /**
     * Creates a new instance that uses the given errorId for {@link #errorId()} and the given bodyToSerialize for
     * {@link #bodyToSerialize()}.
     *
     * @param errorId This will be used as the {@link #errorId()}. It's recommended that you use a
     * {@link java.util.UUID#randomUUID()} for this.
     * @param bodyToSerialize This will be used as the {@link #bodyToSerialize()}.
     */
    public DelegatedErrorResponseBody(String errorId, Object bodyToSerialize) {
        this.errorId = errorId;
        this.bodyToSerialize = bodyToSerialize;
    }

    @Override
    public String errorId() {
        return errorId;
    }

    @Override
    public Object bodyToSerialize() {
        return bodyToSerialize;
    }
}