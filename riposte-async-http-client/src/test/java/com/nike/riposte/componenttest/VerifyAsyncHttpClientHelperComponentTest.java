package com.nike.riposte.componenttest;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.exception.ApiException;
import com.nike.riposte.client.asynchttp.ning.AsyncHttpClientHelper;
import com.nike.riposte.client.asynchttp.ning.RequestBuilderWrapper;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.util.Matcher;
import com.nike.wingtips.Span;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;

import com.ning.http.client.Response;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;

import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component test that verifies essential {@link AsyncHttpClientHelper} functionality.
 *
 * @author Nic Munroe
 */
public class VerifyAsyncHttpClientHelperComponentTest {

    private static int serverPort;
    private static Server server;
    private static AppServerConfigForTesting serverConfig;
    private static final AsyncHttpClientHelper asyncClient = new AsyncHttpClientHelper();

    public static int findFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeClass
    public static void setup() throws Exception {
        serverPort = findFreePort();
        serverConfig = new AppServerConfigForTesting(singleton(new TestEndpoint()), serverPort);
        server = new Server(serverConfig);
        server.startup();
    }

    @AfterClass
    public static void teardown() throws Exception {
        server.shutdown();
    }

    private void resetTracingAndMdc() {
        MDC.clear();
        Tracer.getInstance().completeRequestSpan();
    }

    @Before
    public void beforeMethod() {
        resetTracingAndMdc();
    }

    @After
    public void afterMethod() {
        resetTracingAndMdc();
    }

    @Test
    public void verify_basic_functionality() throws Exception {
        // given
        RequestBuilderWrapper rbw = asyncClient.getRequestBuilder(
            "http://localhost:" + serverPort + TestEndpoint.MATCHING_PATH, HttpMethod.GET
        );
        rbw.requestBuilder.setHeader(TestEndpoint.EXPECTED_HEADER_KEY, TestEndpoint.EXPECTED_HEADER_VAL);
        rbw.requestBuilder.setBody(TestEndpoint.EXPECTED_REQUEST_PAYLOAD);

        Span origSpan = Tracer.getInstance().startRequestWithRootSpan("overallReqSpan");
        Deque<Span> distributedTraceStackForCall = Tracer.getInstance().getCurrentSpanStackCopy();
        Map<String, String> mdcContextForCall = MDC.getCopyOfContextMap();
        resetTracingAndMdc();

        // when
        Response result = asyncClient.executeAsyncHttpRequest(
            rbw, response -> response, distributedTraceStackForCall, mdcContextForCall
        ).join();

        // then
        assertThat(result.getStatusCode()).isEqualTo(200);
        assertThat(result.getResponseBody()).isEqualTo(TestEndpoint.RESPONSE_PAYLOAD);
        assertThat(result.getHeader(TraceHeaders.TRACE_ID)).isEqualTo(origSpan.getTraceId());
        // The async client should have surrounded the request in a subspan,
        //      so the parent ID sent to the downstream service should be the original span's span ID.
        assertThat(result.getHeader(TraceHeaders.PARENT_SPAN_ID)).isEqualTo(origSpan.getSpanId());
    }

    private static class TestEndpoint extends StandardEndpoint<String, String> {

        public static final String MATCHING_PATH = "/testEndpoint";
        public static final String EXPECTED_HEADER_KEY = "expected-header";
        public static final String EXPECTED_HEADER_VAL = UUID.randomUUID().toString();
        public static final String EXPECTED_REQUEST_PAYLOAD = UUID.randomUUID().toString();
        public static final String RESPONSE_PAYLOAD = UUID.randomUUID().toString();

        public static final ApiError MISSING_EXPECTED_REQ_PAYLOAD =
            new ApiErrorBase("MISSING_EXPECTED_REQ_PAYLOAD", 42, "Missing expected request payload", 400);
        public static final ApiError MISSING_EXPECTED_HEADER =
            new ApiErrorBase("MISSING_EXPECTED_HEADER", 42, "Missing expected header", 400);

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(
            RequestInfo<String> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx
        ) {
            if (!EXPECTED_REQUEST_PAYLOAD.equals(request.getContent()))
                throw new ApiException(MISSING_EXPECTED_REQ_PAYLOAD);

            if (!EXPECTED_HEADER_VAL.equals(request.getHeaders().get(EXPECTED_HEADER_KEY)))
                throw new ApiException(MISSING_EXPECTED_HEADER);

            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(RESPONSE_PAYLOAD)
                            .withHeaders(
                                new DefaultHttpHeaders()
                                    .set(TraceHeaders.TRACE_ID, request.getHeaders().get(TraceHeaders.TRACE_ID))
                                    .set(TraceHeaders.PARENT_SPAN_ID,
                                         request.getHeaders().get(TraceHeaders.PARENT_SPAN_ID))
                            )
                            .build()
            );
        }
    }

    private static class AppServerConfigForTesting implements ServerConfig {

        private final Collection<Endpoint<?>> endpoints;
        private final int port;

        private AppServerConfigForTesting(Collection<Endpoint<?>> endpoints, int port) {
            this.endpoints = endpoints;
            this.port = port;
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
