package com.nike.helloworld;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.util.AsyncNettyHelper;
import com.nike.riposte.util.Matcher;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

import static com.nike.riposte.util.AsyncNettyHelper.supplierWithTracingAndMdc;

/**
 * A basic {@link StandardEndpoint} that listends for GET calls at the root path "/" and simply responds with
 * "Hello, world" in text/plain mime type.
 */
public class HelloWorldEndpoint extends StandardEndpoint<Void,String> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Matcher matcher = Matcher.match("/", HttpMethod.GET);

    /**
     * @return The {@link Matcher} that maps incoming requests to this endpoint.
     */
    @Override
    public @NotNull Matcher requestMatcher() {
        return matcher;
    }

    /**
     * Sample service endpoint using CompletableFuture.supplyAsync to illustrate how to create an asynchronous
     * call. Where the service task will be completed without making external calls or compute-intensive code
     * it would be better to call:
     *
     * <pre>
     * return CompletableFuture.completedFuture(ResponseInfo.newBuilder("Hello, world!")
     *                                          .withDesiredContentWriterMimeType("text/plain")
     *                                          .build();
     * </pre>
     *
     * Also note that because we use {@link AsyncNettyHelper#supplierWithTracingAndMdc(Supplier, ChannelHandlerContext)}
     * to wrap the endpoint logic, the log message will be automatically tagged with the trace ID associated with the
     * incoming request.
     */
    @Override
    public @NotNull CompletableFuture<ResponseInfo<String>> execute(
        @NotNull RequestInfo<Void> request,
        @NotNull Executor longRunningTaskExecutor,
        @NotNull ChannelHandlerContext ctx
    ) {
        return CompletableFuture.supplyAsync(supplierWithTracingAndMdc(
            () -> {
                logger.debug("Processing Request...");
                return ResponseInfo.newBuilder("Hello, world!").withDesiredContentWriterMimeType("text/plain").build();
            }, ctx)
        );
    }


}
