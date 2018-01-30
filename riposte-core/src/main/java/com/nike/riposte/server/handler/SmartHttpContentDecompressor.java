package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.RequestInfo;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContentDecompressor;

/**
 * Extension of {@link HttpContentDecompressor} that is smart about whether it decompresses responses or not. It
 * inspects the {@link HttpProcessingState#getEndpointForExecution()} to see what
 * {@link Endpoint#isDecompressRequestPayloadAllowed(RequestInfo)} returns, and only allows automatic decompression if
 * that methods returns true.
 *
 * <p>{@link ProxyRouterEndpoint}s return false by default for {@link
 * ProxyRouterEndpoint#isDecompressRequestPayloadAllowed(RequestInfo)}, so they will not auto-decompress unless that
 * method is overridden for a given {@link ProxyRouterEndpoint}. Other endpoint types default to true causing them to
 * auto-decompress unless that method is overridden to return false.
 *
 * @author Nic Munroe
 */
public class SmartHttpContentDecompressor extends HttpContentDecompressor {

    public SmartHttpContentDecompressor() {
        super();
    }

    public SmartHttpContentDecompressor(boolean strict) {
        super(strict);
    }

    @Override
    protected EmbeddedChannel newContentDecoder(String contentEncoding) throws Exception {
        // We only allow decompression if the endpoint allows it.
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        Endpoint<?> endpoint = state.getEndpointForExecution();

        if (endpointAllowsDecompression(endpoint, state)) {
            return super.newContentDecoder(contentEncoding);
        }

        // The endpoint does not allow decompression. Return null to indicate that this handler should not
        //      auto-decompress this request's payload.
        return null;
    }

    protected boolean endpointAllowsDecompression(Endpoint<?> endpoint, HttpProcessingState state) {
        if (endpoint == null)
            return true;

        return endpoint.isDecompressRequestPayloadAllowed(state.getRequestInfo());
    }
}
