package com.nike.backstopper.handler.riposte;

import com.nike.backstopper.handler.RequestInfoForLogging;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.util.HttpUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter that allows {@link RequestInfo} to be used as a {@link RequestInfoForLogging}.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class RequestInfoForLoggingRiposteAdapter implements RequestInfoForLogging {

    private final @NotNull RequestInfo<?> request;
    private @Nullable Map<String, List<String>> headersMapCache;

    public RequestInfoForLoggingRiposteAdapter(@NotNull RequestInfo<?> request) {
        //noinspection ConstantConditions
        if (request == null)
            throw new IllegalArgumentException("request cannot be null");

        this.request = request;
    }

    @Override
    public @NotNull String getRequestUri() {
        return request.getPath();
    }

    @Override
    public @NotNull String getRequestHttpMethod() {
        return String.valueOf(request.getMethod());
    }

    @Override
    public @Nullable String getQueryString() {
        return HttpUtils.extractQueryString(request.getUri());
    }

    @Override
    public @NotNull Map<String, List<String>> getHeadersMap() {
        if (headersMapCache == null) {
            Map<String, List<String>> headersMap = new HashMap<>();

            Set<String> headerNames = request.getHeaders().names();
            if (headerNames != null) {
                for (String headerName : headerNames) {
                    List<String> headerValues = request.getHeaders().getAll(headerName);
                    if (headerValues != null) {
                        headersMap.put(headerName, headerValues);
                    }
                }
            }

            headersMapCache = headersMap;
        }

        return headersMapCache;
    }

    @Override
    public @Nullable String getHeader(String headerName) {
        return request.getHeaders().get(headerName);
    }

    @Override
    public @Nullable List<String> getHeaders(String headerName) {
        return getHeadersMap().get(headerName);
    }

    @Override
    public @Nullable Object getAttribute(String key) {
        return null;
    }

    @Override
    public @NotNull String getBody() throws GetBodyException {
        try {
            String result = request.getRawContent();
            if (result == null) {
                result = "";
            }
            return result;
        }
        catch (Exception ex) {
            throw new GetBodyException("An error occurred while trying to extract the request body.", ex);
        }
    }
}