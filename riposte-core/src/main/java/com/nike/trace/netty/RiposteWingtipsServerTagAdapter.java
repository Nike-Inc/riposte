package com.nike.trace.netty;

import com.nike.internal.util.StringUtils;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import io.netty.handler.codec.http.HttpMethod;

/**
 * Extension of {@link HttpTagAndSpanNamingAdapter} that knows how to handle Riposte {@link RequestInfo} and
 * {@link ResponseInfo} objects.
 *
 * @author Nic Munroe
 */
public class RiposteWingtipsServerTagAdapter extends HttpTagAndSpanNamingAdapter<RequestInfo<?>, ResponseInfo<?>> {

    @SuppressWarnings("WeakerAccess")
    protected static final RiposteWingtipsServerTagAdapter DEFAULT_INSTANCE = new RiposteWingtipsServerTagAdapter();

    /**
     * @return A reusable, thread-safe, singleton instance of this class that can be used by anybody who wants to use
     * this class and does not need any customization.
     */
    @SuppressWarnings("unchecked")
    public static RiposteWingtipsServerTagAdapter getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Since this class represents server requests/responses (not clients), we only want to consider HTTP status codes
     * greater than or equal to 500 to be an error. From a server's perspective, a 4xx response is the correct
     * response to a bad request, and should therefore not be considered an error (again, from the server's
     * perspective - the client may feel differently).
     *
     * @param response The response object.
     * @return The value of {@link #getResponseHttpStatus(ResponseInfo)} if it is greater than or equal to 500,
     * or null otherwise.
     */
    @Override
    public @Nullable String getErrorResponseTagValue(@Nullable ResponseInfo<?> response) {
        Integer statusCode = getResponseHttpStatus(response);
        if (statusCode != null && statusCode >= 500) {
            return statusCode.toString();
        }

        // Status code does not indicate an error, so return null.
        return null;
    }

    @Nullable
    @Override
    public String getRequestUrl(@Nullable RequestInfo<?> request) {
        if (request == null) {
            return null;
        }

        return request.getUri();
    }

    @Nullable
    @Override
    public String getRequestPath(@Nullable RequestInfo<?> request) {
        if (request == null) {
            return null;
        }

        return request.getPath();
    }

    @Nullable
    @Override
    public String getRequestUriPathTemplate(
        @Nullable RequestInfo<?> request, @Nullable ResponseInfo<?> response
    ) {
        if (request == null) {
            return null;
        }

        String pathTemplate = request.getPathTemplate();
        if (StringUtils.isBlank(pathTemplate)) {
            return null;
        }

        return pathTemplate;
    }

    @Nullable
    @Override
    public Integer getResponseHttpStatus(@Nullable ResponseInfo<?> response) {
        if (response == null) {
            return null;
        }

        return response.getHttpStatusCode();
    }

    @Nullable
    @Override
    public String getRequestHttpMethod(@Nullable RequestInfo<?> request) {
        if (request == null) {
            return null;
        }

        HttpMethod method = request.getMethod();
        if (method == null) {
            return null;
        }

        return method.name();
    }

    @Nullable
    @Override
    public String getHeaderSingleValue(
        @Nullable RequestInfo<?> request, @NotNull String headerKey
    ) {
        if (request == null) {
            return null;
        }

        return request.getHeaders().get(headerKey);
    }

    @Nullable
    @Override
    public List<String> getHeaderMultipleValue(@Nullable RequestInfo<?> request, @NotNull String headerKey) {
        if (request == null) {
            return null;
        }

        return request.getHeaders().getAll(headerKey);
    }

    @Nullable
    @Override
    public String getSpanHandlerTagValue(
        @Nullable RequestInfo<?> request, @Nullable ResponseInfo<?> response
    ) {
        return "riposte.server";
    }
}
