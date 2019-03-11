package com.nike.riposte.server.error.handler.impl;

import com.nike.riposte.server.error.handler.ErrorResponseBody;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A basic implementation of {@link ErrorResponseBody} that delegates serialization ({@link #bodyToSerialize()}) to
 * some other object you specify.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class DelegatedErrorResponseBody implements ErrorResponseBody {

    protected final @NotNull String errorId;
    protected final @Nullable Object bodyToSerialize;

    /**
     * Creates a new instance that uses the given errorId for {@link #errorId()} and the given bodyToSerialize for
     * {@link #bodyToSerialize()}.
     *
     * @param errorId This will be used as the {@link #errorId()}. It's recommended that you use a
     * {@link java.util.UUID#randomUUID()} for this. Cannot be null.
     * @param bodyToSerialize This will be used as the {@link #bodyToSerialize()}. Can be null - if you pass null
     * then an empty response body will be used.
     */
    public DelegatedErrorResponseBody(@NotNull String errorId, @Nullable Object bodyToSerialize) {
        //noinspection ConstantConditions
        if (errorId == null) {
            throw new IllegalArgumentException("errorId cannot be null.");
        }
        this.errorId = errorId;
        this.bodyToSerialize = bodyToSerialize;
    }

    @Override
    public @NotNull String errorId() {
        return errorId;
    }

    @Override
    public @Nullable Object bodyToSerialize() {
        return bodyToSerialize;
    }
}