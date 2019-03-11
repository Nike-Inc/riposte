package com.nike.riposte.server.http.impl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.Set;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;

/**
 * An extension of {@link BaseResponseInfo} that represents chunked (not full) responses. {@link #isChunkedResponse()}
 * will return true, and exceptions will be thrown if you try to treat this class as a full response. You should use the
 * {@link ChunkedResponseInfoBuilder} to create new instances.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class ChunkedResponseInfo extends BaseResponseInfo<Void> {

    /**
     * The "populate everything" constructor. It's recommended that you use the {@link ChunkedResponseInfoBuilder}
     * instead.
     */
    public ChunkedResponseInfo(
        @Nullable Integer httpStatusCode,
        @Nullable HttpHeaders headers,
        @Nullable String desiredContentWriterMimeType,
        @Nullable Charset desiredContentWriterEncoding,
        @Nullable Set<Cookie> cookies,
        boolean preventCompressedOutput
    ) {
        super(httpStatusCode, headers, desiredContentWriterMimeType, desiredContentWriterEncoding, cookies,
              preventCompressedOutput);
    }

    /**
     * Creates a new blank instance. Identical to calling {@code newBuilder().build()}.
     */
    public ChunkedResponseInfo() {
        this(null, null, null, null, null, false);
    }

    /**
     * Since this is a chunked response, this method always returns true.
     */
    public boolean isChunkedResponse() {
        return true;
    }

    @Override
    public @Nullable Void getContentForFullResponse() {
        throw new IllegalStateException(
            "Attempted to call getContentForFullResponse() when isChunkedResponse() is true. Always verify that "
            + "isChunkedResponse() returns false before calling this method."
        );
    }

    @Override
    public void setContentForFullResponse(@Nullable Void contentForFullResponse) {
        throw new IllegalStateException("isChunkedResponse() is true. You cannot add full response content to a "
                                        + "chunked response.");
    }

    /**
     * Base Builder for {@link ChunkedResponseInfo}. Create one of these with {@link #newBuilder()} or {@link
     * #newBuilder(Object)}.
     */
    public static final class ChunkedResponseInfoBuilder extends BaseResponseInfoBuilder<Void> {

        public ChunkedResponseInfoBuilder() {
        }

        @Override
        public @NotNull ChunkedResponseInfoBuilder withHttpStatusCode(@Nullable Integer httpStatusCode) {
            super.withHttpStatusCode(httpStatusCode);
            return this;
        }

        @Override
        public @NotNull ChunkedResponseInfoBuilder withHeaders(@Nullable HttpHeaders headers) {
            super.withHeaders(headers);
            return this;
        }

        @Override
        public @NotNull ChunkedResponseInfoBuilder withDesiredContentWriterMimeType(
            @Nullable String desiredContentWriterMimeType
        ) {
            super.withDesiredContentWriterMimeType(desiredContentWriterMimeType);
            return this;
        }

        @Override
        public @NotNull ChunkedResponseInfoBuilder withDesiredContentWriterEncoding(
            @Nullable Charset desiredContentWriterEncoding
        ) {
            super.withDesiredContentWriterEncoding(desiredContentWriterEncoding);
            return this;
        }

        @Override
        public @NotNull ChunkedResponseInfoBuilder withCookies(@Nullable Set<Cookie> cookies) {
            super.withCookies(cookies);
            return this;
        }

        @Override
        public @NotNull ChunkedResponseInfoBuilder withPreventCompressedOutput(boolean preventCompressedOutput) {
            super.withPreventCompressedOutput(preventCompressedOutput);
            return this;
        }

        /**
         * @return A {@link ChunkedResponseInfo} setup with all the values contained in this builder.
         */
        public @NotNull ChunkedResponseInfo build() {
            return new ChunkedResponseInfo(
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
