package com.nike.riposte.metrics;

import com.nike.riposte.metrics.codahale.CodahaleMetricsEngine;
import com.nike.riposte.metrics.codahale.CodahaleMetricsListener;
import com.nike.riposte.metrics.codahale.contrib.DefaultSLF4jReporterFactory;
import com.nike.riposte.metrics.codahale.contrib.RiposteGraphiteReporterFactory;
import com.nike.riposte.metrics.codahale.impl.EndpointMetricsHandlerDefaultImpl;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.AppInfo;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.config.impl.AppInfoImpl;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.netty.channel.ChannelHandlerContext;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static java.util.Collections.singleton;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MetricsComponentTest {

    private static final Logger logger = LoggerFactory.getLogger(MetricsComponentTest.class);
    public static String NON_BLOCKING_MATCHING_PATH = "/testAsync";
    public static String NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY = "ALL-[" + NON_BLOCKING_MATCHING_PATH + "]";
    public final int NUMBER_OF_REQUESTS = 2;
    private Server server;
    private static final long SLEEP_TIME_MILLIS = 10;
    private Set<String> nettyWorkerThreadsUsed = new HashSet<>();
    private Set<String> executorThreadsUsed = new HashSet<>();
    private ExecutorService executor;
    private ServerConfig serverConfig;
    private CodahaleMetricsListener metricsListener;
    private EndpointMetricsHandlerDefaultImpl endpointMetricsHandler;
    private ObjectMapper mapper = new ObjectMapper();
    private String nonblockingEndpointUrl;

    @Before
    public void setUp() throws Exception {
        executor = Executors.newCachedThreadPool();

        serverConfig = new MetricsTestConfig();
        metricsListener = (CodahaleMetricsListener) serverConfig.metricsListener();
        endpointMetricsHandler = (EndpointMetricsHandlerDefaultImpl) metricsListener.getEndpointMetricsHandler();
        assertTrue(serverConfig.endpointsPort() > 0);
        logger.info("Dynamically chose server port {}", serverConfig.endpointsPort());
        nonblockingEndpointUrl = "http://127.0.0.1:" + serverConfig.endpointsPort() + NON_BLOCKING_MATCHING_PATH;
        server = new Server(serverConfig);
        server.startup();

        // Perform a "warmup" to get the Server ready to go - the first call is always slower than normal.
        List<Future<?>> warmupCalls = new ArrayList<>();
        warmupCalls.add(sendServerRequest(nonblockingEndpointUrl));
        for (Future f : warmupCalls) {
            f.get();
        }
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
        executor.shutdown();
    }

    protected void clearDataForNewTest() {
        nettyWorkerThreadsUsed.clear();
        executorThreadsUsed.clear();
    }

    @Test
    public void serverShouldProcessNonblockingEndpointsAsynchronouslyReturns400() throws Exception {
        logger.warn("serverShouldProcessNonblockingEndpointsAsynchronouslyReturns400 start " + new Date().toString());
        long prevFailedCount = metricsListener.getFailedRequests().getCount();
        long prevProcessedCount = metricsListener.getProcessedRequests().getCount();
        long prevRequestsCount = endpointMetricsHandler.getRequests().getCount();
        long prevGetRequestsCount = endpointMetricsHandler.getGetRequests().getCount();
        long prevResponsesCount = endpointMetricsHandler.getResponses()[4 - 1].getCount();
        long prevRequestCount = ((Timer) endpointMetricsHandler.getEndpointRequestsTimers()
                                                            .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))
            .getCount();
        long prevResponseCount = ((Meter[]) endpointMetricsHandler.getEndpointResponsesMeters()
                                                               .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))[4
                                                                                                                     - 1]
            .getCount();

        doServerAsyncProcessingVerificationTestForUrl(nonblockingEndpointUrl + "?error=400", true);

        long countFailed = metricsListener.getFailedRequests().getCount();
        long processedCount = metricsListener.getProcessedRequests().getCount();
        long requestsCount = endpointMetricsHandler.getRequests().getCount();
        long requestSize = metricsListener.getRequestSizes().getSnapshot().getMax();
        long responseSize = metricsListener.getResponseSizes().getSnapshot().getMax();
        long getRequestsCount = endpointMetricsHandler.getGetRequests().getCount();
        long responsesCount = endpointMetricsHandler.getResponses()[4 - 1].getCount();
        long requestCount = ((Timer) endpointMetricsHandler.getEndpointRequestsTimers()
                                                        .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))
            .getCount();
        long responseCount = ((Meter[]) endpointMetricsHandler.getEndpointResponsesMeters()
                                                           .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))[4 - 1]
            .getCount();

        long inflight = metricsListener.getInflightRequests().getCount();
        long responseWriteFailed = metricsListener.getResponseWriteFailed().getCount();
        long postRequests = endpointMetricsHandler.getPostRequests().getCount();
        long putRequests = endpointMetricsHandler.getPutRequests().getCount();
        long deleteRequests = endpointMetricsHandler.getDeleteRequests().getCount();
        long otherRequests = endpointMetricsHandler.getOtherRequests().getCount();

        assertTrue(countFailed > prevFailedCount);// + NUMBER_OF_REQUESTS);
        assertTrue(processedCount > prevProcessedCount);// + NUMBER_OF_REQUESTS);
        assertTrue(requestsCount > prevRequestsCount);// + NUMBER_OF_REQUESTS);
        assertTrue(requestSize == 0);
        assertTrue(responseSize < 200);
        assertTrue(getRequestsCount > prevGetRequestsCount);// + NUMBER_OF_REQUESTS);
        assertTrue(responsesCount > prevResponsesCount);// + NUMBER_OF_REQUESTS);
        assertTrue(requestCount > prevRequestCount);// + NUMBER_OF_REQUESTS);
        assertTrue(responseCount > prevResponseCount);// + NUMBER_OF_REQUESTS);
        assertEquals(inflight, 0);
        assertEquals(responseWriteFailed, 0);
        assertEquals(postRequests, 0);
        assertEquals(putRequests, 0);
        assertEquals(deleteRequests, 0);
        assertEquals(otherRequests, 0);
        logger.warn("serverShouldProcessNonblockingEndpointsAsynchronouslyReturns400 end " + new Date().toString());
    }

    @Test
    public void serverShouldProcessNonblockingEndpointsAsynchronouslyReturns500() throws Exception {
        logger.warn("serverShouldProcessNonblockingEndpointsAsynchronouslyReturns500 start " + new Date().toString());
        CodahaleMetricsListener metricsListener = (CodahaleMetricsListener) serverConfig.metricsListener();
        long prevFailedCount = metricsListener.getFailedRequests().getCount();
        long prevProcessedCount = metricsListener.getProcessedRequests().getCount();
        long prevRequestsCount = endpointMetricsHandler.getRequests().getCount();
        long prevGetRequestsCount = endpointMetricsHandler.getGetRequests().getCount();
        long prevResponsesCount = endpointMetricsHandler.getResponses()[5 - 1].getCount();
        long prevRequestCount = ((Timer) endpointMetricsHandler.getEndpointRequestsTimers()
                                                            .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))
            .getCount();
        long prevResponseCount = ((Meter[]) endpointMetricsHandler.getEndpointResponsesMeters()
                                                               .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))[5
                                                                                                                     - 1]
            .getCount();

        doServerAsyncProcessingVerificationTestForUrl(nonblockingEndpointUrl + "?error=500", true);

        long countFailed = metricsListener.getFailedRequests().getCount();
        long processedCount = metricsListener.getProcessedRequests().getCount();
        long requestsCount = endpointMetricsHandler.getRequests().getCount();
        long requestSize = metricsListener.getRequestSizes().getSnapshot().getMax();
        long responseSize = metricsListener.getResponseSizes().getSnapshot().getMax();
        long getRequestsCount = endpointMetricsHandler.getGetRequests().getCount();
        long responsesCount = endpointMetricsHandler.getResponses()[5 - 1].getCount();
        long requestCount = ((Timer) endpointMetricsHandler.getEndpointRequestsTimers()
                                                        .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))
            .getCount();
        long responseCount = ((Meter[]) endpointMetricsHandler.getEndpointResponsesMeters()
                                                           .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))[5 - 1]
            .getCount();

        long inflight = metricsListener.getInflightRequests().getCount();
        long responseWriteFailed = metricsListener.getResponseWriteFailed().getCount();
        long postRequests = endpointMetricsHandler.getPostRequests().getCount();
        long putRequests = endpointMetricsHandler.getPutRequests().getCount();
        long deleteRequests = endpointMetricsHandler.getDeleteRequests().getCount();
        long otherRequests = endpointMetricsHandler.getOtherRequests().getCount();

        assertTrue(countFailed > prevFailedCount);// + NUMBER_OF_REQUESTS);
        assertTrue(processedCount > prevProcessedCount);// + NUMBER_OF_REQUESTS);
        assertTrue(requestsCount > prevRequestsCount);// + NUMBER_OF_REQUESTS);
        assertTrue(requestSize == 0);
        assertTrue(responseSize < 200);
        assertTrue(getRequestsCount > prevGetRequestsCount);// + NUMBER_OF_REQUESTS);
        assertTrue(responsesCount > prevResponsesCount);// + NUMBER_OF_REQUESTS);
        assertTrue(requestCount > prevRequestCount);// + NUMBER_OF_REQUESTS);
        assertTrue(responseCount > prevResponseCount);// + NUMBER_OF_REQUESTS);
        assertEquals(inflight, 0);
        assertEquals(responseWriteFailed, 0);
        assertEquals(postRequests, 0);
        assertEquals(putRequests, 0);
        assertEquals(deleteRequests, 0);
        assertEquals(otherRequests, 0);
        logger.warn("serverShouldProcessNonblockingEndpointsAsynchronouslyReturns500 end " + new Date().toString());
    }

    @Test
    public void serverShouldProcessNonblockingEndpointsAsynchronously() throws Exception {
        logger.warn("serverShouldProcessNonblockingEndpointsAsynchronously start " + new Date().toString());
        CodahaleMetricsListener metricsListener = (CodahaleMetricsListener) serverConfig.metricsListener();
        long prevFailedCount = metricsListener.getFailedRequests().getCount();
        long prevProcessedCount = metricsListener.getProcessedRequests().getCount();
        long prevRequestsCount = endpointMetricsHandler.getRequests().getCount();
        long prevGetRequestsCount = endpointMetricsHandler.getGetRequests().getCount();
        long prevResponsesCount = endpointMetricsHandler.getResponses()[4 - 1].getCount();
        long prevRequestCount = ((Timer) endpointMetricsHandler.getEndpointRequestsTimers()
                                                            .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))
            .getCount();
        long prevResponseCount = ((Meter[]) endpointMetricsHandler.getEndpointResponsesMeters()
                                                               .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))[4
                                                                                                                     - 1]
            .getCount();

        doServerAsyncProcessingVerificationTestForUrl(nonblockingEndpointUrl, true);

        long countFailed = metricsListener.getFailedRequests().getCount();
        long processedCount = metricsListener.getProcessedRequests().getCount();
        long requestsCount = endpointMetricsHandler.getRequests().getCount();
        long requestSize = metricsListener.getRequestSizes().getSnapshot().getMax();
        long responseSize = metricsListener.getResponseSizes().getSnapshot().getMax();
        long getRequestsCount = endpointMetricsHandler.getGetRequests().getCount();
        long responsesCount = endpointMetricsHandler.getResponses()[4 - 1].getCount();
        long requestCount = ((Timer) endpointMetricsHandler.getEndpointRequestsTimers()
                                                        .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))
            .getCount();
        long responseCount = ((Meter[]) endpointMetricsHandler.getEndpointResponsesMeters()
                                                           .get(NON_BLOCKING_ENDPOINT_METRICS_LISTENER_MAP_KEY))[4 - 1]
            .getCount();

        long inflight = metricsListener.getInflightRequests().getCount();
        long responseWriteFailed = metricsListener.getResponseWriteFailed().getCount();
        long postRequests = endpointMetricsHandler.getPostRequests().getCount();
        long putRequests = endpointMetricsHandler.getPutRequests().getCount();
        long deleteRequests = endpointMetricsHandler.getDeleteRequests().getCount();
        long otherRequests = endpointMetricsHandler.getOtherRequests().getCount();

        assertEquals(countFailed, prevFailedCount);
        assertTrue(processedCount > prevProcessedCount);// + NUMBER_OF_REQUESTS);
        assertTrue(requestsCount > prevRequestsCount);// + NUMBER_OF_REQUESTS);
        assertTrue(requestSize == 0);
        assertTrue(responseSize < 200);
        assertTrue(getRequestsCount > prevGetRequestsCount);// + NUMBER_OF_REQUESTS);
        assertEquals(responsesCount, prevResponsesCount);
        assertTrue(requestCount > prevRequestCount);// + NUMBER_OF_REQUESTS);
        assertEquals(responseCount, prevResponseCount);
        assertEquals(inflight, 0);
        assertEquals(responseWriteFailed, 0);
        assertEquals(postRequests, 0);
        assertEquals(putRequests, 0);
        assertEquals(deleteRequests, 0);
        assertEquals(otherRequests, 0);
        logger.warn("serverShouldProcessNonblockingEndpointsAsynchronously end " + new Date().toString());
    }

    protected void doServerAsyncProcessingVerificationTestForUrl(String endpointUrl,
                                                                 boolean expectNettyWorkerThreadToBeDifferentThanExecutor)
        throws ExecutionException, InterruptedException {
        // Clear the data and run the test
        clearDataForNewTest();

        List<Future<Map<String, String>>> futures = new ArrayList<>();
        int numSimultaneousCalls = NUMBER_OF_REQUESTS;
        for (int i = 0; i < numSimultaneousCalls; i++) {
            futures.add(sendServerRequest(endpointUrl));
        }

        // Wait for all the requests to return
        List<Map<String, String>> results = new ArrayList<>();
        for (Future<Map<String, String>> f : futures) {
            results.add(f.get());
        }

        // We need to sleep for a short time to give the metrics handler a chance to process the request before we start asserting on the metrics values.
        Thread.sleep(250);
    }

    protected Future<Map<String, String>> sendServerRequest(String endpointUrl) {
        return executor.submit(() -> {
            long start = System.currentTimeMillis();
            ExtractableResponse<Response> response1 =
                given()
                    .when()
                    .get(endpointUrl)
                    .then()
                    .extract();
            long end = System.currentTimeMillis();
            String s = response1.asString();

            logger.info("********* server call to {} returned {} in {} millis *************", endpointUrl, s,
                        (end - start));
            int statusCode = response1.statusCode();
            int expectedStatusCode = 200;
            if (endpointUrl.contains("?error="))
                expectedStatusCode = Integer.parseInt(endpointUrl.substring(endpointUrl.indexOf("?error=") + 7));
            assertEquals(expectedStatusCode, statusCode);
            return mapper.readValue(s, Map.class);
        });

    }

    public class MetricsTestConfig implements ServerConfig {

        private final int port;
        private final CodahaleMetricsListener metricsListener;
        private final Collection<Endpoint<?>> endpoints = singleton(new TestNonblockingEndpoint());

        public MetricsTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            }
            catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            CodahaleMetricsEngine metricsEngine = new CodahaleMetricsEngine()
                .addReporter(new DefaultSLF4jReporterFactory())
                .addReporter(new RiposteGraphiteReporterFactory("test.metrics.stuff", "doesnotexist.nikecloud.com", 2003))
                .reportJvmMetrics()
                .start();
            metricsListener = new CodahaleMetricsListener(metricsEngine.getMetricsCollector());
            metricsListener.initEndpointAndServerConfigMetrics(this);
        }

        @Override
        public @NotNull Collection<@NotNull Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }

        @Override
        public @Nullable CompletableFuture<@Nullable AppInfo> appInfo() {
            return CompletableFuture
                .completedFuture(new AppInfoImpl("someappid", "someenvironment", "us-west-2", "jenkins"));
        }

        @Override
        public @Nullable MetricsListener metricsListener() {
            return metricsListener;
        }
    }

    protected static ResponseInfo<Map<String, String>> doEndpointWork(String error) {
        try {
            //executorThreadsUsed.add(Thread.currentThread().getName());
            Thread.sleep(SLEEP_TIME_MILLIS);
            Map<String, String> map = new HashMap<>();
            map.put("executorThreadName", Thread.currentThread().getName());
            //map.put("nettyWorkerThreadName", nettyWorkerThreadName);
            if (error != null && error.equals("400"))
                return ResponseInfo.newBuilder(map).withHttpStatusCode(400).build();
                //TODO: this doesn't work - it hangs
                // else if (error != null && error.equals("500")) throw new IllegalArgumentException("Thrown on purpose for testing purposes!");
            else if (error != null && error.equals("500"))
                return ResponseInfo.newBuilder(map).withHttpStatusCode(500).build();
            return ResponseInfo.newBuilder(map).build();
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static class TestNonblockingEndpoint extends StandardEndpoint<Object, Map<String, String>> {

        private ExecutorService executor = Executors.newCachedThreadPool();

        @Override
        public @NotNull CompletableFuture<ResponseInfo<Map<String, String>>> execute(
            @NotNull final RequestInfo<Object> request,
            @NotNull Executor longRunningTaskExecutor,
            @NotNull ChannelHandlerContext ctx
        ) {
            String nettyWorkerThreadName = Thread.currentThread().getName();
            //nettyWorkerThreadsUsed.add(nettyWorkerThreadName);
            CompletableFuture<ResponseInfo<Map<String, String>>> result =
                CompletableFuture.supplyAsync(() -> doEndpointWork(request.getQueryParamSingle("error")), executor);
            return result;
        }

        @Override
        public @NotNull Matcher requestMatcher() {
            return Matcher.match(NON_BLOCKING_MATCHING_PATH);
        }

    }
}
