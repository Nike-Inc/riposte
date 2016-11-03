package com.nike.riposte.server.http.impl;

import com.nike.riposte.server.http.ResponseInfo;

import java.nio.charset.Charset;
import java.util.Set;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;

/**
 * Base abstract {@link ResponseInfo} that takes care of the common getters and setters and rules around headers and
 * mime type. The stuff that can differ between response info types must still be implemented by concrete classes.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public abstract class BaseResponseInfo<T> implements ResponseInfo<T> {

    protected Integer httpStatusCode;
    protected final HttpHeaders headers;
    protected String desiredContentWriterMimeType;
    protected Charset desiredContentWriterEncoding;
    protected Set<Cookie> cookies;
    protected boolean preventCompressedOutput;
    protected Long uncompressedRawContentLength;
    protected Long finalContentLength;
    protected boolean responseSendingStarted;
    protected boolean responseSendingLastChunkSent;
    protected boolean forceConnectionCloseAfterResponseSent = false;

    protected BaseResponseInfo(Integer httpStatusCode, HttpHeaders headers,
                               String desiredContentWriterMimeType,
                               Charset desiredContentWriterEncoding,
                               Set<Cookie> cookies,
                               boolean preventCompressedOutput
    ) {

        if (headers == null)
            headers = new DefaultHttpHeaders();

        if (desiredContentWriterMimeType != null && desiredContentWriterMimeType.contains("charset=")) {
            throw new IllegalArgumentException(
                "desiredContentWriterMimeType should not contain the charset as well. It should just be the mime type. "
                + "Specify the charset using the desiredContentWriterEncoding argument"
            );
        }

        this.httpStatusCode = httpStatusCode;
        this.headers = headers;
        this.desiredContentWriterMimeType = desiredContentWriterMimeType;
        this.desiredContentWriterEncoding = desiredContentWriterEncoding;
        this.cookies = cookies;
        this.preventCompressedOutput = preventCompressedOutput;
    }

    @Override
    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    @Override
    public void setHttpStatusCode(Integer httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public String getDesiredContentWriterMimeType() {
        return desiredContentWriterMimeType;
    }

    @Override
    public void setDesiredContentWriterMimeType(String desiredContentWriterMimeType) {
        this.desiredContentWriterMimeType = desiredContentWriterMimeType;
    }

    @Override
    public Charset getDesiredContentWriterEncoding() {
        return desiredContentWriterEncoding;
    }

    @Override
    public void setDesiredContentWriterEncoding(Charset desiredContentWriterEncoding) {
        this.desiredContentWriterEncoding = desiredContentWriterEncoding;
    }

    @Override
    public Set<Cookie> getCookies() {
        return cookies;
    }

    @Override
    public void setCookies(Set<Cookie> cookies) {
        this.cookies = cookies;
    }

    @Override
    public boolean isPreventCompressedOutput() {
        return preventCompressedOutput;
    }

    @Override
    public void setPreventCompressedOutput(boolean preventCompressedOutput) {
        this.preventCompressedOutput = preventCompressedOutput;
    }

    @Override
    public Long getUncompressedRawContentLength() {
        return uncompressedRawContentLength;
    }

    @Override
    public void setUncompressedRawContentLength(Long uncompressedRawContentLength) {
        this.uncompressedRawContentLength = uncompressedRawContentLength;
    }

    @Override
    public Long getFinalContentLength() {
        return finalContentLength;
    }

    @Override
    public void setFinalContentLength(Long finalContentLength) {
        this.finalContentLength = finalContentLength;
    }

    @Override
    public boolean isResponseSendingStarted() {
        return responseSendingStarted;
    }

    @Override
    public void setResponseSendingStarted(boolean responseSendingStarted) {
        this.responseSendingStarted = responseSendingStarted;
    }

    @Override
    public boolean isResponseSendingLastChunkSent() {
        return responseSendingLastChunkSent;
    }

    @Override
    public void setResponseSendingLastChunkSent(boolean responseSendingLastChunkSent) {
        this.responseSendingLastChunkSent = responseSendingLastChunkSent;
    }

    @Override
    public boolean isForceConnectionCloseAfterResponseSent() {
        return forceConnectionCloseAfterResponseSent;
    }

    @Override
    public void setForceConnectionCloseAfterResponseSent(boolean forceConnectionCloseAfterResponseSent) {
        this.forceConnectionCloseAfterResponseSent = forceConnectionCloseAfterResponseSent;
    }
}
