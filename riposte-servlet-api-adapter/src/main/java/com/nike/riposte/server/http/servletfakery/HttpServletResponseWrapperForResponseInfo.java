package com.nike.riposte.server.http.servletfakery;

import com.nike.riposte.server.http.ResponseInfo;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Locale;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.CharsetUtil;

import static io.netty.handler.codec.http.HttpHeaders.Names.SET_COOKIE;

/**
 * Wrapper implementation of {@link HttpServletResponse} that delegates to a {@link ResponseInfo} where possible. This
 * class will throw {@link UnsupportedOperationException} for many methods, but it should work ok for many purposes.
 * <p/>
 * NOTE: This class is only here to interface with utility libraries/etc that are servlet-based. If at all possible the
 * libraries should be refactored to not require servlet stuff.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class HttpServletResponseWrapperForResponseInfo<T> implements HttpServletResponse {

    public final ResponseInfo<T> responseInfo;

    public HttpServletResponseWrapperForResponseInfo(ResponseInfo<T> responseInfo) {
        if (responseInfo == null)
            throw new IllegalArgumentException("responseInfo cannot be null");
        this.responseInfo = responseInfo;
    }

    @Override
    public void addCookie(Cookie cookie) {
        responseInfo.getHeaders().add(SET_COOKIE, ServerCookieEncoder.LAX.encode(cookie.getName(), cookie.getValue()));
    }

    @Override
    public boolean containsHeader(String name) {
        return responseInfo.getHeaders().contains(name);
    }

    @Override
    public String encodeURL(String url) {
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return url;
    }

    @Override
    @Deprecated
    public String encodeUrl(String url) {
        return url;
    }

    @Override
    @Deprecated
    public String encodeRedirectUrl(String url) {
        return url;
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendError(int sc) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setDateHeader(String name, long date) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addDateHeader(String name, long date) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setHeader(String name, String value) {
        responseInfo.getHeaders().set(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        responseInfo.getHeaders().add(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addIntHeader(String name, int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setStatus(int sc) {
        responseInfo.setHttpStatusCode(sc);
    }

    @Override
    @Deprecated
    public void setStatus(int sc, String sm) {
        setStatus(sc);
    }

    @Override
    public int getStatus() {
        return responseInfo.getHttpStatusCode();
    }

    @Override
    public String getHeader(String name) {
        return responseInfo.getHeaders().get(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return responseInfo.getHeaders().getAll(name);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return responseInfo.getHeaders().names();
    }

    @Override
    public String getCharacterEncoding() {
        if (responseInfo.getDesiredContentWriterEncoding() == null)
            return CharsetUtil.ISO_8859_1.name();

        return responseInfo.getDesiredContentWriterEncoding().name();
    }

    @Override
    public String getContentType() {
        if (responseInfo.getDesiredContentWriterMimeType() == null)
            return null;

        String contentType = responseInfo.getDesiredContentWriterMimeType();
        if (responseInfo.getDesiredContentWriterEncoding() != null)
            contentType += "; charset=" + responseInfo.getDesiredContentWriterEncoding().name();

        return contentType;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setCharacterEncoding(String charset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setContentLength(int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setContentLengthLong(long len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setContentType(String type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBufferSize(int size) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getBufferSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void flushBuffer() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resetBuffer() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    public void reset() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setLocale(Locale loc) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Locale getLocale() {
        throw new UnsupportedOperationException();
    }
}
