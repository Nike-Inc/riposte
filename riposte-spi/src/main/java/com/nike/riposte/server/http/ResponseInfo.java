package com.nike.riposte.server.http;

import com.nike.riposte.server.http.impl.ChunkedResponseInfo.ChunkedResponseInfoBuilder;
import com.nike.riposte.server.http.impl.FullResponseInfo.FullResponseInfoBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.Set;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.util.CharsetUtil;

/**
 * Represents an outgoing response. See the javadocs on the individual methods for details on usage. It's recommended
 * that you use the builders to create instances ({@link #newBuilder()}, {@link #newBuilder(Object)}, and {@link
 * #newChunkedResponseBuilder()}).
 *
 * @author Nic Munroe
 */
public interface ResponseInfo<T> {

    /**
     * The default mime type that should be used by the response sender if {@link #getDesiredContentWriterMimeType()} is
     * null and the mime type is not specified in the headers.
     */
    String DEFAULT_MIME_TYPE = "application/json";
    /**
     * The default content encoding charset that should be used if {@link #getDesiredContentWriterEncoding()} is null
     * and the charset is not specified in the headers.
     */
    Charset DEFAULT_CONTENT_ENCODING = CharsetUtil.UTF_8;

    /**
     * The HTTP status code to return. Can be null - if this is null then the response sender will use a default value
     * (generally 200, but it's up to the response sender).
     * <p/>
     * Although this may be null when the response sender sees it, the response sender should set this to the value of
     * the HTTP status code that was actually returned to the user using {@link #setHttpStatusCode(Integer)} so that
     * observers (e.g. {@link com.nike.riposte.server.logging.AccessLogger} or {@link
     * com.nike.riposte.metrics.MetricsListener}) know what was sent to the user after the fact.
     */
    @Nullable Integer getHttpStatusCode();

    /**
     * @return {@link #getHttpStatusCode()}, or the given default if {@link #getHttpStatusCode()} is null.
     */
    default int getHttpStatusCodeWithDefault(int defaultIfNull) {
        Integer actual = getHttpStatusCode();
        if (actual == null)
            return defaultIfNull;

        return actual;
    }

    /**
     * Sets the value of the HTTP status code that the response sender should use. See {@link #getHttpStatusCode()} for
     * more information on how this is used.
     */
    void setHttpStatusCode(@Nullable Integer httpStatusCode);

    /**
     * The response headers to send. This will never be null (it will default to a blank {@link DefaultHttpHeaders} if
     * necessary).
     */
    @NotNull HttpHeaders getHeaders();

    /**
     * Returns true if this response is a chunked response, false if it is a full ready-to-send-everything response. If
     * this is true then response senders should look at the pipeline messages they're being passed to retrieve the
     * content chunks to send to the user as it won't be stored in this object. Otherwise response senders should use
     * {@link #getContentForFullResponse()}.
     */
    boolean isChunkedResponse();

    /**
     * The response content for a *FULL* (not chunked) response. Can be null - if this is null then the response writer
     * will output a blank response body.
     * <p/>
     * <b>ALWAYS CHECK {@link #isChunkedResponse()} BEFORE CALLING THIS METHOD!</b> If {@link #isChunkedResponse()} is
     * true then this method will throw an {@link IllegalStateException}.
     */
    @Nullable T getContentForFullResponse();

    /**
     * Sets the response content for a *FULL* (not chunked) response. Can be null - if this is null then the response
     * writer will output a blank response body.
     * <p/>
     * NOTE: This method can only be called if {@link #isChunkedResponse()} is false, otherwise an {@link
     * IllegalStateException} will be thrown. Similarly you can only call this method if {@link
     * #isResponseSendingLastChunkSent()} is false otherwise an {@link IllegalStateException} will be thrown.
     */
    void setContentForFullResponse(@Nullable T contentForFullResponse);

    /**
     * The mime type (e.g. application/json, or text/html) to use when sending {@link #getContentForFullResponse()} or
     * chunks (when {@link #isChunkedResponse()} is true). This will be used along with {@link
     * #getDesiredContentWriterEncoding()} to populate the outgoing {@link HttpHeaderNames#CONTENT_TYPE} header if
     * non-null. This may return null - if this returns null then the response sender will use {@link
     * #DEFAULT_MIME_TYPE} when sending the response.
     * <p/>
     * NOTE: This and {@link #getDesiredContentWriterEncoding()} will be used to override any Content-Type header in
     * {@link #getHeaders()} if non-null, so if you want the Content-Type header from {@link #getHeaders()} to be the
     * one that is sent to the user (e.g. if you're doing a reverse proxy/edge router/domain router style endpoint) then
     * make sure this is null.
     * <p/>
     * NOTE: This WILL NOT include a charset in this string. The charset is specified via {@link
     * #getDesiredContentWriterEncoding()}.
     */
    @Nullable String getDesiredContentWriterMimeType();

    /**
     * Sets the desired content writer mime type. See {@link #getDesiredContentWriterMimeType()} for more information on
     * how this is used.
     */
    void setDesiredContentWriterMimeType(@Nullable String desiredContentWriterMimeType);

    /**
     * The charset/encoding that should be used when sending {@link #getContentForFullResponse()} or chunks (when {@link
     * #isChunkedResponse()} is true). This will be used to encode the bytes that are sent and will be used along with
     * {@link #getDesiredContentWriterMimeType()} to populate the outgoing {@link HttpHeaderNames#CONTENT_TYPE} header
     * if non-null. This may return null - if this returns null then the response sender will use {@link
     * #DEFAULT_CONTENT_ENCODING} when sending the response.
     * <p/>
     * NOTE: This and {@link #getDesiredContentWriterMimeType()} will be used to override any Content-Type header in
     * {@link #getHeaders()} if non-null, so if you want the Content-Type header from {@link #getHeaders()} to be the
     * one that is sent to the user (e.g. if you're doing a reverse proxy/edge router/domain router style endpoint) then
     * make sure this is null.
     */
    @Nullable Charset getDesiredContentWriterEncoding();

    /**
     * Sets the desired content writer encoding. See {@link #getDesiredContentWriterEncoding()} for more information on
     * how this is used.
     */
    void setDesiredContentWriterEncoding(@Nullable Charset desiredContentWriterEncoding);

    /**
     * The cookies to send. If this is null or empty then no cookies will be output to the user.
     */
    @Nullable Set<Cookie> getCookies();

    /**
     * Sets the cookies to send. If this is null or empty then no cookies will be output to the user.
     */
    void setCookies(@Nullable Set<Cookie> cookies);

    /**
     * Returns true if the response to the user should be sent unchanged/uncompressed. If this is false then the
     * response sender and/or output handlers can consider this response eligible for compression and are free to
     * compress the response at their discretion.
     * <p/>
     * The builder defaults this to false (allowing normal compression rules to apply) - if you want to force a full
     * sized response then set this to true.
     */
    boolean isPreventCompressedOutput();

    /**
     * Pass in true if the response to the user should be sent unchanged/uncompressed. If this is false then the
     * response sender and/or output handlers can consider this response eligible for compression and are free to
     * compress the response at their discretion.
     * <p/>
     * The builder defaults this to false (allowing normal compression rules to apply) - if you want to force a full
     * sized response then set this to true.
     */
    @SuppressWarnings("unused")
    void setPreventCompressedOutput(boolean preventCompressedOutput);

    /**
     * The *uncompressed* response content length of the content that was sent to the user in bytes - 0 for empty
     * responses. This is not necessarily the length of the content that was actually sent to the user since it may have
     * been modified by outgoing handlers (e.g. compression/gzip), just the length of the raw content before any
     * modification.
     * <p/>
     * NOTE: This is *NOT* used to set a Content-Length header. It is here to indicate to observers (e.g. {@link
     * com.nike.riposte.server.logging.AccessLogger} or {@link com.nike.riposte.metrics.MetricsListener}) the length of
     * the uncompressed content that was actually sent to the user after the fact.
     * <p/>
     * Response senders should set this to the correct value after the response has been sent to the user, but otherwise
     * the response sender will ignore this value (again, it will not be used to affect anything sent to the user).
     * <p/>
     * This is guaranteed to be null until after the response sender sends the response to the user. It will not be an
     * accurate number until after the last chunk is sent (use {@link #isResponseSendingLastChunkSent()} to verify when
     * it is done).
     */
    @Nullable Long getUncompressedRawContentLength();

    /**
     * Sets the uncompressed raw content length. See {@link #getUncompressedRawContentLength()} for details on how this
     * is used.
     */
    void setUncompressedRawContentLength(@Nullable Long uncompressedRawContentLength);

    /**
     * The *final* response content length of the content that was sent to the user in bytes - 0 for empty responses.
     * This is the length of the content that was actually sent to the user after being modified by any outgoing
     * handlers (e.g. compression/gzip).
     * <p/>
     * NOTE: This is *NOT* used to set a Content-Length header. It is here to indicate to observers (e.g. {@link
     * com.nike.riposte.server.logging.AccessLogger} or {@link com.nike.riposte.metrics.MetricsListener}) the length of
     * the final (potentially compressed) content that was actually sent to the user after the fact.
     * <p/>
     * An outgoing channel handler should monitor the content being sent out right before {@link
     * io.netty.handler.codec.http.HttpResponseEncoder} converts the entire response (including headers/etc) into the
     * outgoing bytes, and add to or set this value appropriately (you may need to add to this value cumulatively
     * depending on if there are multiple chunks, etc).
     */
    @Nullable Long getFinalContentLength();

    /**
     * Sets the final content length. See {@link #getFinalContentLength()} for details on how this is used.
     */
    void setFinalContentLength(@Nullable Long finalContentLength);

    /**
     * Returns true if at least one chunk of the response (the headers chunk) has been sent to the user, false if
     * nothing has been sent to the user yet. This is not a chunked-response only field - it is valid and usable no
     * matter what {@link #isChunkedResponse()} returns.
     */
    boolean isResponseSendingStarted();

    /**
     * The response sender should call this and pass in true when the first header chunk is sent to the user. This is
     * not a chunked-response only field - it is valid and usable no matter what {@link #isChunkedResponse()} returns.
     */
    void setResponseSendingStarted(boolean responseSendingStarted);

    /**
     * Returns true if the last chunk of the response has been sent to the user and the response sending is therefore
     * complete, false if the response is not fully sent yet. This is not a chunked-response only field - it is valid
     * and usable no matter what {@link #isChunkedResponse()} returns.
     */
    boolean isResponseSendingLastChunkSent();

    /**
     * The response sender should call this and pass in true when the last chunk is sent to the user. This is not a
     * chunked-response only field - it is valid and usable no matter what {@link #isChunkedResponse()} returns.
     */
    void setResponseSendingLastChunkSent(boolean responseSendingLastChunkSent);

    /**
     * Returns true <b>if and only if</b> this is a "final response" before this connection closes. This should only
     * ever be set to true in cases where the server detects a bad state or otherwise knows that the connection should
     * not be allowed to stay open long term but should still be allowed to send one final response, e.g. when a {@link
     * com.nike.riposte.server.error.exception.TooManyOpenChannelsException} is thrown. This should default to false in
     * normal implementations.
     */
    boolean isForceConnectionCloseAfterResponseSent();

    /**
     * Sets the {@link #isForceConnectionCloseAfterResponseSent()} field - see that method for details on how this is
     * used. This should default to false in normal implementations.
     */
    void setForceConnectionCloseAfterResponseSent(boolean forceConnectionCloseAfterResponseSent);

    /**
     * @return A new blank builder for full responses (not chunked responses). If you know the content you want to
     * return you should probably use {@link #newBuilder(Object)} so that you don't have to manually specify the {@code
     * T} generic type. For example, if you have a string to return as the content type and you use this method you
     * would have to do something like: {@code ResponseInfo<String> responseInfo = ResponseInfo.<String>newBuilder().withContentForFullResponse("someString").build()},
     * but if you use the other method it would look like: {@code ResponseInfo<String> responseInfo =
     * ResponseInfo.newBuilder("someString").build()}.
     */
    static <T> @NotNull FullResponseInfoBuilder<T> newBuilder() {
        return new FullResponseInfoBuilder<>();
    }

    /**
     * @return A new builder for full responses (not chunked responses) with the given content already populated.
     */
    static <T> @NotNull FullResponseInfoBuilder<T> newBuilder(@Nullable T content) {
        return new FullResponseInfoBuilder<T>().withContentForFullResponse(content);
    }

    /**
     * @return A new blank builder for chunked responses (not full responses). You would only ever use this when you're
     * doing a streaming or proxy style endpoint. Most of the time you'll want one of the full response builders ({@link
     * #newBuilder()} or {@link #newBuilder(Object)}). If you're unsure which one you should be using, chances are it
     * should *not* be this one.
     */
    static @NotNull ChunkedResponseInfoBuilder newChunkedResponseBuilder() {
        return new ChunkedResponseInfoBuilder();
    }

}
