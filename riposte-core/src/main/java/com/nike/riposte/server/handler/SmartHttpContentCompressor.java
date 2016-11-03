package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.ResponseInfo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Extension of {@link HttpContentCompressor} that is smart about whether it compresses responses or not. See the {@link
 * #write(ChannelHandlerContext, Object, ChannelPromise)} method for the specific rules, but in short compression is
 * only allowed if the following criteria are met:
 * <ul>
 *     <li>The total length of the raw content being sent must be greater than {@link #responseSizeThresholdBytes}</li>
 *     <li>
 *         The response must be a full response (i.e. the output message must be both a {@link HttpResponse} *and* a
 *         {@link LastHttpContent}). This is necessary because otherwise there's no way to tell what the total length of
 *         the raw content is.
 *         TODO: Should we allow streamed responses by looking for a Content-Length header and trusting it?
 *     </li>
 *     <li>
 *         The endpoint must allow it (i.e. the endpoint cannot be a {@link ProxyRouterEndpoint} - see
 *         {@link #endpointAllowsCompression(Endpoint)}).
 *     </li>
 *     <li>
 *         The response info must be available in the state and must allow it - see {@link
 *         ResponseInfo#isPreventCompressedOutput()}
 *     </li>
 * </ul>
 * Compression is prevented in all other cases.
 *
 * @author Nic Munroe
 */
public class SmartHttpContentCompressor extends HttpContentCompressor {

    private boolean allowCompressionForThisRequest = false;
    private final long responseSizeThresholdBytes;

    public SmartHttpContentCompressor(int responseSizeThresholdBytes) {
        this.responseSizeThresholdBytes = responseSizeThresholdBytes;
    }

    @SuppressWarnings("unused")
    public SmartHttpContentCompressor(int compressionLevel, int responseSizeThresholdBytes) {
        super(compressionLevel);
        this.responseSizeThresholdBytes = responseSizeThresholdBytes;
    }

    @SuppressWarnings("unused")
    public SmartHttpContentCompressor(int compressionLevel, int windowBits, int memLevel,
                                      int responseSizeThresholdBytes) {
        super(compressionLevel, windowBits, memLevel);
        this.responseSizeThresholdBytes = responseSizeThresholdBytes;
    }

    @Override
    protected Result beginEncode(HttpResponse headers, String acceptEncoding) throws Exception {
        if (allowCompressionForThisRequest)
            return super.beginEncode(headers, acceptEncoding);

        return null;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();

        allowCompressionForThisRequest = false;

        if (state != null) {
            // We only want to allow compression if the endpoint being hit is *not* a ProxyRouterEndpoint, the response is full, and the response size
            // is greater than the threshold
            boolean isFull = msg instanceof HttpResponse && msg instanceof LastHttpContent;
            boolean endpointAllowed = endpointAllowsCompression(state.getEndpointForExecution());
            boolean responseInfoAllowed =
                state.getResponseInfo() == null || !state.getResponseInfo().isPreventCompressedOutput();
            if (isFull && endpointAllowed && responseInfoAllowed
                && ((LastHttpContent) msg).content().readableBytes() > responseSizeThresholdBytes) {
                allowCompressionForThisRequest = true;
            }
        }

        super.write(ctx, msg, promise);
    }

    @SuppressWarnings("WeakerAccess")
    protected boolean endpointAllowsCompression(Endpoint<?> endpoint) {
        if (endpoint == null)
            return true;

        //noinspection RedundantIfStatement
        if (endpoint instanceof ProxyRouterEndpoint)
            return false;

        return true;
    }
}
