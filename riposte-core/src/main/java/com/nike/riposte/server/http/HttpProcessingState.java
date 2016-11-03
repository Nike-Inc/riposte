package com.nike.riposte.server.http;

import com.nike.wingtips.Span;

import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.HttpResponse;

/**
 * A class holding state context for a single HTTP request/response cycle (a full processing cycle of the pipeline).
 * Setup/teardown handlers can call {@link #cleanStateForNewRequest()} for each new incoming request to make sure
 * there's never any stale state from a previous request for this connection.
 *
 * @author Nic Munroe
 */
public class HttpProcessingState implements ProcessingState {

    private static final CompletableFuture<Void> COMPLETED_VOID_FUTURE = CompletableFuture.completedFuture(null);

    private RequestInfo<?> requestInfo;
    private ResponseInfo<?> responseInfo;
    private HttpResponse actualResponseObject;
    private Endpoint<?> endpointForExecution;
    private Deque<Span> distributedTraceStack;
    private Map<String, String> loggerMdcContextMap;
    private Instant requestStartTime;
    private ChannelFuture responseWriterFinalChunkChannelFuture;
    private boolean traceCompletedOrScheduled = false;
    private boolean accessLogCompletedOrScheduled = false;
    private boolean requestMetricsRecordedOrScheduled = false;
    private CompletableFuture<Void> preEndpointExecutionWorkChain = COMPLETED_VOID_FUTURE;

    public HttpProcessingState() {
        // Default constructor - do nothing
    }

    public HttpProcessingState(HttpProcessingState copyMe) {
        this.requestInfo = copyMe.getRequestInfo();
        this.responseInfo = copyMe.getResponseInfo();
        this.actualResponseObject = copyMe.getActualResponseObject();
        this.endpointForExecution = copyMe.getEndpointForExecution();
        this.distributedTraceStack = copyMe.getDistributedTraceStack();
        this.loggerMdcContextMap = copyMe.getLoggerMdcContextMap();
        this.requestStartTime = copyMe.getRequestStartTime();
        this.responseWriterFinalChunkChannelFuture = copyMe.getResponseWriterFinalChunkChannelFuture();
        this.traceCompletedOrScheduled = copyMe.isTraceCompletedOrScheduled();
        this.accessLogCompletedOrScheduled = copyMe.isAccessLogCompletedOrScheduled();
        this.requestMetricsRecordedOrScheduled = copyMe.isRequestMetricsRecordedOrScheduled();
        this.preEndpointExecutionWorkChain = copyMe.preEndpointExecutionWorkChain;
    }

    public void cleanStateForNewRequest() {
        if (requestInfo != null)
            requestInfo.releaseAllResources();

        requestInfo = null;
        responseInfo = null;
        actualResponseObject = null;
        endpointForExecution = null;
        distributedTraceStack = null;
        loggerMdcContextMap = null;
        requestStartTime = null;
        responseWriterFinalChunkChannelFuture = null;
        traceCompletedOrScheduled = false;
        accessLogCompletedOrScheduled = false;
        requestMetricsRecordedOrScheduled = false;
        preEndpointExecutionWorkChain = COMPLETED_VOID_FUTURE;
    }

    public RequestInfo<?> getRequestInfo() {
        return requestInfo;
    }

    public void setRequestInfo(RequestInfo<?> requestInfo) {
        this.requestInfo = requestInfo;
    }

    public Endpoint<?> getEndpointForExecution() {
        return endpointForExecution;
    }

    public void setEndpointForExecution(Endpoint<?> endpointForExecution) {
        this.endpointForExecution = endpointForExecution;
    }

    public boolean isRequestHandled() {
        return (getResponseInfo() != null);
    }

    public ResponseInfo<?> getResponseInfo() {
        return responseInfo;
    }

    public void setResponseInfo(ResponseInfo<?> responseInfo) {
        this.responseInfo = responseInfo;
    }

    public boolean isResponseSendingStarted() {
        //noinspection SimplifiableIfStatement
        if (responseInfo == null)
            return false;

        return responseInfo.isResponseSendingStarted();
    }

    public boolean isResponseSendingLastChunkSent() {
        //noinspection SimplifiableIfStatement
        if (responseInfo == null || getResponseWriterFinalChunkChannelFuture() == null)
            return false;

        return responseInfo.isResponseSendingLastChunkSent();
    }

    public Deque<Span> getDistributedTraceStack() {
        return distributedTraceStack;
    }

    public void setDistributedTraceStack(Deque<Span> distributedTraceStack) {
        this.distributedTraceStack = distributedTraceStack;
    }

    public Map<String, String> getLoggerMdcContextMap() {
        return loggerMdcContextMap;
    }

    public void setLoggerMdcContextMap(Map<String, String> loggerMdcContextMap) {
        this.loggerMdcContextMap = loggerMdcContextMap;
    }

    public Instant getRequestStartTime() {
        return requestStartTime;
    }

    public void setRequestStartTime(Instant requestStartTime) {
        this.requestStartTime = requestStartTime;
    }

    public ChannelFuture getResponseWriterFinalChunkChannelFuture() {
        return responseWriterFinalChunkChannelFuture;
    }

    public void setResponseWriterFinalChunkChannelFuture(ChannelFuture responseWriterFinalChunkChannelFuture) {
        this.responseWriterFinalChunkChannelFuture = responseWriterFinalChunkChannelFuture;
    }

    public HttpResponse getActualResponseObject() {
        return actualResponseObject;
    }

    public void setActualResponseObject(HttpResponse actualResponseObject) {
        this.actualResponseObject = actualResponseObject;
    }

    public boolean isTraceCompletedOrScheduled() {
        return traceCompletedOrScheduled;
    }

    public void setTraceCompletedOrScheduled(boolean traceCompletedOrScheduled) {
        this.traceCompletedOrScheduled = traceCompletedOrScheduled;
    }

    public boolean isAccessLogCompletedOrScheduled() {
        return accessLogCompletedOrScheduled;
    }

    public void setAccessLogCompletedOrScheduled(boolean accessLogCompletedOrScheduled) {
        this.accessLogCompletedOrScheduled = accessLogCompletedOrScheduled;
    }

    public boolean isRequestMetricsRecordedOrScheduled() {
        return requestMetricsRecordedOrScheduled;
    }

    public void setRequestMetricsRecordedOrScheduled(boolean requestMetricsRecordedOrScheduled) {
        this.requestMetricsRecordedOrScheduled = requestMetricsRecordedOrScheduled;
    }

    public void addPreEndpointExecutionWorkChainSegment(Function<Void, CompletableFuture<Void>> segmentStarterFunc) {
        preEndpointExecutionWorkChain = preEndpointExecutionWorkChain.thenCompose(segmentStarterFunc);
    }

    public CompletableFuture<Void> getPreEndpointExecutionWorkChain() {
        return preEndpointExecutionWorkChain;
    }
}
