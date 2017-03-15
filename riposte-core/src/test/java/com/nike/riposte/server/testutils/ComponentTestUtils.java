package com.nike.riposte.server.testutils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.internal.util.Pair;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import org.apache.commons.lang3.RandomUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.netty.util.CharsetUtil.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Helper methods for working with component tests.
 *
 * @author Nic Munroe
 */
public class ComponentTestUtils {

    private static final String payloadDictionary = "aBcDefGhiJkLmN@#$%";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static int findFreePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    public static String generatePayload(int payloadSize) {
        StringBuilder payload = new StringBuilder();

        for(int i = 0; i < payloadSize; i++) {
            int randomInt = RandomUtils.nextInt(0, payloadDictionary.length() - 1);
            payload.append(payloadDictionary.charAt(randomInt));
        }

        return payload.toString();
    }

    public static ByteBuf createByteBufPayload(int payloadSize) {
        return Unpooled.wrappedBuffer(generatePayload(payloadSize).getBytes(UTF_8));
    }

    public static Pair<Integer, String> executeRequest(HttpRequest request, int port, int incompleteCallTimeoutMillis) throws Exception {
        Bootstrap bootstrap = new Bootstrap();
        EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
        try {
            CompletableFuture<Pair<Integer, String>> responseFromServer = new CompletableFuture<>();
            bootstrap.group(eventLoopGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new HttpClientCodec());
                            p.addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
                            p.addLast(new SimpleChannelInboundHandler<HttpObject>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg)
                                        throws Exception {
                                    if (msg instanceof FullHttpResponse) {
                                        // Store the proxyServer response for asserting on later.
                                        FullHttpResponse responseMsg = (FullHttpResponse) msg;
                                        responseFromServer.complete(
                                                Pair.of(responseMsg.getStatus().code(), responseMsg.content().toString(UTF_8)));
                                    } else {
                                        // Should never happen.
                                        throw new RuntimeException("Received unexpected message type: " + msg.getClass());
                                    }
                                }
                            });
                        }
                    });

            // Connect to the proxyServer.
            Channel ch = bootstrap.connect("localhost", port).sync().channel();

            // Send the request.
            ch.writeAndFlush(request);

            // Wait for the response to be received
            try {
                responseFromServer.get(incompleteCallTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                fail("The call took much longer than expected without receiving a response. "
                        + "Cancelling this test - it's not working properly", ex);
            } finally {
                ch.close();
            }

            // If we reach here then the call should be complete.
            return responseFromServer.get();
        } finally {
            eventLoopGroup.shutdownGracefully();
        }
    }

    public static void verifyErrorReceived(String response, int responseStatusCode, ApiError expectedApiError) throws IOException {
        assertThat(responseStatusCode).isEqualTo(expectedApiError.getHttpStatusCode());
        DefaultErrorContractDTO responseAsError = objectMapper.readValue(response, DefaultErrorContractDTO.class);
        assertThat(responseAsError.errors).hasSize(1);
        assertThat(responseAsError.errors.get(0).code).isEqualTo(expectedApiError.getErrorCode());
        assertThat(responseAsError.errors.get(0).message).isEqualTo(expectedApiError.getMessage());
        assertThat(responseAsError.errors.get(0).metadata).isEqualTo(expectedApiError.getMetadata());
    }
}
