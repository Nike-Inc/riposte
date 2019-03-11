package com.nike.riposte.server.http.impl;

import com.nike.riposte.server.http.ResponseInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.Set;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;

/**
 * Base Builder for {@link ResponseInfo}. This builder doesn't actually build anything - concrete builders should extend
 * this class with a {@code build()} method (and anything else they need). This is here to prevent a bunch of copy-paste
 * on the common fields.
 *
 * @param <T>
 *     The type of the response body content.
 */
@SuppressWarnings("WeakerAccess")
public abstract class BaseResponseInfoBuilder<T> {

    private @Nullable Integer httpStatusCode;
    private @Nullable HttpHeaders headers;
    private @Nullable String desiredContentWriterMimeType;
    private @Nullable Charset desiredContentWriterEncoding;
    private @Nullable Set<Cookie> cookies;
    private boolean preventCompressedOutput = false;

    protected BaseResponseInfoBuilder() {
    }

    /**
     * Populates this builder with the given HTTP status code. Can be null - if this is null then the response sender
     * will use a default value (generally 200, but it's up to the response sender).
     */
    public @NotNull BaseResponseInfoBuilder<T> withHttpStatusCode(@Nullable Integer httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
        return this;
    }

    // TODO: Support individual withHeader(String, String) type method.

    /**
     * Populates this builder with the given headers. Can be null - if this is null then the a default blank {@link
     * DefaultHttpHeaders} will be used.
     */
    public @NotNull BaseResponseInfoBuilder<T> withHeaders(@Nullable HttpHeaders headers) {
        this.headers = headers;
        return this;
    }

    /**
     * Populates this builder with the mime type (e.g. application/json, or text/html) to use when sending {@link
     * ResponseInfo#getContentForFullResponse()} or any content chunks. This will be used along with {@link
     * #withDesiredContentWriterEncoding(Charset)} to populate the outgoing {@link HttpHeaderNames#CONTENT_TYPE}
     * header if non-null.
     * <p/>
     * NOTE: This and {@link #withDesiredContentWriterEncoding(Charset)} will be used to override any Content-Type
     * header in {@link #getHeaders()} if non-null, so if you want the Content-Type header from {@link #getHeaders()} to
     * be the one that is sent to the user (e.g. if you're doing a reverse proxy/edge router/domain router style
     * endpoint) then make sure this is null.
     * <p/>
     * NOTE: This MUST NOT include a charset in this string. The charset is specified via {@link
     * #desiredContentWriterEncoding}.
     */
    public @NotNull BaseResponseInfoBuilder<T> withDesiredContentWriterMimeType(
        @Nullable String desiredContentWriterMimeType
    ) {
        this.desiredContentWriterMimeType = desiredContentWriterMimeType;
        return this;
    }

    /**
     * Populates this builder with the charset/encoding that should be used when sending {@link
     * ResponseInfo#getContentForFullResponse()} or any content chunks. This will be used to encode the bytes that are
     * sent and will be used along with {@link #withDesiredContentWriterMimeType(String)} to populate the outgoing
     * {@link HttpHeaderNames#CONTENT_TYPE} header if non-null.
     * <p/>
     * NOTE: This and {@link #withDesiredContentWriterMimeType(String)} will be used to override any Content-Type header
     * in {@link #getHeaders()} if non-null, so if you want the Content-Type header from {@link #getHeaders()} to be the
     * one that is sent to the user (e.g. if you're doing a reverse proxy/edge router/domain router style endpoint) then
     * make sure this is null.
     */
    public @NotNull BaseResponseInfoBuilder<T> withDesiredContentWriterEncoding(
        @Nullable Charset desiredContentWriterEncoding
    ) {
        this.desiredContentWriterEncoding = desiredContentWriterEncoding;
        return this;
    }

    /**
     * Populates this builder with the given cookies. Can be null - if this is null then no cookies will be sent to the
     * user.
     */
    public @NotNull BaseResponseInfoBuilder<T> withCookies(@Nullable Set<Cookie> cookies) {
        this.cookies = cookies;
        return this;
    }

    /**
     * Populates the {@link #preventCompressedOutput} value for this builder. If true then this response will *not* be
     * compressed (e.g. via gzip) when sent to the user, even if it would otherwise qualify for compression. If false
     * then the normal rules for compression will apply and this response may or may not be compressed depending on
     * whether it meets compression criteria. This defaults to false (allowing normal compression rules to apply) - if
     * you want to force a full sized response then set this to true.
     */
    public @NotNull BaseResponseInfoBuilder<T> withPreventCompressedOutput(boolean preventCompressedOutput) {
        this.preventCompressedOutput = preventCompressedOutput;
        return this;
    }

    protected @Nullable Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    protected @Nullable HttpHeaders getHeaders() {
        return headers;
    }

    protected @Nullable String getDesiredContentWriterMimeType() {
        return desiredContentWriterMimeType;
    }

    protected @Nullable Charset getDesiredContentWriterEncoding() {
        return desiredContentWriterEncoding;
    }

    protected @Nullable Set<Cookie> getCookies() {
        return cookies;
    }

    protected boolean isPreventCompressedOutput() {
        return preventCompressedOutput;
    }
}
