package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ResponseInfo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Monitors outgoing messages to capture the final state of the outgoing response before it is sent. This is used to
 * calculate the final content length of the outgoing payload, excluding headers (i.e. this is for the response body
 * content only). This final content length may be different than the raw content's length due to modifications on the
 * outgoing pipeline handlers, e.g. compression/gzip. After this handler is done processing outgoing messages, the
 * channel's {@link HttpProcessingState#getRequestInfo()}'s {@link ResponseInfo#getFinalContentLength()} will contain
 * the correct final value. This method is also used to capture the final {@link HttpResponse} - which may have
 * different headers set on it than the initial object pushed to the outbound pipeline, e.g. the Content-Length header
 * (due to compression/gzip).
 * <p/>
 * This handler should come directly *before* {@link io.netty.handler.codec.http.HttpResponseEncoder} in the *outgoing*
 * handler pipeline to make sure all response modifications are finished before this handler is called. Since outgoing
 * handlers are processed in reverse order this means this handler's index should be 1 greater than {@link
 * io.netty.handler.codec.http.HttpResponseEncoder}'s index in the channel pipeline list.
 *
 * @author Nic Munroe
 */
public class ProcessFinalResponseOutputHandler extends ChannelOutboundHandlerAdapter {

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // Deal with the final outbound HttpResponse
        if (msg instanceof HttpResponse) {
            HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            if (state != null)
                state.setActualResponseObject((HttpResponse) msg);
        }

        // Deal with the final outbound body content
        if (msg instanceof HttpContent) {
            HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            if (state != null && state.getResponseInfo() != null) {
                ResponseInfo<?> responseInfo = state.getResponseInfo();
                long contentBytes = ((HttpContent) msg).content().readableBytes();
                if (responseInfo.getFinalContentLength() == null)
                    responseInfo.setFinalContentLength(contentBytes);
                else
                    responseInfo.setFinalContentLength(responseInfo.getFinalContentLength() + contentBytes);
            }
        }

        super.write(ctx, msg, promise);
    }
}
