package com.nike.riposte.server.http.impl;

import com.nike.riposte.server.error.exception.RequestContentDeserializationException;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.util.HttpUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    protected final String uri;
    protected final String path;
    protected final HttpMethod method;
    protected final HttpHeaders headers;
    protected HttpHeaders trailingHeaders;
    protected final QueryStringDecoder queryParams;
    protected final Set<Cookie> cookies;
    protected String pathTemplate;
    protected Map<String, String> pathParams;
    protected final Map<String, Object> attributes = new HashMap<>();
    protected int rawContentLengthInBytes;
    protected byte[] rawContentBytes;
    protected String rawContent;
    protected T content;
    protected final Charset contentCharset;
    protected final HttpVersion protocolVersion;
    protected final boolean keepAliveRequested;
    protected final List<HttpContent> contentChunks = new ArrayList<>();
    protected boolean isCompleteRequestWithAllChunks;
    protected final boolean isMultipart;
    protected boolean multipartDataIsDestroyed = false;
    protected HttpPostMultipartRequestDecoder multipartData;

    protected ObjectMapper contentDeserializer;
    protected TypeReference<T> contentDeserializerTypeReference;

    protected boolean contentChunksWillBeReleasedExternally = false;

    public RequestInfoImpl(String uri, HttpMethod method, HttpHeaders headers, HttpHeaders trailingHeaders,
                           QueryStringDecoder queryParams,
                           Set<Cookie> cookies, Map<String, String> pathParams, List<HttpContent> contentChunks,
                           HttpVersion protocolVersion,
                           boolean keepAliveRequested, boolean isCompleteRequestWithAllChunks, boolean isMultipart) {

        if (uri == null)
            uri = "";

        if (headers == null)
            headers = new DefaultHttpHeaders();

        if (trailingHeaders == null)
            trailingHeaders = new DefaultHttpHeaders();

        if (queryParams == null)
            queryParams = new QueryStringDecoder(uri);

        if (cookies == null)
            cookies = new HashSet<>();

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

    public RequestInfoImpl(HttpRequest request) {
        this(request.getUri(), request.getMethod(), request.headers(),
             HttpUtils.extractTrailingHeadersIfPossible(request), null, HttpUtils.extractCookies(request), null,
             HttpUtils.extractContentChunks(request), request.getProtocolVersion(), HttpHeaders.isKeepAlive(request),
             (request instanceof FullHttpRequest),
             HttpPostRequestDecoder.isMultipart(request));
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
    public String getUri() {
        return uri;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPath() {
        return path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HttpMethod getMethod() {
        return method;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueryStringDecoder getQueryParams() {
        return queryParams;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Cookie> getCookies() {
        return cookies;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Charset getContentCharset() {
        return contentCharset;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HttpVersion getProtocolVersion() {
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
    public synchronized byte[] getRawContentBytes() {
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
    public synchronized String getRawContent() {
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
    public synchronized T getContent() {
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
    public synchronized List<InterfaceHttpData> getMultipartParts() {
        if (!isMultipartRequest() || !isCompleteRequestWithAllChunks())
            return null;

        if (multipartData == null) {
            byte[] contentBytes = getRawContentBytes();
            HttpRequest fullHttpRequestForMultipartDecoder =
                (contentBytes == null)
                ? new DefaultFullHttpRequest(getProtocolVersion(), getMethod(), getUri())
                : new DefaultFullHttpRequest(getProtocolVersion(), getMethod(), getUri(),
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
        if (!isContentDeserializerSetup())
            return null;

        try {
            Type inputType = contentDeserializerTypeReference.getType();
            if (inputType instanceof Class) {
                Class inputTypeClass = (Class) inputType;
                // If they want a String or CharSequence then return the getRawContent() string.
                if (String.class.equals(inputTypeClass) || CharSequence.class.equals(inputTypeClass)) {
                    //noinspection unchecked
                    return (T) getRawContent();
                }
            }

            // Not a String or CharSequence. Do our best to deserialize.
            byte[] bytes = getRawContentBytes();
            return (bytes == null) ? null : contentDeserializer.readValue(bytes, contentDeserializerTypeReference);
        }
        catch (Throwable e) {
            // Something went wrong during deserialization. Throw an appropriate error.
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
    public RequestInfo<T> setupContentDeserializer(ObjectMapper deserializer, TypeReference<T> typeReference) {
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
    public HttpHeaders getTrailingHeaders() {
        return trailingHeaders;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RequestInfo<T> setPathParamsBasedOnPathTemplate(String pathTemplate) {
        this.pathTemplate = pathTemplate;
        setPathParams(HttpUtils.decodePathParams(pathTemplate, getPath()));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> getPathParams() {
        return pathParams;
    }

    protected void setPathParams(Map<String, String> pathParams) {
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
        // If we had somehow already pulled in some content chunks then can remove them from the contentChunks list,
        //      however as per the javadocs for this method we should *not* release() them.
        if (contentChunks.size() > 0) {
            contentChunks.clear();
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public int addContentChunk(HttpContent chunk) {
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
    public void addRequestAttribute(String attributeName, Object attributeValue) {
        attributes.put(attributeName, attributeValue);
    }

    /**
     * {@inheritDoc}
     */
    public Map<String, Object> getRequestAttributes() {
        return attributes;
    }

}
