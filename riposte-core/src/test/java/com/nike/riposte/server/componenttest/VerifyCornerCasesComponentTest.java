package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.ApiErrorWithMetadata;
import com.nike.backstopper.apierror.sample.SampleCoreApiError;
import com.nike.internal.util.Pair;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils.NettyHttpClientRequestBuilder;
import com.nike.riposte.server.testutils.ComponentTestUtils.NettyHttpClientResponse;
import com.nike.riposte.util.Matcher;

import org.jetbrains.annotations.NotNull;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;

import static com.nike.riposte.server.testutils.ComponentTestUtils.findFreePort;
import static com.nike.riposte.server.testutils.ComponentTestUtils.request;
import static com.nike.riposte.server.testutils.ComponentTestUtils.verifyErrorReceived;
import static java.util.Collections.singleton;

public class VerifyCornerCasesComponentTest {

    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        downstreamServerConfig = new DownstreamServerTestConfig();
        downstreamServer = new Server(downstreamServerConfig);
        downstreamServer.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        downstreamServer.shutdown();
    }

    @Test
    public void invalid_http_call_that_causes_Netty_DecoderFailure_should_result_in_expected_400_error() throws Exception {
        // given
        // Normal request, but fiddle with the first chunk as it's going out to remove the HTTP version and make it an
        //      invalid HTTP call. This will cause Netty to mark the HttpRequest with a DecoderFailure.
        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.GET)
            .withUri(BasicEndpoint.MATCHING_PATH)
            .withPipelineAdjuster(
                p -> p.addFirst(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        String msgAsString = ((ByteBuf)msg).toString(CharsetUtil.UTF_8);

                        if (msgAsString.contains("HTTP/1.1")) {
                            msg = Unpooled.copiedBuffer(msgAsString.replace("HTTP/1.1", ""), CharsetUtil.UTF_8);
                        }
                        super.write(ctx, msg, promise);
                    }
                })
            );

        // when
        NettyHttpClientResponse response = request.execute(downstreamServerConfig.endpointsPort(), 3000);

        // then
        verifyErrorReceived(response.payload,
                            response.statusCode,
                            new ApiErrorWithMetadata(SampleCoreApiError.MALFORMED_REQUEST,
                                                     Pair.of("cause", "Invalid HTTP request"))
        );
    }

    @Test
    public void invalid_http_call_with_invalid_URL_escaping_should_result_in_expected_400_error() throws Exception {
        // given
        // Incorrectly escaped URLs cause a blowup in RequestInfoImpl when it tries to decode the URL. We can trigger
        //      this by putting a % character that is not followed by a proper escape sequence.
        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.GET)
            .withUri("%notAnEscapeSequence");

        // when
        NettyHttpClientResponse response = request.execute(downstreamServerConfig.endpointsPort(), 3000);

        // then
        verifyErrorReceived(response.payload,
                            response.statusCode,
                            new ApiErrorWithMetadata(SampleCoreApiError.MALFORMED_REQUEST,
                                                     Pair.of("cause", "Invalid HTTP request"))
        );
    }

    public static class BasicEndpoint extends StandardEndpoint<Void, String> {
        public static final String MATCHING_PATH = "/basicEndpoint";
        public static final String RESPONSE_PAYLOAD = "basic-endpoint-" + UUID.randomUUID().toString();

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }

        @Override
        public @NotNull CompletableFuture<ResponseInfo<String>> execute(
            @NotNull RequestInfo<Void> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            return CompletableFuture.completedFuture(ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build());
        }
    }

    public static class DownstreamServerTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public DownstreamServerTestConfig() {
            try {
                port = findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = singleton(new BasicEndpoint());
        }

        @Override
        public @NotNull Collection<@NotNull Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }
    }
}