package com.nike.riposte.server.http.servletfakery;

import com.nike.internal.util.StringUtils;
import com.nike.riposte.server.http.RequestInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

import io.netty.handler.codec.http.HttpHeaders;

/**
 * Wrapper implementation of {@link HttpServletRequest} that delegates to a {@link RequestInfo} where possible. This
 * class will throw {@link UnsupportedOperationException} for many methods, and other methods may not be thorough in
 * their implementations, but it should work ok for many purposes.
 * <p/>
 * NOTE: This class is only here to interface with utility libraries/etc that are servlet-based. If at all possible the
 * libraries should be refactored to not require servlet stuff.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class HttpServletRequestWrapperForRequestInfo<T> implements HttpServletRequest {

    public final RequestInfo<T> requestInfo;
    protected final boolean isSsl;
    private Cookie[] convertedCookieCache;

    public HttpServletRequestWrapperForRequestInfo(RequestInfo<T> requestInfo, boolean isSsl) {
        if (requestInfo == null)
            throw new IllegalArgumentException("requestInfo cannot be null");
        this.requestInfo = requestInfo;
        this.isSsl = isSsl;
    }

    @Override
    public String getAuthType() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Cookie[] getCookies() {
        if (convertedCookieCache == null) {
            List<Cookie> convertedCookies = requestInfo.getCookies().stream()
                                                       .map(nettyCookie -> new Cookie(nettyCookie.name(),
                                                                                      nettyCookie.value()))
                                                       .collect(Collectors.toList());
            convertedCookieCache = convertedCookies.toArray(new Cookie[convertedCookies.size()]);
        }

        return convertedCookieCache;
    }

    @Override
    public long getDateHeader(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getHeader(String name) {
        return requestInfo.getHeaders().get(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(requestInfo.getHeaders().getAll(name));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(requestInfo.getHeaders().names());
    }

    @Override
    public int getIntHeader(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getMethod() {
        if (requestInfo.getMethod() == null)
            return null;

        return requestInfo.getMethod().name();
    }

    @Override
    public String getPathInfo() {
        return requestInfo.getPath();
    }

    @Override
    public String getPathTranslated() {
        throw new UnsupportedOperationException();
    }

    public String extractContext(String uri) {
        if (uri == null || uri.indexOf('/') != 0)
            return "";

        int secondSlashPos = uri.indexOf('/', 1);
        if (secondSlashPos < 0)
            return "";

        return uri.substring(0, secondSlashPos);
    }

    @Override
    public String getContextPath() {
        return extractContext(requestInfo.getPath());
    }

    @Override
    public String getQueryString() {
        List<String> queryParams = requestInfo.getQueryParams().parameters().entrySet().stream()
                                              .map(entry -> entry.getKey() + "="
                                                            + StringUtils.join(entry.getValue(), ","))
                                              .collect(Collectors.toList());

        if (queryParams.isEmpty())
            return null;

        return StringUtils.join(queryParams, "&");
    }

    @Override
    public String getRemoteUser() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUserInRole(String role) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Principal getUserPrincipal() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRequestedSessionId() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRequestURI() {
        return requestInfo.getPath();
    }

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer sb = new StringBuffer();

        sb.append(getScheme()).append("://");

        String host = requestInfo.getHeaders().get(HttpHeaders.Names.HOST);
        if (host == null)
            host = "unknown";

        sb.append(host).append(requestInfo.getPath());
        return sb;
    }

    @Override
    public String getServletPath() {
        return "";
    }

    @Override
    public HttpSession getSession(boolean create) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpSession getSession() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String changeSessionId() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public boolean isRequestedSessionIdFromUrl() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void login(String username, String password) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void logout() throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U extends HttpUpgradeHandler> U upgrade(Class<U> handlerClass) throws IOException, ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getAttribute(String name) {
        return requestInfo.getRequestAttributes().get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(requestInfo.getRequestAttributes().keySet());
    }

    @Override
    public String getCharacterEncoding() {
        return null;
    }

    @Override
    public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getContentLength() {
        return requestInfo.getRawContentLengthInBytes();
    }

    @Override
    public long getContentLengthLong() {
        return getContentLength();
    }

    @Override
    public String getContentType() {
        return requestInfo.getHeaders().get(HttpHeaders.Names.CONTENT_TYPE);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getParameter(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getParameterValues(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getProtocol() {
        if (requestInfo.getProtocolVersion() == null)
            return null;
        return requestInfo.getProtocolVersion().text();
    }

    @Override
    public String getScheme() {
        if (isSsl)
            return "https";
        return "http";
    }

    @Override
    public String getServerName() {
        String host = requestInfo.getHeaders().get(HttpHeaders.Names.HOST);
        if (host == null)
            host = "unknown";

        if (host.contains(":"))
            host = host.substring(0, host.indexOf(':'));

        return host;
    }

    @Override
    public int getServerPort() {
        String portString = requestInfo.getHeaders().get(HttpHeaders.Names.HOST);
        if (portString == null || !portString.contains(":"))
            return -1;

        portString = portString.substring(portString.indexOf(':') + 1);

        return Integer.parseInt(portString);
    }

    @Override
    public BufferedReader getReader() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRemoteAddr() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRemoteHost() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAttribute(String name, Object o) {
        requestInfo.addRequestAttribute(name, o);
    }

    @Override
    public void removeAttribute(String name) {
        requestInfo.getRequestAttributes().remove(name);
    }

    @Override
    public Locale getLocale() {
        return Locale.getDefault();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return Collections.enumeration(Collections.singleton(getLocale()));
    }

    @Override
    public boolean isSecure() {
        return "https".equals(getScheme());
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public String getRealPath(String path) {
        return null;
    }

    @Override
    public int getRemotePort() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getLocalName() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getLocalAddr() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getLocalPort() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletContext getServletContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse)
        throws IllegalStateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAsyncStarted() {
        return false;
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public DispatcherType getDispatcherType() {
        throw new UnsupportedOperationException();
    }
}
