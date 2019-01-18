package com.nike.riposte.client.asynchttp.ning;

import com.nike.internal.util.StringUtils;
import com.nike.riposte.util.HttpUtils;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;

import com.ning.http.client.Response;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Extension of {@link com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter} that knows how to handle the request
 * and response for {@link AsyncHttpClientHelper} ({@link RequestBuilderWrapper} and {@link Response}).
 *
 * @author Nic Munroe
 */
public class AsyncHttpClientHelperTagAdapter extends HttpTagAndSpanNamingAdapter<RequestBuilderWrapper, Response> {

    @SuppressWarnings("WeakerAccess")
    protected static final AsyncHttpClientHelperTagAdapter
        DEFAULT_INSTANCE = new AsyncHttpClientHelperTagAdapter();

    /**
     * @return A reusable, thread-safe, singleton instance of this class that can be used by anybody who wants to use
     * this class and does not need any customization.
     */
    @SuppressWarnings("unchecked")
    public static AsyncHttpClientHelperTagAdapter getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    @Nullable
    @Override
    public String getRequestUrl(@Nullable RequestBuilderWrapper request) {
        if (request == null) {
            return null;
        }

        return request.getUrl();
    }

    @Nullable
    @Override
    public String getRequestPath(@Nullable RequestBuilderWrapper request) {
        if (request == null) {
            return null;
        }

        String result = request.getUrl();
        if (StringUtils.isBlank(result)) {
            return null;
        }

        // Chop out the query string (if any).
        result = HttpUtils.extractPath(result);

        // If it starts with '/' then there's nothing left for us to do - it's already the path.
        if (result.startsWith("/")) {
            return result;
        }

        // Doesn't start with '/'. We expect it to start with http at this point.
        if (!result.toLowerCase().startsWith("http")) {
            // Didn't start with http. Not sure what to do with this at this point, so return null.
            return null;
        }

        // It starts with http. Chop out the scheme and host/port.
        int schemeColonAndDoubleSlashIndex = result.indexOf("://");
        if (schemeColonAndDoubleSlashIndex < 0) {
            // It didn't have a colon-double-slash after the scheme. Not sure what to do at this point, so return null.
            return null;
        }

        int firstSlashIndexAfterSchemeDoubleSlash = result.indexOf('/', (schemeColonAndDoubleSlashIndex + 3));
        if (firstSlashIndexAfterSchemeDoubleSlash < 0) {
            // No other slashes after the scheme colon-double-slash, so no real path. The path at this point is
            //      effectively "/".
            return "/";
        }

        return result.substring(firstSlashIndexAfterSchemeDoubleSlash);
    }

    @Nullable
    @Override
    public String getRequestUriPathTemplate(@Nullable RequestBuilderWrapper request, @Nullable Response response) {
        // Nothing we can do by default - this needs to be overridden on a per-project basis and given some smarts
        //      based on project-specific knowledge.
        return null;
    }

    @Nullable
    @Override
    public Integer getResponseHttpStatus(@Nullable Response response) {
        if (response == null) {
            return null;
        }

        return response.getStatusCode();
    }

    @Nullable
    @Override
    public String getRequestHttpMethod(@Nullable RequestBuilderWrapper request) {
        if (request == null) {
            return null;
        }

        return request.getHttpMethod();
    }

    @Nullable
    @Override
    public String getHeaderSingleValue(
        @Nullable RequestBuilderWrapper request, @NotNull String headerKey
    ) {
        // There's no way for us to get the headers - they're hidden away in the request.requestBuilder object and
        //      we have no access to them.
        return null;
    }

    @Nullable
    @Override
    public List<String> getHeaderMultipleValue(@Nullable RequestBuilderWrapper request, @NotNull String headerKey) {
        // There's no way for us to get the headers - they're hidden away in the request.requestBuilder object and
        //      we have no access to them.
        return null;
    }

    @Nullable
    @Override
    public String getSpanHandlerTagValue(
        @Nullable RequestBuilderWrapper request, @Nullable Response response
    ) {
        return "riposte.ningasynchttpclienthelper";
    }
}
