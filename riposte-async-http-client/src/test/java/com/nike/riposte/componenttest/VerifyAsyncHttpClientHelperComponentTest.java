package com.nike.riposte.componenttest;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorBase;
import com.nike.backstopper.exception.ApiException;
import com.nike.riposte.client.asynchttp.ning.AsyncHttpClientHelper;
import com.nike.riposte.client.asynchttp.ning.RequestBuilderWrapper;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.util.Matcher;
import com.nike.wingtips.Span;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.lifecyclelistener.SpanLifecycleListener;
import com.nike.wingtips.tags.KnownZipkinTags;
import com.nike.wingtips.tags.WingtipsTags;

import com.ning.http.client.Response;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;

import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Component test that verifies essential {@link AsyncHttpClientHelper} functionality.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class VerifyAsyncHttpClientHelperComponentTest {

    private static int serverPort;
    private static Server server;
    private static AppServerConfigForTesting serverConfig;

    private SpanRecorder spanRecorder;

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

    private void resetTracing() {
        MDC.clear();
        Tracer.getInstance().unregisterFromThread();
        removeSpanRecorderLifecycleListener();
        spanRecorder = new SpanRecorder();
        Tracer.getInstance().addSpanLifecycleListener(spanRecorder);
    }

    private void removeSpanRecorderLifecycleListener() {
        List<SpanLifecycleListener> listeners = new ArrayList<>(Tracer.getInstance().getSpanLifecycleListeners());
        for (SpanLifecycleListener listener : listeners) {
            if (listener instanceof SpanRecorder) {
                Tracer.getInstance().removeSpanLifecycleListener(listener);
            }
        }
    }

    @Before
    public void beforeMethod() {
        resetTracing();
    }

    @After
    public void afterMethod() {
        resetTracing();
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true",
        "false  |   false"
    }, splitBy = "\\|")
    @Test
    public void verify_basic_functionality(boolean surroundWithSubspan, boolean parentSpanExists) throws Exception {
        // given
        AsyncHttpClientHelper asyncClient = new AsyncHttpClientHelper(surroundWithSubspan);

        String fullUrl = "http://localhost:" + serverPort + TestEndpoint.MATCHING_PATH + "?foo=bar";
        RequestBuilderWrapper rbw = asyncClient.getRequestBuilder(fullUrl, HttpMethod.GET);
        rbw.requestBuilder.setHeader(TestEndpoint.EXPECTED_HEADER_KEY, TestEndpoint.EXPECTED_HEADER_VAL);
        rbw.requestBuilder.setBody(TestEndpoint.EXPECTED_REQUEST_PAYLOAD);

        Deque<Span> distributedTraceStackForCall = null;
        Map<String, String> mdcContextForCall = null;
        Span origSpan = null;

        if (parentSpanExists) {
            origSpan = Tracer.getInstance().startRequestWithRootSpan("overallReqSpan");
            distributedTraceStackForCall = Tracer.getInstance().getCurrentSpanStackCopy();
            mdcContextForCall = MDC.getCopyOfContextMap();
            resetTracing();
        }

        // when
        Response result = asyncClient.executeAsyncHttpRequest(
            rbw, response -> response, distributedTraceStackForCall, mdcContextForCall
        ).join();

        // then
        Span subspan = findSubspan();

        assertThat(result.getStatusCode()).isEqualTo(200);
        assertThat(result.getResponseBody()).isEqualTo(TestEndpoint.RESPONSE_PAYLOAD);

        if (surroundWithSubspan) {
            assertThat(subspan).isNotNull();
            assertThat(result.getHeader(TraceHeaders.TRACE_ID)).isEqualTo(subspan.getTraceId());
            assertThat(result.getHeader(TraceHeaders.SPAN_ID)).isEqualTo(subspan.getSpanId());
            assertThat(result.getHeader(TraceHeaders.PARENT_SPAN_ID)).isEqualTo(
                String.valueOf(subspan.getParentSpanId())
            );
            verifySubspanTags(subspan, fullUrl, "200", false);
        }
        else {
            assertThat(subspan).isNull();
        }

        if (parentSpanExists) {
            assertThat(result.getHeader(TraceHeaders.TRACE_ID)).isEqualTo(origSpan.getTraceId());
            String expectedParentSpanId = (surroundWithSubspan) ? subspan.getParentSpanId() : "null";
            assertThat(result.getHeader(TraceHeaders.PARENT_SPAN_ID)).isEqualTo(expectedParentSpanId);
            String expectedSpanId = (surroundWithSubspan) ? subspan.getSpanId() : origSpan.getSpanId();
            assertThat(result.getHeader(TraceHeaders.SPAN_ID)).isEqualTo(expectedSpanId);
        }

        if (!parentSpanExists && !surroundWithSubspan) {
            assertThat(result.getHeader(TraceHeaders.TRACE_ID)).isEqualTo("null");
            assertThat(result.getHeader(TraceHeaders.SPAN_ID)).isEqualTo("null");
            assertThat(result.getHeader(TraceHeaders.PARENT_SPAN_ID)).isEqualTo("null");
        }
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true",
        "false  |   false"
    }, splitBy = "\\|")
    @Test
    public void verify_tags_when_an_error_occurs_preventing_execution(
        boolean surroundWithSubspan, boolean parentSpanExists
    ) {
        // given
        AsyncHttpClientHelper asyncClient = new AsyncHttpClientHelper(surroundWithSubspan);

        // The server is not listening on HTTPS, just HTTP, so trying to hit HTTPS will result in an exception.
        String fullUrl = "https://localhost:" + serverPort + TestEndpoint.MATCHING_PATH + "?foo=bar";
        RequestBuilderWrapper rbw = asyncClient.getRequestBuilder(fullUrl, HttpMethod.GET);
        rbw.requestBuilder.setHeader(TestEndpoint.EXPECTED_HEADER_KEY, TestEndpoint.EXPECTED_HEADER_VAL);
        rbw.requestBuilder.setBody(TestEndpoint.EXPECTED_REQUEST_PAYLOAD);

        Deque<Span> distributedTraceStackForCall = null;
        Map<String, String> mdcContextForCall = null;
        Span origSpan = null;

        if (parentSpanExists) {
            origSpan = Tracer.getInstance().startRequestWithRootSpan("overallReqSpan");
            distributedTraceStackForCall = Tracer.getInstance().getCurrentSpanStackCopy();
            mdcContextForCall = MDC.getCopyOfContextMap();
            resetTracing();
        }

        final Deque<Span> finalDistributedTraceStackForCall = distributedTraceStackForCall;
        final Map<String, String> finalMdcContextForCall = mdcContextForCall;

        // when
        Throwable ex = catchThrowable(
            () -> asyncClient.executeAsyncHttpRequest(
                rbw, response -> response, finalDistributedTraceStackForCall, finalMdcContextForCall
            ).join()
        );

        // then
        assertThat(ex)
            .isInstanceOf(CompletionException.class)
            .hasCauseInstanceOf(ConnectException.class);

        Span subspan = findSubspan();

        if (surroundWithSubspan) {
            if (parentSpanExists) {
                assertThat(subspan.getTraceId()).isEqualTo(origSpan.getTraceId());
                assertThat(subspan.getParentSpanId()).isEqualTo(origSpan.getSpanId());
            }
            else {
                assertThat(subspan.getParentSpanId()).isNull();
            }

            verifySubspanTags(subspan, fullUrl, null, true);
        }
        else {
            assertThat(subspan).isNull();
        }
    }

    private void verifySubspanTags(
        Span subspan, String expectedFullUrl, String expectedHttpStatusCode, boolean expectError
    ) {
        assertThat(subspan.getTags().get(KnownZipkinTags.HTTP_METHOD)).isEqualTo("GET");
        assertThat(subspan.getTags().get(KnownZipkinTags.HTTP_PATH)).isEqualTo(TestEndpoint.MATCHING_PATH);
        assertThat(subspan.getTags().get(KnownZipkinTags.HTTP_URL)).isEqualTo(expectedFullUrl);
        assertThat(subspan.getTags().get(KnownZipkinTags.HTTP_STATUS_CODE)).isEqualTo(expectedHttpStatusCode);
        assertThat(subspan.getTags().get(WingtipsTags.SPAN_HANDLER)).isEqualTo("riposte.ningasynchttpclienthelper");

        if (expectError) {
            assertThat(subspan.getTags().get(KnownZipkinTags.ERROR)).isNotNull();
        }
        else {
            assertThat(subspan.getTags().get(KnownZipkinTags.ERROR)).isNull();
        }

        // Either there's a status code tag but no error tag, or an error tag but no status code tag. In either
        //      case we expect 5 tags.
        assertThat(subspan.getTags()).hasSize(5);
    }

    private Span findSubspan() {
        return spanRecorder.completedSpans.stream().filter(
            s -> "riposte.ningasynchttpclienthelper".equals(s.getTags().get(WingtipsTags.SPAN_HANDLER))
        ).findFirst().orElse(null);
    }

    private static class TestEndpoint extends StandardEndpoint<String, String> {

        public static final String MATCHING_PATH = "/some/testEndpoint";
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
                                    .set(TraceHeaders.TRACE_ID,
                                         String.valueOf(request.getHeaders().get(TraceHeaders.TRACE_ID))
                                    )
                                    .set(TraceHeaders.SPAN_ID,
                                         String.valueOf(request.getHeaders().get(TraceHeaders.SPAN_ID))
                                    )
                                    .set(TraceHeaders.PARENT_SPAN_ID,
                                         String.valueOf(request.getHeaders().get(TraceHeaders.PARENT_SPAN_ID))
                                    )
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

    private static class SpanRecorder implements SpanLifecycleListener {

        public final List<Span> completedSpans = new ArrayList<>();

        @Override
        public void spanStarted(Span span) {
        }

        @Override
        public void spanSampled(Span span) {
        }

        @Override
        public void spanCompleted(Span span) {
            completedSpans.add(span);
        }
    }
}
