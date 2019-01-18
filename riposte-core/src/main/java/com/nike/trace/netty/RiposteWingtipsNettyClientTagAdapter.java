package com.nike.trace.netty;

import com.nike.internal.util.StringUtils;
import com.nike.riposte.util.HttpUtils;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Extension of {@link HttpTagAndSpanNamingAdapter} that knows how to handle raw Netty {@link HttpRequest} and
 * {@link HttpResponse} objects.
 *
 * @author Nic Munroe
 */
public class RiposteWingtipsNettyClientTagAdapter extends HttpTagAndSpanNamingAdapter<HttpRequest, HttpResponse> {

    @SuppressWarnings("WeakerAccess")
    protected static final RiposteWingtipsNettyClientTagAdapter
        DEFAULT_INSTANCE = new RiposteWingtipsNettyClientTagAdapter();

    /**
     * @return A reusable, thread-safe, singleton instance of this class that can be used by anybody who wants to use
     * this class and does not need any customization.
     */
    @SuppressWarnings("unchecked")
    public static RiposteWingtipsNettyClientTagAdapter getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    @Nullable
    @Override
    public String getRequestUrl(@Nullable HttpRequest request) {
        if (request == null) {
            return null;
        }

        return request.uri();
    }

    @Nullable
    @Override
    public String getRequestPath(@Nullable HttpRequest request) {
        if (request == null) {
            return null;
        }

        String result = HttpUtils.extractPath(request.uri());
        if (StringUtils.isBlank(result)) {
            return null;
        }

        return result;
    }

    @Nullable
    @Override
    public String getRequestUriPathTemplate(@Nullable HttpRequest request, @Nullable HttpResponse response) {
        // Nothing we can do by default - this needs to be overridden on a per-project basis and given some smarts
        //      based on project-specific knowledge.
        return null;
    }

    @Nullable
    @Override
    public Integer getResponseHttpStatus(@Nullable HttpResponse response) {
        if (response == null) {
            return null;
        }

        HttpResponseStatus statusObj = response.status();
        if (statusObj == null) {
            return null;
        }

        return statusObj.code();
    }

    @Nullable
    @Override
    public String getRequestHttpMethod(@Nullable HttpRequest request) {
        if (request == null) {
            return null;
        }

        HttpMethod method = request.method();
        if (method == null) {
            return null;
        }

        return method.name();
    }

    @Nullable
    @Override
    public String getHeaderSingleValue(
        @Nullable HttpRequest request, @NotNull String headerKey
    ) {
        if (request == null) {
            return null;
        }

        HttpHeaders headers = request.headers();
        if (headers == null) {
            return null;
        }

        return headers.get(headerKey);
    }

    @Nullable
    @Override
    public List<String> getHeaderMultipleValue(@Nullable HttpRequest request, @NotNull String headerKey) {
        if (request == null) {
            return null;
        }

        HttpHeaders headers = request.headers();
        if (headers == null) {
            return null;
        }

        return headers.getAll(headerKey);
    }

    @Nullable
    @Override
    public String getSpanHandlerTagValue(
        @Nullable HttpRequest request, @Nullable HttpResponse response
    ) {
        return "netty.httpclient";
    }
}
