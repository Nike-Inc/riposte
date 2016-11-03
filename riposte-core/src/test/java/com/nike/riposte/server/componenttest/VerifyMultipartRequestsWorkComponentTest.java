package com.nike.riposte.server.componenttest;

import com.nike.internal.util.StringUtils;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;

import com.google.common.base.Charsets;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.CharsetUtil;

import static com.jayway.restassured.RestAssured.given;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

public class VerifyMultipartRequestsWorkComponentTest {

    private static Server server;
    private static ServerConfig serverConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        serverConfig = new MultipartTestConfig();
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void verify_multipart_file_works_properly() throws IOException, InterruptedException {
        String name = "someImageFile";
        String filename = "helloWorld.png";
        InputStream multipartFileInputStream = VerifyMultipartRequestsWorkComponentTest.class.getClassLoader().getResourceAsStream(filename);
        byte[] multipartFileBytes = IOUtils.toByteArray(multipartFileInputStream);

        String responseString =
                    given()
                        .baseUri("http://127.0.0.1")
                        .port(serverConfig.endpointsPort())
                        .basePath(MultipartTestEndpoint.MATCHING_PATH)
                        .log().all()
                    .when()
                        .multiPart(name, filename, multipartFileBytes, "image/png")
                        .post()
                    .then()
                        .log().all()
                        .statusCode(200)
                        .extract().asString();

        String expectedHash = getHashForMultipartPayload(name, filename, multipartFileBytes);
        assertThat(responseString).isEqualTo(expectedHash);
    }

    @Test
    public void verify_multipart_attribute_works_properly() throws IOException, InterruptedException {
        String name = "someAttribute";

        String uuidString = UUID.randomUUID().toString();
        String responseString =
                    given()
                        .baseUri("http://127.0.0.1")
                        .port(serverConfig.endpointsPort())
                        .basePath(MultipartTestEndpoint.MATCHING_PATH)
                        .log().all()
                    .when()
                        .multiPart(name, uuidString)
                        .post()
                    .then()
                        .log().all()
                        .statusCode(200)
                        .extract().asString();

        String expectedHash = getHashForMultipartPayload(name, null, uuidString.getBytes(CharsetUtil.UTF_8));
        assertThat(responseString).isEqualTo(expectedHash);
    }

    @Test
    public void verify_multipart_with_mixed_types_works_properly() throws IOException, InterruptedException {
        String imageName = "someImageFile";
        String imageFilename = "helloWorld.png";
        InputStream imageFileInputStream = VerifyMultipartRequestsWorkComponentTest.class.getClassLoader().getResourceAsStream(imageFilename);
        byte[] imageFileBytes = IOUtils.toByteArray(imageFileInputStream);

        String textName = "someTextFile";
        String textFilename = "testMultipartFile.txt";
        InputStream textFileInputStream = VerifyMultipartRequestsWorkComponentTest.class.getClassLoader().getResourceAsStream(textFilename);
        byte[] textFileBytes = IOUtils.toByteArray(textFileInputStream);

        String attributeName = "someAttribute";
        String attributeString = UUID.randomUUID().toString();

        String responseString =
                    given()
                        .baseUri("http://127.0.0.1")
                        .port(serverConfig.endpointsPort())
                        .basePath(MultipartTestEndpoint.MATCHING_PATH)
                        .log().all()
                    .when()
                        .multiPart(imageName, imageFilename, imageFileBytes, "image/png")
                        .multiPart(attributeName, attributeString)
                        .multiPart(textName, textFilename, textFileBytes)
                        .post()
                    .then()
                        .log().all()
                        .statusCode(200)
                        .extract().asString();

        String expectedImageFileHash = getHashForMultipartPayload(imageName, imageFilename, imageFileBytes);
        String expectedAttributeHash = getHashForMultipartPayload(attributeName, null, attributeString.getBytes(CharsetUtil.UTF_8));
        String expectedTextFileHash = getHashForMultipartPayload(textName, textFilename, textFileBytes);
        String expectedResponse = StringUtils.join(Arrays.asList(expectedImageFileHash, expectedAttributeHash, expectedTextFileHash), ",");
        assertThat(responseString).isEqualTo(expectedResponse);
    }

    private static final HashFunction hashFunction = Hashing.md5();
    private static String getHashForMultipartPayload(String name, String filename, byte[] payloadBytes) {
        Hasher hasher = hashFunction.newHasher()
                                    .putString(name, Charsets.UTF_8);
        if (filename != null)
            hasher = hasher.putString(filename, Charsets.UTF_8);

        hasher = hasher.putBytes(payloadBytes);

        HashCode hc = hasher.hash();
        return hc.toString();
    }

    public static class MultipartTestConfig implements ServerConfig {
        private final Collection<Endpoint<?>> endpoints = singleton(new MultipartTestEndpoint());
        private final int port;

        public MultipartTestConfig() {
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

    public static class MultipartTestEndpoint extends StandardEndpoint<String, String> {

        public static String MATCHING_PATH = "/multipart";

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<String> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            List<String> hashesFound = new ArrayList<>();

            for (InterfaceHttpData multipartData : request.getMultipartParts()) {
                String name = multipartData.getName();
                byte[] payloadBytes;
                try {
                    payloadBytes = ((HttpData)multipartData).get();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                String filename = null;
                switch (multipartData.getHttpDataType()) {
                    case Attribute:
                        // Do nothing - filename stays null
                        break;
                    case FileUpload:
                        filename = ((FileUpload)multipartData).getFilename();
                        break;
                    default:
                        throw new RuntimeException("Unsupported multipart type: " + multipartData.getHttpDataType().name());
                }

                hashesFound.add(getHashForMultipartPayload(name, filename, payloadBytes));
            }

            return CompletableFuture.completedFuture(ResponseInfo.newBuilder(StringUtils.join(hashesFound, ",")).build());
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }

    }
}
