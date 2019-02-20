package com.nike.riposte.server.http;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.Charset;
import java.util.Set;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Tests the default functionality of {@link ResponseInfo}
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class ResponseInfoTest {

    @Test
    @DataProvider(value = {
            "400    |   200     |   400",
            "null   |   200     |   200",
    }, splitBy = "\\|")
    public void getHttpStatusCodeWithDefault_works_as_expected(Integer httpStatusCodeForResponseInfo, Integer defaultArg, Integer expectedReturnValue) {
        // given
        ResponseInfo<?> responseInfo = spy(new ResponseInfoForTesting<>());
        doReturn(httpStatusCodeForResponseInfo).when(responseInfo).getHttpStatusCode();

        // expect
        assertThat(responseInfo.getHttpStatusCodeWithDefault(defaultArg), is(expectedReturnValue));
    }

    private static class ResponseInfoForTesting<T> implements ResponseInfo<T> {
        @Override
        public @Nullable Integer getHttpStatusCode() {
            return null;
        }

        @Override
        public void setHttpStatusCode(@Nullable Integer httpStatusCode) {
        }

        @Override
        public @NotNull HttpHeaders getHeaders() {
            return new DefaultHttpHeaders();
        }

        @Override
        public boolean isChunkedResponse() {
            return false;
        }

        @Override
        public @Nullable T getContentForFullResponse() {
            return null;
        }

        @Override
        public void setContentForFullResponse(@Nullable T contentForFullResponse) {

        }

        @Override
        public @Nullable String getDesiredContentWriterMimeType() {
            return null;
        }

        @Override
        public void setDesiredContentWriterMimeType(@Nullable String desiredContentWriterMimeType) {
        }

        @Override
        public @Nullable Charset getDesiredContentWriterEncoding() {
            return null;
        }

        @Override
        public void setDesiredContentWriterEncoding(@Nullable Charset desiredContentWriterEncoding) {
        }

        @Override
        public @Nullable Set<Cookie> getCookies() {
            return null;
        }

        @Override
        public void setCookies(@Nullable Set<Cookie> cookies) {
        }

        @Override
        public boolean isPreventCompressedOutput() {
            return false;
        }

        @Override
        public void setPreventCompressedOutput(boolean preventCompressedOutput) {

        }

        @Override
        public @Nullable Long getUncompressedRawContentLength() {
            return null;
        }

        @Override
        public void setUncompressedRawContentLength(@Nullable Long uncompressedRawContentLength) {
        }

        @Override
        public @Nullable Long getFinalContentLength() {
            return null;
        }

        @Override
        public void setFinalContentLength(@Nullable Long finalContentLength) {
        }

        @Override
        public boolean isResponseSendingStarted() {
            return false;
        }

        @Override
        public void setResponseSendingStarted(boolean responseSendingStarted) {
        }

        @Override
        public boolean isResponseSendingLastChunkSent() {
            return false;
        }

        @Override
        public void setResponseSendingLastChunkSent(boolean responseSendingLastChunkSent) {
        }

        @Override
        public boolean isForceConnectionCloseAfterResponseSent() {
            return false;
        }

        @Override
        public void setForceConnectionCloseAfterResponseSent(boolean forceConnectionCloseAfterResponseSent) {
        }
    }

}