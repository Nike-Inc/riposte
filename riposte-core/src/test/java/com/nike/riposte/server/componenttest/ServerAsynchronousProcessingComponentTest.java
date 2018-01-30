package com.nike.riposte.server.componenttest;

import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;


public class ServerAsynchronousProcessingComponentTest {

    private static final Logger logger = LoggerFactory.getLogger(ServerAsynchronousProcessingComponentTest.class);
    private static Server server;
    private static final long SLEEP_TIME_MILLIS = 500;
    private static Set<String> nettyWorkerThreadsUsed = new HashSet<>();
    private static Set<String> executorThreadsUsed = new HashSet<>();
    private static ExecutorService executor;
    private static ServerConfig serverConfig;
    private static ObjectMapper mapper = new ObjectMapper();
    private static String nonblockingEndpointUrl;

    @BeforeClass
    public static void setUpClass() throws Exception {
        executor = Executors.newCachedThreadPool();

        serverConfig = new AsyncProcessingTestConfig();
        assertEquals(1, serverConfig.numBossThreads());
        assertEquals(1, serverConfig.numWorkerThreads());
        assertTrue(serverConfig.endpointsPort() > 0);
        logger.info("Dynamically chose server port {}", serverConfig.endpointsPort());
        nonblockingEndpointUrl = "http://127.0.0.1:" + serverConfig.endpointsPort() + TestNonblockingEndpoint.MATCHING_PATH;
        server = new Server(serverConfig);
        server.startup();

        // Perform a "warmup" to get the Server ready to go - the first call is always slower than normal.
        List<Future<?>> warmupCalls = new ArrayList<>();
        warmupCalls.add(sendServerRequest(nonblockingEndpointUrl));
        for (Future f : warmupCalls) {
            f.get();
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.shutdown();
        executor.shutdown();
    }

    protected void clearDataForNewTest() {
        nettyWorkerThreadsUsed.clear();
        executorThreadsUsed.clear();
    }

    @Test
    public void serverShouldProcessNonblockingEndpointsAsynchronously() throws Exception {
        doServerAsyncProcessingVerificationTestForUrl(nonblockingEndpointUrl, true);
    }

    protected void doServerAsyncProcessingVerificationTestForUrl(String endpointUrl, boolean expectNettyWorkerThreadToBeDifferentThanExecutor) throws ExecutionException, InterruptedException {
        // Clear the data and run the test
        clearDataForNewTest();

        List<Future<Map<String, String>>> futures = new ArrayList<>();
        int numSimultaneousCalls = 10;
        long timeBeforeAnyCallsStarted = System.currentTimeMillis();
        for (int i = 0; i < numSimultaneousCalls; i++) {
            futures.add(sendServerRequest(endpointUrl));
        }

        // Wait for all the requests to return
        List<Map<String, String>> results = new ArrayList<>();
        for (Future<Map<String, String>> f : futures) {
            results.add(f.get());
        }
        long timeAfterAllCallsCompleted = System.currentTimeMillis();

        // Dig through the test results to make sure it all worked. First, when the netty worker thread is supposed to be different than the executor
        // make sure that for each call the same netty worker thread was used, and the executor thread was not the same as the netty worker thread.
        if (expectNettyWorkerThreadToBeDifferentThanExecutor) {
            String commonNettyWorkerThreadName = null;
            for (Map<String, String> map : results) {
                if (commonNettyWorkerThreadName == null)
                    commonNettyWorkerThreadName = map.get("nettyWorkerThreadName");

                assertEquals("Should have common netty worker thread", commonNettyWorkerThreadName, map.get("nettyWorkerThreadName"));
                assertNotEquals("Executor thread should be different than netty worker thread", commonNettyWorkerThreadName, map.get("executorThreadName"));
            }
        }

        // Now that the same number of executor threads were used as calls were made and that the total time for all calls was less than twice a single call.
        // This combination proves that the server was processing calls asynchronously.
        assertThat("This test only works if you do more than 1 simultaneous call", numSimultaneousCalls > 1, is(true));
        // TODO: Travis CI can sometimes be so slow that executor threads get reused, so we can't do an exact (executorThreadsUsed == numSimultaneousCalls) check. Rethink this test.
        assertThat("The number of executor threads used should have been more than one", executorThreadsUsed.size(), is(greaterThan(1)));
        long totalTimeForAllCalls = timeAfterAllCallsCompleted - timeBeforeAnyCallsStarted;
        assertThat(
            "Total time for the server to process all calls should have been less than calling them serially",
            totalTimeForAllCalls < (numSimultaneousCalls * SLEEP_TIME_MILLIS),
            is(true)
        );

        // Additionally, if the netty worker thread is supposed to be different than the executor thread then make sure only one worker thread was ever used.
        if (expectNettyWorkerThreadToBeDifferentThanExecutor)
            assertThat("There should have only been one netty worker thread used", nettyWorkerThreadsUsed.size(), is(1));
    }

    protected static Future<Map<String, String>> sendServerRequest(String endpointUrl) {
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

            logger.info("********* server call to {} returned {} in {} millis *************", endpointUrl, s, (end - start));
            int statusCode = response1.statusCode();
            assertEquals(200, statusCode);
            return mapper.readValue(s, Map.class);
        });

    }

    public static class AsyncProcessingTestConfig implements ServerConfig {
        private final Collection<Endpoint<?>> endpoints = singleton(new TestNonblockingEndpoint());
        private final int port;

        public AsyncProcessingTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }
        }

        @Override
        public int numBossThreads() {
            return 1;
        }

        @Override
        public int numWorkerThreads() {
            return 1;
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

    protected static ResponseInfo<Map<String, String>> doEndpointWork(String nettyWorkerThreadName) {
        try {
            executorThreadsUsed.add(Thread.currentThread().getName());
            Thread.sleep(SLEEP_TIME_MILLIS);
            Map<String, String> map = new HashMap<>();
            map.put("executorThreadName", Thread.currentThread().getName());
            map.put("nettyWorkerThreadName", nettyWorkerThreadName);
            return ResponseInfo.newBuilder(map).build();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static class TestNonblockingEndpoint extends StandardEndpoint<Object, Map<String,String>> {

        public static String MATCHING_PATH = "/testAsync";

        @Override
        public CompletableFuture<ResponseInfo<Map<String,String>>> execute(RequestInfo<Object> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            String nettyWorkerThreadName = Thread.currentThread().getName();
            nettyWorkerThreadsUsed.add(nettyWorkerThreadName);
            CompletableFuture<ResponseInfo<Map<String, String>>> result = CompletableFuture.supplyAsync(() -> doEndpointWork(nettyWorkerThreadName), executor);
            return result;
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }

    }
}
