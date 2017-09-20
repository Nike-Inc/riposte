package com.nike.riposte.server.testutils;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.wingtips.Tracer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;
import org.slf4j.MDC;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestUtil {

    public static ChannelHandlerContextMocks mockChannelHandlerContext() {
        HttpProcessingState mockHttpProcessingState = mock(HttpProcessingState.class);
        ChannelHandlerContext mockContext = mock(ChannelHandlerContext.class);
        Channel mockChannel = mock(Channel.class);
        when(mockContext.channel()).thenReturn(mockChannel);
        @SuppressWarnings("unchecked")
        Attribute<HttpProcessingState> mockAttribute = mock(Attribute.class);
        when(mockContext.channel().attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY)).thenReturn(mockAttribute);
        when(mockAttribute.get()).thenReturn(mockHttpProcessingState);
        return new ChannelHandlerContextMocks(mockContext, mockChannel, mockAttribute, mockHttpProcessingState);
    }

    public static ChannelHandlerContextMocks mockChannelHandlerContextWithTraceInfo() {
        return mockChannelHandlerContextWithTraceInfo("123");
    }

    public static ChannelHandlerContextMocks mockChannelHandlerContextWithTraceInfo(String userId) {
        if (Tracer.getInstance().getCurrentSpan() == null) {
            Tracer.getInstance().startRequestWithRootSpan("mockChannelHandlerContext", userId);
        }

        ChannelHandlerContextMocks channelHandlerMocks = mockChannelHandlerContext();

        when(channelHandlerMocks.mockHttpProcessingState.getLoggerMdcContextMap()).thenReturn(MDC.getCopyOfContextMap());
        when(channelHandlerMocks.mockHttpProcessingState.getDistributedTraceStack()).thenReturn(Tracer.getInstance().getCurrentSpanStackCopy());

        return channelHandlerMocks;
    }

    public static class ChannelHandlerContextMocks {
        public final ChannelHandlerContext mockContext;
        public final Channel mockChannel;
        public final Attribute<HttpProcessingState> mockAttribute;
        public final HttpProcessingState mockHttpProcessingState;

        public ChannelHandlerContextMocks(ChannelHandlerContext mockContext, Channel mockChannel, Attribute<HttpProcessingState> mockAttribute, HttpProcessingState mockHttpProcessingState) {
            this.mockContext = mockContext;
            this.mockChannel = mockChannel;
            this.mockAttribute = mockAttribute;
            this.mockHttpProcessingState = mockHttpProcessingState;
        }
    }
}
