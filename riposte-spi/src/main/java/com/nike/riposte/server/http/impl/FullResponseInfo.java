package com.nike.riposte.server.http.impl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.Set;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;

/**
 * An extension of {@link BaseResponseInfo} that represents full (not chunked) responses. {@link #isChunkedResponse()}
 * will return false, and support has been added for the full content (i.e. {@link #getContentForFullResponse()} and
 * {@link #setContentForFullResponse(Object)} are implemented). You should use the {@link FullResponseInfoBuilder} to
 * create new instances.
 *
 * @author Nic Munroe
 */
public class FullResponseInfo<T> extends BaseResponseInfo<T> {

    @SuppressWarnings("WeakerAccess")
    protected @Nullable T contentForFullResponse;

    /**
     * The "populate everything" constructor. It's recommended that you use the {@link FullResponseInfoBuilder} instead.
     */
    public FullResponseInfo(
        @Nullable T contentForFullResponse,
        @Nullable Integer httpStatusCode,
        @Nullable HttpHeaders headers,
        @Nullable String desiredContentWriterMimeType,
        @Nullable Charset desiredContentWriterEncoding,
        @Nullable Set<Cookie> cookies,
        boolean preventCompressedOutput
    ) {
        super(httpStatusCode, headers, desiredContentWriterMimeType, desiredContentWriterEncoding, cookies,
              preventCompressedOutput);

        this.contentForFullResponse = contentForFullResponse;
    }

    /**
     * Creates a new blank instance. Identical to calling {@code newBuilder().build()}.
     */
    public FullResponseInfo() {
        this(null, null, null, null, null, null, false);
    }

    /**
     * Since this is a full response, this method always returns false.
     */
    public boolean isChunkedResponse() {
        return false;
    }

    @Override
    public @Nullable T getContentForFullResponse() {
        return contentForFullResponse;
    }

    @Override
    public void setContentForFullResponse(@Nullable T contentForFullResponse) {
        if (isResponseSendingLastChunkSent()) {
            throw new IllegalStateException("isFullResponseSent() is true. You cannot set content for a response that "
                                            + "has already been sent to the user.");
        }

        this.contentForFullResponse = contentForFullResponse;
    }

    /**
     * Base Builder for {@link FullResponseInfo}. Create one of these with {@link #newBuilder()} or {@link
     * #newBuilder(Object)}.
     *
     * @param <T>
     *     The type of the response body content.
     */
    public static final class FullResponseInfoBuilder<T> extends BaseResponseInfoBuilder<T> {

        private @Nullable T contentForFullResponse;

        public FullResponseInfoBuilder() {

        }

        /**
         * Populates this builder with the given content <b>intended for a full response</b>. Can be null if there is no
         * response body content to send.
         */
        public @NotNull FullResponseInfoBuilder<T> withContentForFullResponse(@Nullable T content) {
            this.contentForFullResponse = content;
            return this;
        }

        @Override
        public @NotNull FullResponseInfoBuilder<T> withHttpStatusCode(@Nullable Integer httpStatusCode) {
            super.withHttpStatusCode(httpStatusCode);
            return this;
        }

        @Override
        public @NotNull FullResponseInfoBuilder<T> withHeaders(@Nullable HttpHeaders headers) {
            super.withHeaders(headers);
            return this;
        }

        @Override
        public @NotNull FullResponseInfoBuilder<T> withDesiredContentWriterMimeType(
            @Nullable String desiredContentWriterMimeType
        ) {
            super.withDesiredContentWriterMimeType(desiredContentWriterMimeType);
            return this;
        }

        @Override
        public @NotNull FullResponseInfoBuilder<T> withDesiredContentWriterEncoding(
            @Nullable Charset desiredContentWriterEncoding
        ) {
            super.withDesiredContentWriterEncoding(desiredContentWriterEncoding);
            return this;
        }

        @Override
        public @NotNull FullResponseInfoBuilder<T> withCookies(@Nullable Set<Cookie> cookies) {
            super.withCookies(cookies);
            return this;
        }

        @Override
        public @NotNull FullResponseInfoBuilder<T> withPreventCompressedOutput(boolean preventCompressedOutput) {
            super.withPreventCompressedOutput(preventCompressedOutput);
            return this;
        }

        /**
         * @return A {@link FullResponseInfo} setup with all the values contained in this builder.
         */
        public @NotNull FullResponseInfo<T> build() {
            return new FullResponseInfo<>(contentForFullResponse,
                                          getHttpStatusCode(),
                                          getHeaders(),
                                          getDesiredContentWriterMimeType(),
                                          getDesiredContentWriterEncoding(),
                                          getCookies(),
                                          isPreventCompressedOutput()
            );
        }
    }

}
