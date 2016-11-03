package com.nike.riposte.server.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/**
 * Represents an incoming request. Everything is conceptually immutable except the bits related to content chunks and
 * the bits that require knowing which endpoint the request is routed to - once you know the endpoint then you can call
 * {@link #setPathParamsBasedOnPathTemplate(String)} and {@link #setupContentDeserializer(ObjectMapper, TypeReference)}
 * to finish populating an instance of this class before passing it on to the endpoint for execution. As new content
 * chunks are received you can call {@link #addContentChunk(HttpContent)} to add them. Once the final content chunk has
 * been received then {@link #isCompleteRequestWithAllChunks} will be set to true, {@link #getRawContentBytes()} will be
 * populated (and therefore {@link #getRawContent()} will also be available), and {@link #getTrailingHeaders()} will be
 * populated.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("UnnecessaryInterfaceModifier")
public interface RequestInfo<T> {

    public static final String NONE_OR_UNKNOWN_TAG = "none_or_unknown";
    public static final Charset DEFAULT_CONTENT_CHARSET = CharsetUtil.UTF_8;

    /**
     * The full URI associated with this request. May include the query string (use {@link #getPath()} if you don't want
     * the query string). Will never be null - the empty string will be used if no URI information was provided.
     */
    public String getUri();

    /**
     * The path-only portion of the URI. Will *not* include the query string (use {@link #getUri()} if you want the
     * query string). Will never be null - the empty string will be used if no path information could be extracted.
     */
    public String getPath();

    /**
     * The method associated with this request, or null if no method was provided.
     */
    public HttpMethod getMethod();

    /**
     * The headers associated with this request. There may or may not be {@link #getTrailingHeaders()} associated with
     * this request as well. Will never be null - an empty {@link DefaultHttpHeaders} will be used if no headers were
     * provided.
     */
    public HttpHeaders getHeaders();

    /**
     * The trailing headers associated with this request. This will be empty if {@link #isCompleteRequestWithAllChunks}
     * is false - i.e. we can't populate this field until all request chunks have arrived. Will never be null - an empty
     * {@link DefaultHttpHeaders} will be used if no trailing headers were provided. You can call {@link
     * #isCompleteRequestWithAllChunks()} to determine whether this field is empty because we're waiting on all the
     * content to finish arriving or because the request has no trailing headers associated with it.
     */
    public HttpHeaders getTrailingHeaders();

    /**
     * The {@link QueryStringDecoder} containing the query parameters associated with this request. Will never be null -
     * if no {@link QueryStringDecoder} was provided then one will be created based on {@link #getUri()}.
     */
    public QueryStringDecoder getQueryParams();

    /**
     * Helper method for extracting the single query parameter value for the given key from {@link #getQueryParams()},
     * or null if no such query parameter is available. If the value for the given key is a multi-value list, then only
     * the first item in the multi-value list will be returned.
     */
    public default String getQueryParamSingle(String key) {
        if (getQueryParams() == null || getQueryParams().parameters() == null)
            return null;

        List<String> paramList = getQueryParams().parameters().get(key);
        if (paramList == null || paramList.size() == 0)
            return null;

        return paramList.get(0);
    }

    /**
     * @return The path parameters associated with this request. This is a key/value map where the key is the name of
     * the path parameter minus the surrounding {} curly brackets, and the value is the string extracted from the {@link
     * #getPath()} in the place where the key is located in the template. <b>This will not be populated until you call
     * {@link #setPathParamsBasedOnPathTemplate(String)} and provide the path template</b> (note that this should be
     * done for you by the time this request info is passed to an endpoint for execution).
     * <p/>
     * For example, if the path template was "/app/{appId}/user/{userId}" and the actual {@link #getPath()} requested
     * was "/app/foo/user/bar", then this map would consist of the mappings "appId"->"foo", "userId"->"bar".
     * <p/>
     * Will never be null - an empty map will be used if no path parameters could be determined.
     */
    public Map<String, String> getPathParams();

    /**
     * Helper method that is a shortcut for calling {@code getPathParams().get(key)}. Will return null if there's no
     * path parameter mapping for the given key.
     */
    public default String getPathParam(String key) {
        return getPathParams().get(key);
    }

    /**
     * Uses the passed-in path template along with the actual {@link #getPath()} for this request to determine the path
     * parameters associated with this request. After executing this method you can call {@link #getPathParams()} to
     * retrieve the path parameters for this request. The path template should match {@link #getPath()}, but with the
     * dynamic path parameter portions surrounded by curly brackets {} and filled with whatever parameter key names you
     * want.
     * <p/>
     * For example, if the passed-in path template was "/app/{appId}/user/{userId}" and the actual {@link #getPath()}
     * for this request was "/app/foo/user/bar", then this method would populate the path parameter map with the
     * mappings "appId"->"foo", "userId"->"bar", which you could retrieve by calling {@link #getPathParams()}.
     */
    public RequestInfo<T> setPathParamsBasedOnPathTemplate(String pathTemplate);

    /**
     * Returns the total size of the raw content in bytes. This will be 0 until {@link #addContentChunk(HttpContent)}
     * detects that the final content chunk has been added, at which point this method will return the number of bytes
     * for the request content. You can call {@link #isCompleteRequestWithAllChunks()} to determine whether this method
     * is returning 0 because we're waiting on all the content to finish arriving or because the request has no content
     * associated with it.
     */
    public int getRawContentLengthInBytes();

    /**
     * Returns the raw content associated with this request as a byte array. This will be null until {@link
     * #addContentChunk(HttpContent)} detects that the final content chunk has been added, at which point this method
     * will return the content as a byte array. You can call {@link #isCompleteRequestWithAllChunks()} to determine
     * whether this method is returning null because we're waiting on all the content to finish arriving or because the
     * request has no content associated with it.
     * <p/>
     * NOTE: As per the javadocs for {@link #addContentChunk(HttpContent)}, this method lazy-loads the content into a
     * byte array only after {@link #isCompleteRequestWithAllChunks()} returns true and this method is called. This
     * conversion process should only happen once, and when it is done this method should call {@link
     * #releaseContentChunks()} before returning.
     */
    public byte[] getRawContentBytes();

    /**
     * Returns the raw content associated with this request (as retrieved from {@link #getRawContentBytes()}) as a
     * string with {@link #getContentCharset()} encoding. This will be null until {@link #getRawContentBytes()} returns
     * a non-null value, at which point this method will return the content as a string. See the javadocs for {@link
     * #getRawContentBytes()} for details on all the rules. You can call {@link #isCompleteRequestWithAllChunks()} to
     * determine whether this method is returning null because we're waiting on all the content to finish arriving or
     * because the request has no content associated with it.
     * <p/>
     * NOTE: Since this method takes the {@link #getRawContentBytes()} and converts it into a string, this may not be
     * usable for some requests where the body is not 100% encoded with {@link #getContentCharset()}. Multipart requests
     * are one example. In these cases you should use {@link #getMultipartParts()} or {@link #getRawContentBytes()}
     * directly.
     * <p/>
     * ALSO NOTE: The result of this method is lazy-loaded from {@link #getRawContentBytes()} and cached, so this is yet
     * another copy of the content in addition to {@link #getRawContentBytes()}, {@link #getContent()} (if {@link
     * #setupContentDeserializer(ObjectMapper, TypeReference)} was called to lazy-load {@link #getContent()}), and
     * {@link #getMultipartParts()} (if that method was called to lazy-load multipart processing). Therefore you should
     * only call this method if absolutely necessary in order to keep memory pressure as low as possible.
     */
    public String getRawContent();

    /**
     * <b>IMPORTANT NOTE: THIS WILL RETURN NULL UNTIL {@link #setupContentDeserializer(ObjectMapper, TypeReference)} IS
     * CALLED!</b> And by default, that method is called by the endpoint setup handlers if your endpoint's {@link
     * Endpoint#requestContentType()} returns a non-null value. In other words, if you want this value to be non-null
     * when you call it from your endpoint, make sure your endpoint's {@link Endpoint#requestContentType()} returns a
     * non-null value (subclasses of {@code StandardEndpoint} should set this up automatically). Also, since this method
     * uses the {@link #getRawContentBytes()} to deserialize, it will be null until that method returns a non-null
     * value. You can call {@link #isCompleteRequestWithAllChunks()} to determine whether this method even has a chance
     * to return a non-null value.
     * <p/>
     * Implementations of this interface should generally make this a lazy-loaded value.
     *
     * @return The {@link #getRawContentBytes()} request body content after it was deserialized into the appropriate
     * object type by calling {@link #setupContentDeserializer(ObjectMapper, TypeReference)}, or null if no content was
     * provided. This will be null until {@link #setupContentDeserializer(ObjectMapper, TypeReference)} is called and
     * {@link #getRawContentBytes()} is non-null.
     */
    public T getContent();

    /**
     * Returns true if this request is a multipart request, false otherwise. If this is true and {@link
     * #isCompleteRequestWithAllChunks()}, then {@link #getMultipartParts()} should return a non-null value. See {@link
     * io.netty.handler.codec.http.multipart.HttpPostRequestDecoder#isMultipart(HttpRequest)} for details on what
     * constitutes a multipart request.
     */
    public boolean isMultipartRequest();

    /**
     * Returns the list of multipart data objects if and only if {@link #isMultipartRequest()} is true and {@link
     * #isCompleteRequestWithAllChunks()} is true. If either of those methods are false then this method will return
     * null. If {@link #releaseMultipartData()} has been called, then this method will throw an {@link
     * IllegalStateException}. Once this is called in a situation where it returns non-null data, the data is stored
     * behind the scenes (usually in the form of a {@link
     * io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder})
     * so that it doesn't have to be regenerated every time this method is called and so that its resources can be
     * released when {@link #releaseMultipartData()} is called.
     * <p/>
     * USAGE INFO: Each item in the returned list represents a multipart part. The raw {@link InterfaceHttpData}
     * interface doesn't provide many methods and for most cases it won't be sufficient. The {@link
     * InterfaceHttpData#getHttpDataType()} enum will tell you what class it can be safely cast to in order to provide
     * you with the methods you'll need to interact with it. There are three possibilities:
     * <pre>
     * <ol>
     *     <li>
     *         {@link InterfaceHttpData.HttpDataType#Attribute}: You can cast the object to a {@link
     *         io.netty.handler.codec.http.multipart.Attribute}
     *     </li>
     *     <li>
     *         {@link InterfaceHttpData.HttpDataType#FileUpload}: You can cast the object to a {@link
     *         io.netty.handler.codec.http.multipart.FileUpload}
     *     </li>
     *     <li>
     *         {@link InterfaceHttpData.HttpDataType#InternalAttribute}: You can cast the object to a {@link
     *         io.netty.handler.codec.http.multipart.InternalAttribute}, however <b>this should never happen in reality
     *         as this class is for internal use only</b>.
     *     </li>
     * </ol>
     * </pre>
     *
     * <p>Since the internal attribute should never be seen in the wild, and attribute and file upload both implement
     * {@link io.netty.handler.codec.http.multipart.HttpData}, you should be able to just cast directly to {@link
     * io.netty.handler.codec.http.multipart.HttpData} which may have all the methods you need including extracting the
     * multipart data as a byte array or String, etc. If you need the more specific {@link
     * io.netty.handler.codec.http.multipart.Attribute} or {@link io.netty.handler.codec.http.multipart.FileUpload}
     * methods then you'll need to look at {@link InterfaceHttpData#getHttpDataType()} to determine which one it is.
     * <p/>
     * <b>NOTE: {@link #releaseMultipartData()} *MUST* be called before this request is fully processed in order to
     * prevent memory leaks.</b> The pipeline will handle this for you automatically, however you can call it yourself
     * in endpoint code if you know you will never need it again and want to aggressively release resources.
     */
    public List<InterfaceHttpData> getMultipartParts();

    /**
     * Keeps track of the passed-in deserializer and type reference for the purpose of deserializing {@link
     * #getRawContentBytes()} into the desired object type when {@link #getContent()} is called. This method is called
     * as part of the default pipeline, so individual endpoints should never need to worry about this, however it's
     * important to note that this method must be called for {@link #getContent()} to have any shot at deserializing.
     */
    public RequestInfo<T> setupContentDeserializer(ObjectMapper deserializer, TypeReference<T> typeReference);

    /**
     * @return true if {@link #setupContentDeserializer(ObjectMapper, TypeReference)} was called and passed valid
     * deserialization info such that content can be deserialized and returned properly from {@link #getContent()},
     * false otherwise. Individual endpoints should never need to worry about this.
     */
    public boolean isContentDeserializerSetup();

    /**
     * The cookies associated with this request. Will never be null - an empty set will be used if no cookie information
     * was provided.
     */
    public Set<Cookie> getCookies();

    /**
     * The charset used to convert the content chunks (added via {@link #addContentChunk(HttpContent)}) into {@link
     * #getRawContent()}. This will never be null - {@link #DEFAULT_CONTENT_CHARSET} will be used if a charset could not
     * be determined from the headers.
     */
    public Charset getContentCharset();

    /**
     * The protocol version associated with this request, or null if this information was not provided.
     */
    public HttpVersion getProtocolVersion();

    /**
     * Whether or not the request is eligible for a keep-alive connection. This is calculated based on HTTP standards
     * and is related to both the Connection header and the {@link #getProtocolVersion()}. See {@link
     * HttpHeaders#isKeepAlive(HttpMessage)} for an example of the logic that is used.
     */
    public boolean isKeepAliveRequested();

    /**
     * Adds the given content chunk to the list of chunks this instance is tracking. When this method detects that the
     * passed-in chunk is the last chunk in the request it will mark this request so that {@link
     * #isCompleteRequestWithAllChunks()} will return true. Once this is done, the first subsequent call to {@link
     * #getRawContentBytes()} will convert all the chunks into a byte array and return it from then on. This
     * lazy-loading style protects us from loading the bytes and (very temporarily) doubling the memory necessary to
     * store it in the case that the request never causes {@link #getRawContentBytes()} to be called. NOTE: Calling
     * {@link #getRawContent()} is even worse memory-wise since it converts the bytes into a string and caches the
     * result. This is also true for {@link #getContent()}, however raw bytes are usually not useful and endpoints need
     * to inspect the data somehow, so the utility of the deserialized object usually pushes users in favor of {@link
     * #getContent()} rather than {@link #getRawContent()}. Just understand that those methods are lazy-loading, so if
     * you don't need to call {@link #getRawContent()} or {@link #getContent()} (or both) then you can save some memory
     * while the endpoint is executing.
     * <p/>
     * When the last chunk is detected this method will also set {@link #getTrailingHeaders()} to whatever trailing
     * headers were contained in the last chunk.
     * <p/>
     * NOTE: This method will throw an {@link IllegalStateException} if {@link #isCompleteRequestWithAllChunks()}
     * returns true. In other words, you can't call this method after the final chunk has been added.
     * <p/>
     * <b>ALSO NOTE - IMPORTANT:</b> In order to work with Netty reference counting, this method calls {@link
     * HttpContent#retain()} so that the chunk won't be destroyed before we've received all the content and had a chance
     * to convert it to the {@link #getRawContentBytes()} byte array. Therefore the {@link #releaseContentChunks()}
     * method MUST be called at some point before request processing completes, otherwise there will be memory leaks.
     * Normally this is handled transparently for you by {@link #getRawContentBytes()} when it converts the chunks into
     * a byte array, but the pipeline must take care of this itself in the case of exceptions being thrown before the
     * final chunk being added or in the case that the request never causes {@link #getRawContentBytes()} to be called.
     * Individual endpoints do not need to worry about this issue - it's a problem for the server to solve.
     */
    public void addContentChunk(HttpContent chunk);

    /**
     * Returns true if this request represents a 100% complete request with all chunks and trailing headers populated,
     * or false if this is a partial request and we're still waiting for more chunks.
     */
    public boolean isCompleteRequestWithAllChunks();

    /**
     * Method to add any object as an attribute (state) for the RequestInfo object. The attributes will have the same
     * lifespan as the RequestInfo object.
     *
     * @param attributeName
     *     name for the object
     * @param attributeValue
     *     object set as an attribute
     */
    public void addRequestAttribute(String attributeName, Object attributeValue);

    /**
     * The map of request attributes for this request info.
     *
     * @return The map of attributes, or an empty map when no attributes are set (will never return null).
     */
    public Map<String, Object> getRequestAttributes();

    /**
     * Calls {@link #releaseContentChunks()}, {@link #releaseMultipartData()}, and releases any other resources held by
     * the implementation of this interface. This is recommended as a shortcut for calling each individual method when
     * the request has been fully processed. That way if other resources are added in the future they will be
     * automatically released without any further code changes. Individual endpoints do not need to worry about this
     * issue - it's a problem for the server to solve.
     */
    public void releaseAllResources();

    /**
     * Calls {@link ReferenceCountUtil#release(Object)} on all the items in the content chunk list to release any
     * reference counts so Netty can reclaim them. After releasing the chunks, this method will also drop all references
     * to those chunks (e.g. with a {@link List#clear()} or whatever mechanism is being used to track the chunks) so
     * that subsequent calls to this method don't throw exceptions (or worse - cause incorrect reference counts). By
     * releasing a chunk, we're indicating that this instance no longer has any use for it, so there would be no point
     * in hanging on to it anyway.
     * <p/>
     * IMPORTANT NOTE: This method *MUST* be called at some point before the request is done, otherwise there will be
     * leaks. Normally this is handled transparently for you by {@link #getRawContentBytes()} when it converts the
     * chunks into a string, but the pipeline must take care of this itself in the case of exceptions being thrown
     * before the final chunk being added or in the case that the request never causes {@link #getRawContentBytes()} to
     * be called. Individual endpoints do not need to worry about this issue - it's a problem for the server to solve.
     */
    public void releaseContentChunks();

    /**
     * Calls {@link io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder#destroy()} on the cached
     * multipart data that is the source of {@link #getMultipartParts()} to release all its held resources including any
     * disk resources in use. Does nothing if this is not a multipart request or if the multipart data was never loaded.
     * Calling this multiple times has no negative effects.
     * <p/>
     * IMPORTANT NOTE: This method *MUST* be called at some point before the request is done, otherwise there will be
     * leaks from multipart requests. The pipeline must take care of this itself in all cases, including exceptions
     * being thrown in various phases. This does not need to be called from application endpoint code unless the
     * application wants to aggressively release resources before the request is fully processed. Individual endpoints
     * do not need to worry about this issue - it's a problem for the server to solve.
     */
    public void releaseMultipartData();
}
