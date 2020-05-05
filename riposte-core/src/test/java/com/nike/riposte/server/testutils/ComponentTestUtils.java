package com.nike.riposte.server.testutils;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.internal.util.Pair;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.lang3.RandomUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

import static io.netty.util.CharsetUtil.UTF_8;
import static org.apache.commons.lang3.StringUtils.split;
import static org.apache.commons.lang3.StringUtils.substringAfter;
import static org.apache.commons.lang3.StringUtils.substringBefore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Helper methods for working with component tests.
 *
 * @author Nic Munroe
 */
public class ComponentTestUtils {

    private static final String HEADER_SEPARATOR = ":";
    private static final String payloadDictionary = "aBcDefGhiJkLmN@#$%";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static int findFreePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    public static String generatePayload(int payloadSize) {
        return generatePayload(payloadSize, payloadDictionary);
    }

    public static String generatePayload(int payloadSize, String dictionary) {
        StringBuilder payload = new StringBuilder();

        for(int i = 0; i < payloadSize; i++) {
            int randomInt = RandomUtils.nextInt(0, dictionary.length() - 1);
            payload.append(dictionary.charAt(randomInt));
        }

        return payload.toString();
    }

    public static ByteBuf createByteBufPayload(int payloadSize) {
        return Unpooled.wrappedBuffer(generatePayload(payloadSize).getBytes(UTF_8));
    }

    public static byte[] gzipPayload(String payload) {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(bytesOut)) {
            byte[] payloadBytes = payload.getBytes(UTF_8);
            gzipOutputStream.write(payloadBytes);
            gzipOutputStream.finish();
            return bytesOut.toByteArray();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String ungzipPayload(byte[] compressed) {
        try {
            if ((compressed == null) || (compressed.length == 0)) {
                throw new RuntimeException("Null/empty compressed payload. is_null=" + (compressed == null));
            }

            final StringBuilder outStr = new StringBuilder();
            final GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
            final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(gis, "UTF-8"));

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                outStr.append(line);
            }

            return outStr.toString();
        }
        catch(IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static byte[] deflatePayload(String payload) {
        Deflater deflater = new Deflater(6, false);
        byte[] payloadBytes = payload.getBytes(UTF_8);
        deflater.setInput(payloadBytes);
        deflater.finish();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            outputStream.write(buffer, 0, count);
        }

        return outputStream.toByteArray();
    }

    public static String inflatePayload(byte[] compressed) {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!inflater.finished()) {
            try {
                int count = inflater.inflate(buffer);
                outputStream.write(buffer, 0, count);
            }
            catch (DataFormatException e) {
                throw new RuntimeException(e);
            }
        }

        return new String(outputStream.toByteArray(), UTF_8);
    }

    public static String base64Encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] base64Decode(String encodedStr) {
        return Base64.getDecoder().decode(encodedStr);
    }

    public enum CompressionType {
        GZIP(ComponentTestUtils::gzipPayload,
             ComponentTestUtils::ungzipPayload,
             HttpHeaders.Values.GZIP),
        DEFLATE(ComponentTestUtils::deflatePayload,
                ComponentTestUtils::inflatePayload,
                HttpHeaders.Values.DEFLATE),
        IDENTITY(s -> s.getBytes(UTF_8),
                 b -> new String(b, UTF_8),
                 HttpHeaders.Values.IDENTITY);

        private final Function<String, byte[]> compressionFunction;
        private final Function<byte[], String> decompressionFunction;
        public final String contentEncodingHeaderValue;

        CompressionType(Function<String, byte[]> compressionFunction,
                        Function<byte[], String> decompressionFunction,
                        String contentEncodingHeaderValue) {
            this.compressionFunction = compressionFunction;
            this.decompressionFunction = decompressionFunction;
            this.contentEncodingHeaderValue = contentEncodingHeaderValue;
        }

        public byte[] compress(String s) {
            return compressionFunction.apply(s);
        }

        public String decompress(byte[] compressed) {
            return decompressionFunction.apply(compressed);
        }
    }

    public static Bootstrap createNettyHttpClientBootstrap() {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(new NioEventLoopGroup())
                 .channel(NioSocketChannel.class)
                 .handler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     protected void initChannel(SocketChannel ch) throws Exception {
                         ChannelPipeline p = ch.pipeline();
                         p.addLast(new HttpClientCodec());
                         p.addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
                         p.addLast("clientResponseHandler", new SimpleChannelInboundHandler<HttpObject>() {
                             @Override
                             protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
                                 throw new RuntimeException("Client response handler was not setup before the call");
                             }
                         });
                     }
                 });

        return bootstrap;
    }

    public static Channel connectNettyHttpClientToLocalServer(Bootstrap bootstrap, int port) throws InterruptedException {
        return bootstrap.connect("localhost", port).sync().channel();
    }

    public static CompletableFuture<NettyHttpClientResponse> setupNettyHttpClientResponseHandler(Channel ch) {
        return setupNettyHttpClientResponseHandler(ch, null);
    }

    public static CompletableFuture<NettyHttpClientResponse> setupNettyHttpClientResponseHandler(
        Channel ch, Consumer<ChannelPipeline> pipelineAdjuster
    ) {
        CompletableFuture<NettyHttpClientResponse> responseFromServerFuture = new CompletableFuture<>();
        ch.pipeline().replace("clientResponseHandler", "clientResponseHandler", new SimpleChannelInboundHandler<HttpObject>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg)
                throws Exception {
                if (msg instanceof FullHttpResponse) {
                    // Store the proxyServer response for asserting on later.
                    responseFromServerFuture.complete(new NettyHttpClientResponse((FullHttpResponse) msg));
                } else {
                    // Should never happen.
                    throw new RuntimeException("Received unexpected message type: " + msg.getClass());
                }
            }
        });

        if (pipelineAdjuster != null)
            pipelineAdjuster.accept(ch.pipeline());
        
        return responseFromServerFuture;
    }

    public static NettyHttpClientResponse executeNettyHttpClientCall(
        Channel ch, FullHttpRequest request, long incompleteCallTimeoutMillis
    ) throws ExecutionException, InterruptedException, TimeoutException {
        return executeNettyHttpClientCall(ch, request, incompleteCallTimeoutMillis, null);
    }

    public static NettyHttpClientResponse executeNettyHttpClientCall(
        Channel ch, FullHttpRequest request, long incompleteCallTimeoutMillis, Consumer<ChannelPipeline> pipelineAdjuster
    ) throws ExecutionException, InterruptedException, TimeoutException {

        CompletableFuture<NettyHttpClientResponse> responseFuture = setupNettyHttpClientResponseHandler(ch, pipelineAdjuster);

        // Send the request.
        ch.writeAndFlush(request);

        // Wait for the response to be received
        return responseFuture.get(incompleteCallTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    public static class NettyHttpClientResponse {
        public final int statusCode;
        public final HttpHeaders headers;
        public final String payload;
        public final byte[] payloadBytes;
        public final FullHttpResponse fullHttpResponse;

        public NettyHttpClientResponse(FullHttpResponse fullHttpResponse) {
            this.statusCode = fullHttpResponse.status().code();
            this.headers = fullHttpResponse.headers();
            ByteBuf content = fullHttpResponse.content();
            this.payloadBytes = new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(), this.payloadBytes);
            this.payload = new String(this.payloadBytes, UTF_8);
            this.fullHttpResponse = fullHttpResponse;
        }
    }

    public static NettyHttpClientResponse executeRequest(
        FullHttpRequest request, int port, long incompleteCallTimeoutMillis
    ) throws InterruptedException, TimeoutException, ExecutionException {
        return executeRequest(request, port, incompleteCallTimeoutMillis, null);
    }

    public static NettyHttpClientResponse executeRequest(
        FullHttpRequest request, int port, long incompleteCallTimeoutMillis, Consumer<ChannelPipeline> pipelineAdjuster
    ) throws InterruptedException, TimeoutException, ExecutionException {
        Bootstrap bootstrap = createNettyHttpClientBootstrap();
        try {
            // Connect to the proxyServer.
            Channel ch = connectNettyHttpClientToLocalServer(bootstrap, port);

            try {
                return executeNettyHttpClientCall(ch, request, incompleteCallTimeoutMillis, pipelineAdjuster);
            }
            finally {
                ch.close();
            }
        } finally {
            bootstrap.config().group().shutdownGracefully();
        }
    }

    public static NettyHttpClientRequestBuilder request() {
        return new NettyHttpClientRequestBuilder();
    }

    public static class NettyHttpClientRequestBuilder {
        private HttpMethod method;
        private String uri;
        private String payload;
        private HttpHeaders headers = new DefaultHttpHeaders();
        private Consumer<ChannelPipeline> pipelineAdjuster;

        public NettyHttpClientRequestBuilder withMethod(HttpMethod method) {
            this.method = method;
            return this;
        }

        public NettyHttpClientRequestBuilder withUri(String uri) {
            this.uri = uri;
            return this;
        }

        public NettyHttpClientRequestBuilder withPaylod(String payload) {
            this.payload = payload;
            return this;
        }

        public NettyHttpClientRequestBuilder withKeepAlive() {
            headers.set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
            return this;
        }

        public NettyHttpClientRequestBuilder withConnectionClose() {
            headers.set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
            return this;
        }

        public NettyHttpClientRequestBuilder withHeader(String key, Object value) {
            this.headers.set(key, value);
            return this;
        }

        public NettyHttpClientRequestBuilder withHeaders(Iterable<Pair<String, Object>> headers) {
            for (Pair<String, Object> header : headers) {
                withHeader(header.getKey(), header.getValue());
            }
            return this;
        }

        @SafeVarargs
        public final NettyHttpClientRequestBuilder withHeaders(Pair<String, Object>... headers) {
            return withHeaders(Arrays.asList(headers));
        }

        public NettyHttpClientRequestBuilder withPipelineAdjuster(Consumer<ChannelPipeline> pipelineAdjuster) {
            this.pipelineAdjuster = pipelineAdjuster;
            return this;
        }

        public FullHttpRequest build() {
            ByteBuf content;
            if (payload != null)
                content = Unpooled.copiedBuffer(payload, CharsetUtil.UTF_8);
            else
                content = Unpooled.buffer(0);

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, content);

            if (headers != null) 
                request.headers().set(headers);

            return request;
        }

        public NettyHttpClientResponse execute(int port, long incompleteCallTimeoutMillis) throws Exception {
            return executeRequest(build(), port, incompleteCallTimeoutMillis, pipelineAdjuster);
        }

        public NettyHttpClientResponse execute(Channel ch, long incompleteCallTimeoutMillis) throws InterruptedException, ExecutionException, TimeoutException {
            return executeNettyHttpClientCall(ch, build(), incompleteCallTimeoutMillis, pipelineAdjuster);
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

    public static String extractBodyFromRawRequestOrResponse(String rawRequestOrResponse) {
        return substringAfter(rawRequestOrResponse, "\r\n\r\n"); //body start after \r\n\r\n combo
    }

    public static String extractFullBodyFromChunks(String chunkedBody) {
        if (!chunkedBody.contains("\r\n")) {
            return chunkedBody;
        }

        // https://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6.1
        String[] chunksWithSizes = chunkedBody.split("\r\n");
        boolean nextChunkIsChunkSize = true;
        StringBuilder finalResultMinusChunkMetadata = new StringBuilder();
        for (String chunk : chunksWithSizes) {
            if (!nextChunkIsChunkSize) {
                // This is not metadata - it is actual body payload.
                finalResultMinusChunkMetadata.append(chunk);
            }

            // Toggle our "next is metadata" flag, as according to the RFC it should alternate between
            //      chunk-size and chunk-data.
            nextChunkIsChunkSize = !nextChunkIsChunkSize;
        }

        return finalResultMinusChunkMetadata.toString();
    }

    public static HttpHeaders extractHeadersFromRawRequestOrResponse(String rawRequestOrResponseString) {
        int indexOfFirstCrlf = rawRequestOrResponseString.indexOf("\r\n");
        int indexOfBodySeparator = rawRequestOrResponseString.indexOf("\r\n\r\n");

        if (indexOfFirstCrlf == -1 || indexOfBodySeparator == -1) {
            throw new IllegalArgumentException("The given rawRequestOrResponseString does not appear to be a valid HTTP message");
        }

        String concatHeaders = rawRequestOrResponseString.substring(indexOfFirstCrlf + "\r\n".length(), indexOfBodySeparator);

        HttpHeaders extractedHeaders = new DefaultHttpHeaders();

        for (String concatHeader : split(concatHeaders, "\r\n")) {
            extractedHeaders.add(substringBefore(concatHeader, HEADER_SEPARATOR).trim(), substringAfter(concatHeader, HEADER_SEPARATOR).trim());
        }

        return extractedHeaders;
    }

    public static Map<String, List<String>> headersToMap(HttpHeaders headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.names().forEach(headerKey -> result.put(headerKey, headers.getAll(headerKey)));
        return result;
    }
}
