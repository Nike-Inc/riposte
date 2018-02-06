package com.nike.riposte.server.componenttest;

import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.server.testutils.ComponentTestUtils.CompressionType;
import com.nike.riposte.server.testutils.ComponentTestUtils.NettyHttpClientRequestBuilder;
import com.nike.riposte.server.testutils.ComponentTestUtils.NettyHttpClientResponse;
import com.nike.riposte.util.Matcher;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import static com.nike.riposte.server.testutils.ComponentTestUtils.generatePayload;
import static com.nike.riposte.server.testutils.ComponentTestUtils.request;
import static io.netty.handler.codec.http.HttpHeaders.Names.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_ENCODING;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component test verifying the functionality of {@link com.nike.riposte.server.handler.SmartHttpContentCompressor}.
 */
@RunWith(DataProviderRunner.class)
public class VerifySmartHttpContentCompressorComponentTest {

    private static Server server;
    private static ServerConfig serverConfig;
    private int incompleteCallTimeoutMillis = 2000;

    @BeforeClass
    public static void setUpClass() throws Exception {
        serverConfig = new ResponsePayloadCompressionServerConfig();
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
    }

    @DataProvider(value = {
        "GZIP       |   499 |   false",
        "DEFLATE    |   499 |   false",
        "IDENTITY   |   499 |   false",
        "GZIP       |   500 |   false",
        "DEFLATE    |   500 |   false",
        "IDENTITY   |   500 |   false",
        "GZIP       |   501 |   true",
        "DEFLATE    |   501 |   true",
        "IDENTITY   |   501 |   false"
    }, splitBy = "\\|")
    @Test
    public void response_should_be_compressed_based_on_payload_size_and_accept_encoding_header(
        CompressionType compressionType, int desiredUncompressedPayloadSize, boolean expectCompressed
    ) throws Exception {
        // given
        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.GET)
            .withUri(BasicEndpoint.MATCHING_PATH)
            .withHeader(ACCEPT_ENCODING, compressionType.contentEncodingHeaderValue)
            .withHeader(BasicEndpoint.DESIRED_UNCOMPRESSED_PAYLOAD_SIZE_HEADER_KEY, desiredUncompressedPayloadSize);

        // when
        NettyHttpClientResponse serverResponse = request.execute(serverConfig.endpointsPort(),
                                                                incompleteCallTimeoutMillis);

        // then
        assertThat(serverResponse.statusCode).isEqualTo(HttpResponseStatus.OK.code());

        String contentEncodingHeader = serverResponse.headers.get(CONTENT_ENCODING);
        String decompressedPayload;

        if (expectCompressed) {
            assertThat(contentEncodingHeader).isEqualTo(compressionType.contentEncodingHeaderValue);
            decompressedPayload = compressionType.decompress(serverResponse.payloadBytes);
        }
        else {
            assertThat(contentEncodingHeader).isNull();
            decompressedPayload = serverResponse.payload;
        }

        assertThat(decompressedPayload).hasSize(desiredUncompressedPayloadSize);
        assertThat(decompressedPayload).startsWith(BasicEndpoint.RESPONSE_PAYLOAD_PREFIX);

    }

    @DataProvider(value = {
        "GZIP",
        "DEFLATE",
        "IDENTITY"
    }, splitBy = "\\|")
    @Test
    public void response_should_not_be_compressed_when_ResponseInfo_disables_compression(
        CompressionType compressionType
    ) throws Exception {
        // given
        NettyHttpClientRequestBuilder request = request()
            .withMethod(HttpMethod.GET)
            .withUri(BasicEndpoint.MATCHING_PATH)
            .withHeader(ACCEPT_ENCODING, compressionType.contentEncodingHeaderValue)
            .withHeader(BasicEndpoint.DESIRED_UNCOMPRESSED_PAYLOAD_SIZE_HEADER_KEY, 1000)
            .withHeader(BasicEndpoint.DISABLE_COMPRESSION_HEADER_KEY, "true");

        // when
        NettyHttpClientResponse serverResponse = request.execute(serverConfig.endpointsPort(),
                                                                 incompleteCallTimeoutMillis);

        // then
        assertThat(serverResponse.statusCode).isEqualTo(HttpResponseStatus.OK.code());

        assertThat(serverResponse.headers.get(CONTENT_ENCODING)).isNull();

        assertThat(serverResponse.payload).hasSize(1000);
        assertThat(serverResponse.payload).startsWith(BasicEndpoint.RESPONSE_PAYLOAD_PREFIX);

    }

    private static String generatePayloadOfSizeInBytes(String prefix, int length) {
        return prefix + generatePayload(length - prefix.length());
    }

    private static class BasicEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/basicEndpoint";
        public static final String RESPONSE_PAYLOAD_PREFIX = "basic-endpoint-" + UUID.randomUUID().toString();
        public static final String DESIRED_UNCOMPRESSED_PAYLOAD_SIZE_HEADER_KEY = "desired-uncompressed-payload-size";
        public static final String DISABLE_COMPRESSION_HEADER_KEY = "disable-compression";

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            String responsePayload = generatePayloadOfSizeInBytes(
                RESPONSE_PAYLOAD_PREFIX,
                Integer.parseInt(request.getHeaders().get(DESIRED_UNCOMPRESSED_PAYLOAD_SIZE_HEADER_KEY))
            );
            boolean disableCompression = "true".equals(request.getHeaders().get(DISABLE_COMPRESSION_HEADER_KEY));
            
            return CompletableFuture.completedFuture(
                    ResponseInfo.newBuilder(responsePayload)
                                .withPreventCompressedOutput(disableCompression)
                                .build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }

    public static class ResponsePayloadCompressionServerConfig implements ServerConfig {
        private final Collection<Endpoint<?>> endpoints = singletonList(new BasicEndpoint());

        private final int port;

        public ResponsePayloadCompressionServerConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }
    }

}
