package com.nike.riposte.server.http;

import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * A class holding state context for a proxy router request. Setup/teardown handlers can call {@link
 * #cleanStateForNewRequest()} for each new incoming request to make sure there's never any stale state from a previous
 * request for this connection.
 *
 * @author Nic Munroe
 */
public class ProxyRouterProcessingState implements ProcessingState {

    private StreamingAsyncHttpClient.StreamingCallback streamingCallback;
    private CompletableFuture<StreamingAsyncHttpClient.StreamingChannel> streamingChannelCompletableFuture;
    // TODO: This business with first chunk future, latest chunk future, and ordering is kinda gross. Find a cleaner solution.
    private CompletableFuture<StreamingAsyncHttpClient.StreamingChannel> firstChunkCF;
    private CompletableFuture<StreamingAsyncHttpClient.StreamingChannel> latestChunkCF;
    private long streamingStartTimeNanos;

    public ProxyRouterProcessingState() {
        // Default constructor - do nothing
    }

    public void cleanStateForNewRequest() {
        streamingCallback = null;
        streamingChannelCompletableFuture = null;
        firstChunkCF = null;
        latestChunkCF = null;
        streamingStartTimeNanos = 0;
    }

    public CompletableFuture<StreamingAsyncHttpClient.StreamingChannel> getStreamingChannelCompletableFuture() {
        return streamingChannelCompletableFuture;
    }

    public void setStreamingChannelCompletableFuture(
        CompletableFuture<StreamingAsyncHttpClient.StreamingChannel> streamingChannelCompletableFuture) {
        this.streamingChannelCompletableFuture = streamingChannelCompletableFuture;
    }

    public StreamingAsyncHttpClient.StreamingCallback getStreamingCallback() {
        return streamingCallback;
    }

    public void setStreamingCallback(StreamingAsyncHttpClient.StreamingCallback streamingCallback) {
        this.streamingCallback = streamingCallback;
    }

    @SuppressWarnings("WeakerAccess")
    protected synchronized void initFirstChunkCompletableFutureIfNecessary() {
        if (firstChunkCF == null) {
            firstChunkCF = new CompletableFuture<>();
            latestChunkCF = firstChunkCF;
        }
    }

    public void triggerChunkProcessing(StreamingAsyncHttpClient.StreamingChannel sc) {
        initFirstChunkCompletableFutureIfNecessary();
        firstChunkCF.complete(sc);
    }

    public void triggerStreamingChannelErrorForChunks(Throwable cause) {
        initFirstChunkCompletableFutureIfNecessary();
        firstChunkCF.completeExceptionally(cause);
    }

    public void registerStreamingChannelChunkProcessingAction(
        BiConsumer<? super StreamingAsyncHttpClient.StreamingChannel, ? super Throwable> action) {
        initFirstChunkCompletableFutureIfNecessary();
        latestChunkCF = latestChunkCF.whenComplete(action);
    }

    public void setStreamingStartTimeNanos(long streamingStartTimeNanos) {
        this.streamingStartTimeNanos = streamingStartTimeNanos;
    }

    public long getStreamingStartTimeNanos() {
        return streamingStartTimeNanos;
    }
}
