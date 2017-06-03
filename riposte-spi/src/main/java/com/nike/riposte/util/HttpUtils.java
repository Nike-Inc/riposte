package com.nike.riposte.util;

import com.nike.riposte.server.error.exception.InvalidCharsetInContentTypeHeaderException;
import com.nike.riposte.server.error.exception.PathParameterMatchingException;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;

import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import static io.netty.handler.codec.http.HttpHeaders.Names.COOKIE;

/**
 * Static utility/helper methods for dealing with HTTP stuff (requests, responses, and all the associated tidbits).
 * TODO: Finish documenting all these methods
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class HttpUtils {

    /**
     * Regex Pattern for parsing charset from content-type header
     */
    protected static final Pattern CONTENT_TYPE_CHARSET_EXTRACTOR_PATTERN =
        Pattern.compile("(?i)\\bcharset=\\s*\"?([^\\s;\"]*)");

    protected static final AntPathMatcher pathParamExtractor = new AntPathMatcher();

    // Intentionally protected - use the static methods.
    protected HttpUtils() { /* do nothing */ }

    /**
     * @return The path portion of the given URI (i.e. everything before the '?' of a query string). For example if you
     * pass in {@code /my/uri?foo=bar} then this method will return {@code /my/uri}. If there is no query string in the
     * URI then the URI will be returned unchanged.
     */
    public static String extractPath(String uri) {
        if (uri == null)
            return "";

        int pathEndPos = uri.indexOf('?');
        if (pathEndPos < 0)
            return uri;

        return uri.substring(0, pathEndPos);
    }

    /**
     * @return The query string portion of the given URI (i.e. everything after the '?' of a URI string). For example if
     * you pass in {@code /my/uri?foo=bar} then this method will return {@code foo=bar}. If there is no query string in
     * the URI then this will return null.
     */
    public static String extractQueryString(String uri) {
        if (uri == null)
            return null;

        int questionMarkPos = uri.indexOf('?');
        if (questionMarkPos < 0)
            return null;

        if ((questionMarkPos + 1) >= uri.length())
            return null;

        return uri.substring(questionMarkPos + 1);
    }

    /**
     * @param headers
     *     The headers containing the content-type header to parse
     * @param def
     *     The default charset to use if one wasn't found in the headers
     *
     * @return The encoding specified in the header or the default Charset if not specified.
     **/
    public static Charset determineCharsetFromContentType(HttpHeaders headers, Charset def) {
        if (headers == null)
            return def;

        String contentTypeHeader = headers.get(HttpHeaders.Names.CONTENT_TYPE);
        if (contentTypeHeader == null)
            return def;

        String charset;
        Matcher m = CONTENT_TYPE_CHARSET_EXTRACTOR_PATTERN.matcher(contentTypeHeader);
        if (m.find()) {
            charset = m.group(1).trim().toUpperCase();
            try {
                return Charset.forName(charset);
            }
            catch (Exception ex) {
                throw new InvalidCharsetInContentTypeHeaderException("Invalid charset in Content-Type header", ex,
                                                                     contentTypeHeader);
            }
        }

        return def;
    }

    public static List<HttpContent> extractContentChunks(HttpRequest request) {
        if (!(request instanceof HttpContent))
            return null;

        return Collections.singletonList((HttpContent) request);
    }

    public static String convertRawBytesToString(Charset contentCharset, byte[] rawBytes) {
        if (contentCharset == null)
            throw new IllegalArgumentException("contentCharset cannot be null");

        if (rawBytes == null)
            return null;

        if (rawBytes.length == 0)
            return "";

        String rawString = new String(rawBytes, contentCharset);
        // UTF-16 can insert byte order mark characters when splicing together multiple chunks. Remove them
        rawString = rawString.replace("\uFEFF", "");
        return rawString;
    }

    public static String convertContentChunksToRawString(Charset contentCharset,
                                                         Collection<HttpContent> contentChunks) {
        byte[] rawBytes = convertContentChunksToRawBytes(contentChunks);
        if (rawBytes == null)
            return null;

        return convertRawBytesToString(contentCharset, rawBytes);
    }

    public static byte[] convertContentChunksToRawBytes(Collection<HttpContent> contentChunks) {
        if (contentChunks == null || contentChunks.size() == 0)
            return null;

        ByteBuf[] chunkByteBufs = contentChunks.stream().map(ByteBufHolder::content).toArray(ByteBuf[]::new);
        int totalNumBytes =
            contentChunks.stream().collect(Collectors.summingInt(chunk -> chunk.content().readableBytes()));
        if (totalNumBytes == 0)
            return null;

        byte[] comboBytes = new byte[totalNumBytes];
        int bytesWrittenSoFar = 0;
        for (ByteBuf chunk : chunkByteBufs) {
            int numBytesInThisChunk = chunk.readableBytes();
            chunk.getBytes(0, comboBytes, bytesWrittenSoFar, chunk.readableBytes());
            bytesWrittenSoFar += numBytesInThisChunk;
        }

        return comboBytes;
    }

    public static HttpHeaders extractTrailingHeadersIfPossible(HttpRequest request) {
        if (!(request instanceof LastHttpContent))
            return null;

        return ((LastHttpContent) request).trailingHeaders();
    }

    public static Set<Cookie> extractCookies(HttpRequest request) {
        Set<Cookie> cookies = new HashSet<>();

        HttpHeaders trailingHeaders = extractTrailingHeadersIfPossible(request);

        String cookieString = request.headers().get(COOKIE);
        if (cookieString == null && trailingHeaders != null)
            cookieString = trailingHeaders.get(COOKIE);

        if (cookieString != null)
            cookies.addAll(ServerCookieDecoder.LAX.decode(cookieString));

        return cookies;
    }

    public static Map<String, String> decodePathParams(String pathTemplate, String path) {
        // Ignore trailing slashes on either the template or path.
        if (pathTemplate.endsWith("/"))
            pathTemplate = pathTemplate.substring(0, pathTemplate.length() - 1);

        if (path.endsWith("/"))
            path = path.substring(0, path.length() - 1);

        if (!pathParamExtractor.match(pathTemplate, path)) {
            throw new PathParameterMatchingException(
                "Cannot decode path params - path template and URI path do not match.", pathTemplate, path);
        }

        return pathParamExtractor.extractUriTemplateVariables(pathTemplate, path);
    }


    public static String replaceUriPathVariables(RequestInfo<?> request, String downstreamDestinationUriPath) {
        for (String pathParam : request.getPathParams().keySet()) {
            downstreamDestinationUriPath =
                downstreamDestinationUriPath.replaceAll("\\{" + pathParam + "\\}", request.getPathParam(pathParam));
        }

        return downstreamDestinationUriPath;
    }

    public static boolean isMaxRequestSizeValidationDisabled(int configuredMaxRequestSize) {
        return configuredMaxRequestSize <= 0;
    }

    public static int getConfiguredMaxRequestSize(Endpoint<?> endpoint, int globalConfiguredMaxRequestSizeInBytes) {
        //if the endpoint is null or the endpoint is not overriding, we should return the globally configured value
        if (endpoint == null || endpoint.maxRequestSizeInBytesOverride() == null) {
            return globalConfiguredMaxRequestSizeInBytes;
        }

        return endpoint.maxRequestSizeInBytesOverride();
    }
}
