package com.nike.riposte.server.http.impl;

import com.nike.riposte.server.error.exception.RequestContentDeserializationException;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.util.HttpUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.ReferenceCounted;

/**
 * Default implementation of {@link RequestInfo}
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class RequestInfoImpl<T> implements RequestInfo<T>, RiposteInternalRequestInfo {

    private static final Logger logger = LoggerFactory.getLogger(RequestInfoImpl.class);

    protected final @NotNull String uri;
    protected final @NotNull String path;
    protected final @Nullable HttpMethod method;
    protected final @NotNull HttpHeaders headers;
    protected @NotNull HttpHeaders trailingHeaders;
    protected @NotNull final QueryStringDecoder queryParams;
    protected final @NotNull Set<Cookie> cookies;
    protected @Nullable String pathTemplate;
    protected @NotNull Map<String, String> pathParams = Collections.emptyMap();
    protected final @NotNull Map<String, Object> attributes = new HashMap<>();
    protected int rawContentLengthInBytes;
    protected @Nullable byte[] rawContentBytes;
    protected @Nullable String rawContent;
    protected @Nullable T content;
    protected final @NotNull Charset contentCharset;
    protected final @Nullable HttpVersion protocolVersion;
    protected final boolean keepAliveRequested;
    protected final @NotNull List<HttpContent> contentChunks = new ArrayList<>();
    protected boolean isCompleteRequestWithAllChunks;
    protected final boolean isMultipart;
    protected boolean multipartDataIsDestroyed = false;
    protected @Nullable HttpPostMultipartRequestDecoder multipartData;

    protected @Nullable ObjectMapper contentDeserializer;
    protected @Nullable TypeReference<T> contentDeserializerTypeReference;

    protected boolean contentChunksWillBeReleasedExternally = false;

    public RequestInfoImpl(
        @Nullable String uri,
        @Nullable HttpMethod method,
        @Nullable HttpHeaders headers,
        @Nullable HttpHeaders trailingHeaders,
        @Nullable QueryStringDecoder queryParams,
        @Nullable Set<Cookie> cookies,
        @Nullable Map<String, String> pathParams,
        @Nullable List<@NotNull HttpContent> contentChunks,
        @Nullable HttpVersion protocolVersion,
        boolean keepAliveRequested,
        boolean isCompleteRequestWithAllChunks,
        boolean isMultipart
    ) {
        if (uri == null) {
            uri = "";
        }

        if (headers == null) {
            headers = new DefaultHttpHeaders();
        }

        if (trailingHeaders == null) {
            trailingHeaders = new DefaultHttpHeaders();
        }

        if (queryParams == null) {
            queryParams = new QueryStringDecoder(uri);
        }

        if (cookies == null) {
            cookies = new HashSet<>();
        }

        this.uri = uri;
        this.path = QueryStringDecoder.decodeComponent(HttpUtils.extractPath(uri));
        this.method = method;
        this.headers = headers;
        this.trailingHeaders = trailingHeaders;
        this.queryParams = queryParams;
        this.cookies = cookies;
        setPathParams(pathParams);
        this.contentCharset = HttpUtils.determineCharsetFromContentType(headers, DEFAULT_CONTENT_CHARSET);
        if (contentChunks != null) {
            contentChunks.forEach(this::addContentChunk);
        }
        this.protocolVersion = protocolVersion;
        this.keepAliveRequested = keepAliveRequested;
        this.isCompleteRequestWithAllChunks = isCompleteRequestWithAllChunks;
        this.isMultipart = isMultipart;
    }

    public RequestInfoImpl(@NotNull HttpRequest request) {
        this(
            request.uri(),
            request.method(),
            request.headers(),
            HttpUtils.extractTrailingHeadersIfPossible(request),
            null,
            HttpUtils.extractCookies(request),
            null,
            HttpUtils.extractContentChunks(request),
            request.protocolVersion(),
            HttpUtil.isKeepAlive(request),
            (request instanceof FullHttpRequest),
            HttpPostRequestDecoder.isMultipart(request)
        );
    }

    /**
     * Creates a new RequestInfo that represents unknown requests. Usually only needed in error situations. The URI,
     * query params, and headers will be tagged with {@link #NONE_OR_UNKNOWN_TAG} to indicate that the request was
     * unknown.
     */
    public static RequestInfoImpl<?> dummyInstanceForUnknownRequests() {
        HttpHeaders headers = new DefaultHttpHeaders().set(NONE_OR_UNKNOWN_TAG, "true");
        QueryStringDecoder queryParams = new QueryStringDecoder("/?" + NONE_OR_UNKNOWN_TAG + "=true");

        return new RequestInfoImpl(NONE_OR_UNKNOWN_TAG, null, headers, null, queryParams, null, null, null, null, false,
                                   true, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull String getUri() {
        return uri;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull String getPath() {
        return path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable HttpMethod getMethod() {
        return method;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull HttpHeaders getHeaders() {
        return headers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull QueryStringDecoder getQueryParams() {
        return queryParams;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull Set<Cookie> getCookies() {
        return cookies;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull Charset getContentCharset() {
        return contentCharset;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable HttpVersion getProtocolVersion() {
        return protocolVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isKeepAliveRequested() {
        return keepAliveRequested;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized int getRawContentLengthInBytes() {
        if (!isCompleteRequestWithAllChunks)
            return 0;

        return rawContentLengthInBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized @Nullable byte[] getRawContentBytes() {
        if (!isCompleteRequestWithAllChunks)
            return null;

        if (!contentChunks.isEmpty()) {
            rawContentBytes = HttpUtils.convertContentChunksToRawBytes(contentChunks);
            releaseContentChunks();
        }

        return rawContentBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized @Nullable String getRawContent() {
        if (!isCompleteRequestWithAllChunks)
            return null;

        if (rawContent != null)
            return rawContent;

        // The raw content string has not been loaded/cached yet. Do that now.
        rawContent = HttpUtils.convertRawBytesToString(getContentCharset(), getRawContentBytes());

        return rawContent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized @Nullable T getContent() {
        if (!isCompleteRequestWithAllChunks)
            return null;

        if (content == null)
            content = deserializeContent();

        return content;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMultipartRequest() {
        return isMultipart;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized @Nullable List<InterfaceHttpData> getMultipartParts() {
        if (!isMultipartRequest() || !isCompleteRequestWithAllChunks())
            return null;

        if (multipartData == null) {
            byte[] contentBytes = getRawContentBytes();
            HttpVersion httpVersion = getProtocolVersion();
            HttpMethod httpMethod = getMethod();
            // HttpVersion and HttpMethod cannot be null because DefaultFullHttpRequest doesn't allow them to be
            //      null, but our getProtocolVersion() and getMethod() methods might return null (i.e. due to an
            //      invalid request). They shouldn't be null in practice by the time this getMultipartParts() method
            //      is called, but since they don't seem to be used by the Netty code we delegate to, we can just
            //      default them to something if null somehow slips through.
            if (httpVersion == null) {
                httpVersion = HttpVersion.HTTP_1_0;
            }

            if (httpMethod == null) {
                httpMethod = HttpMethod.POST;
            }

            HttpRequest fullHttpRequestForMultipartDecoder =
                (contentBytes == null)
                ? new DefaultFullHttpRequest(httpVersion, httpMethod, getUri())
                : new DefaultFullHttpRequest(httpVersion, httpMethod, getUri(),
                                             Unpooled.wrappedBuffer(contentBytes));

            fullHttpRequestForMultipartDecoder.headers().add(getHeaders());

            multipartData = new HttpPostMultipartRequestDecoder(
                new DefaultHttpDataFactory(false), fullHttpRequestForMultipartDecoder, getContentCharset()
            );
        }

        return multipartData.getBodyHttpDatas();
    }

    protected T deserializeContent() {
        // TODO: We could conceivably have a case where contentDeserializerTypeReference is a string/charsequence,
        //       but contentDeserializer is null. In that case we should not return null, because getRawContent() is a
        //       valid return value. Can probably fix this by making isContentDeserializerSetup() smarter.
        if (!isContentDeserializerSetup()) {
            return null;
        }

        try {
            @SuppressWarnings("ConstantConditions") // isContentDeserializerSetup() verifies contentDeserializerTypeReference is non-null.
            Type inputType = contentDeserializerTypeReference.getType();
            if (inputType instanceof Class) {
                Class inputTypeClass = (Class) inputType;
                // If they want a raw byte[] then return getRawContentBytes().
                if (byte[].class.equals(inputTypeClass)) {
                    return (T) getRawContentBytes();
                }

                // If they want a String or CharSequence then return the getRawContent() string.
                if (String.class.equals(inputTypeClass) || CharSequence.class.equals(inputTypeClass)) {
                    //noinspection unchecked
                    return (T) getRawContent();
                }
            }

            // Not a String or CharSequence. Do our best to deserialize.
            byte[] bytes = getRawContentBytes();
            //noinspection ConstantConditions - isContentDeserializerSetup() verifies contentDeserializer is non-null.
            return (bytes == null) ? null : contentDeserializer.readValue(bytes, contentDeserializerTypeReference);
        }
        catch (Throwable e) {
            // Something went wrong during deserialization. Throw an appropriate error.
            //noinspection ConstantConditions - isContentDeserializerSetup() verifies contentDeserializerTypeReference is non-null.
            logger.info("Unable to deserialize request content to desired object type - {}: {}",
                        contentDeserializerTypeReference.getType().toString(), e.getMessage());
            throw new RequestContentDeserializationException(
                "Unable to deserialize request content to desired object type.", e, this,
                contentDeserializerTypeReference
            );
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull RequestInfo<T> setupContentDeserializer(
        @NotNull ObjectMapper deserializer,
        @NotNull TypeReference<T> typeReference
    ) {
        this.contentDeserializer = deserializer;
        this.contentDeserializerTypeReference = typeReference;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isContentDeserializerSetup() {
        return contentDeserializer != null && contentDeserializerTypeReference != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull HttpHeaders getTrailingHeaders() {
        return trailingHeaders;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull RequestInfo<T> setPathParamsBasedOnPathTemplate(@NotNull String pathTemplate) {
        this.pathTemplate = pathTemplate;
        setPathParams(HttpUtils.decodePathParams(pathTemplate, getPath()));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull Map<String, String> getPathParams() {
        return pathParams;
    }

    protected void setPathParams(@Nullable Map<String, String> pathParams) {
        if (pathParams == null)
            pathParams = Collections.emptyMap();

        this.pathParams = pathParams;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void contentChunksWillBeReleasedExternally() {
        this.contentChunksWillBeReleasedExternally = true;
        // If we had somehow already pulled in some content chunks then we can remove them from the contentChunks list,
        //      however as per the javadocs for this method we should *not* release() them.
        if (contentChunks.size() > 0) {
            contentChunks.clear();
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public int addContentChunk(@NotNull HttpContent chunk) {
        if (isCompleteRequestWithAllChunks) {
            throw new IllegalStateException("Cannot add new content chunk - this RequestInfo is already marked as "
                                            + "representing the complete request with all chunks");
        }

        chunk.retain();
        rawContentLengthInBytes += chunk.content().readableBytes();

        // If content chunks will be released externally then there's no point in us holding on to them
        if (!contentChunksWillBeReleasedExternally)
            contentChunks.add(chunk);

        if (chunk instanceof LastHttpContent) {
            // If content chunks will be released externally then we can't guarantee that the data will be available
            //      at any given time (earlier chunks may have already been released before the last chunk arrives,
            //      e.g. in the case of ProxyRouter endpoints), so we'll never allow isCompleteRequestWithAllChunks
            //      to be set to true if content chunks are released externally.
            if (!contentChunksWillBeReleasedExternally)
                isCompleteRequestWithAllChunks = true;

            HttpHeaders chunkTrailingHeaders = ((LastHttpContent) chunk).trailingHeaders();
            //noinspection StatementWithEmptyBody
            if (trailingHeaders == chunkTrailingHeaders) {
                // Can happen during the constructor. We've already set the trailing headers to the chunk's trailing headers, so nothing to do here.
            }
            else {
                if (!trailingHeaders.isEmpty()) {
                    throw new IllegalStateException("Received the final chunk, but trailingHeaders was already "
                                                    + "populated. This should not be possible.");
                }

                trailingHeaders.add(chunkTrailingHeaders);
            }
        }

        return rawContentLengthInBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCompleteRequestWithAllChunks() {
        return isCompleteRequestWithAllChunks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseAllResources() {
        releaseContentChunks();
        releaseMultipartData();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseContentChunks() {
        if (!contentChunksWillBeReleasedExternally) {
            contentChunks.forEach(ReferenceCounted::release);
        }
        // Now that the chunks have been released we should clear the chunk list - we can no longer rely on the chunks
        //      for anything, and if this method is called a second time we don't want to re-release the chunks
        //      (which would screw up the reference counting).
        contentChunks.clear();
    }

    /**
     * {@inheritDoc}
     */
    public void releaseMultipartData() {
        if (multipartDataIsDestroyed)
            return;

        if (multipartData != null) {
            multipartData.destroy();
            multipartDataIsDestroyed = true;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void addRequestAttribute(@NotNull String attributeName, @NotNull Object attributeValue) {
        attributes.put(attributeName, attributeValue);
    }

    /**
     * {@inheritDoc}
     */
    public @NotNull Map<String, Object> getRequestAttributes() {
        return attributes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull String getPathTemplate() {
        return this.pathTemplate == null ? "" : this.pathTemplate;
    }

}
