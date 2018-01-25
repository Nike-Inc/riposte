package com.nike.riposte.server.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.channelpipeline.message.ChunkedOutboundMessage;
import com.nike.riposte.server.channelpipeline.message.OutboundMessageSendContentChunk;
import com.nike.riposte.server.channelpipeline.message.OutboundMessageSendHeadersChunkFromResponseInfo;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.error.handler.ErrorResponseBodySerializer;
import com.nike.riposte.util.ErrorContractSerializerHelper;
import com.nike.riposte.util.HttpUtils;
import com.nike.wingtips.Span;
import com.nike.wingtips.TraceAndSpanIdGenerator;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.nike.riposte.util.AsyncNettyHelper.consumerWithTracingAndMdc;
import static com.nike.riposte.util.AsyncNettyHelper.runnableWithTracingAndMdc;
import static com.nike.riposte.util.AsyncNettyHelper.supplierWithTracingAndMdc;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaders.Values.CHUNKED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Responsible for sending the response to the client. Will populate a {@link TraceHeaders#TRACE_ID} trace header for
 * all outgoing responses, correctly serializes and sends response body content (along with all the appropriate
 * content-related headers such as {@link io.netty.handler.codec.http.HttpHeaders.Names#CONTENT_TYPE}), and correctly
 * handles keep-alive connections. Contains methods for both full responses and chunked responses.
 * <p/>
 * Non-error full responses should call {@link #sendFullResponse(io.netty.channel.ChannelHandlerContext, RequestInfo,
 * ResponseInfo)} or {@link #sendFullResponse(io.netty.channel.ChannelHandlerContext, RequestInfo, ResponseInfo,
 * com.fasterxml.jackson.databind.ObjectMapper)}. Error responses should call {@link
 * #sendErrorResponse(io.netty.channel.ChannelHandlerContext, RequestInfo, ResponseInfo)}. Chunked responses should call
 * {@link #sendResponseChunk(ChannelHandlerContext, RequestInfo, ResponseInfo, ChunkedOutboundMessage)}.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class ResponseSender {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final ObjectMapper defaultResponseContentSerializer;
    private final ErrorResponseBodySerializer errorResponseBodySerializer;

    public static final int DEFAULT_HTTP_STATUS_CODE = HttpResponseStatus.OK.code();

    private static final String HORRIBLE_EXPLOSION_DEFAULT_RESPONSE =
        "{\"error_id\":\"%UUID%\",\"errors\":[{\"code\":10,\"message\":\"An error occurred while fulfilling the request\"}]}";

    private final Consumer<ChannelFuture> logOnWriteErrorConsumer = (channelFuture) -> logger
        .error("An error occurred while writing/sending the response to the remote peer.", channelFuture.cause());

    private ChannelFutureListener logOnWriteErrorOperationListener(ChannelHandlerContext ctx) {
        Consumer<ChannelFuture> errorLoggerConsumerWithTracingAndMdc = consumerWithTracingAndMdc(
            logOnWriteErrorConsumer, ctx
        );

        return channelFuture -> {
            if (!channelFuture.isSuccess())
                errorLoggerConsumerWithTracingAndMdc.accept(channelFuture);
        };
    }

    public ResponseSender(ObjectMapper defaultResponseContentSerializer,
                          ErrorResponseBodySerializer errorResponseBodySerializer) {
        if (defaultResponseContentSerializer == null) {
            logger.info("No defaultResponseContentSerializer specified - using a new no-arg ObjectMapper as the "
                        + "default response serializer");
            defaultResponseContentSerializer = new ObjectMapper();
        }

        if (errorResponseBodySerializer == null) {
            logger.info("No errorResponseBodySerializer specified - using "
                        + "ErrorContractSerializerHelper.SMART_ERROR_SERIALIZER as the default response serializer");
            errorResponseBodySerializer = ErrorContractSerializerHelper.SMART_ERROR_SERIALIZER;
        }

        this.defaultResponseContentSerializer = defaultResponseContentSerializer;
        this.errorResponseBodySerializer = errorResponseBodySerializer;
    }

    protected String serializeOutput(Object output, ObjectMapper serializer, ResponseInfo<?> responseInfo,
                                     ChannelHandlerContext ctx) {
        if (output instanceof CharSequence)
            return output.toString();

        if (serializer == null)
            serializer = defaultResponseContentSerializer;

        try {
            return serializer.writeValueAsString(output);
        }
        catch (JsonProcessingException e) {
            // Something blew up trying to serialize the output.
            // Log what went wrong, set the error_uid response header, then return a default error response string.
            String errorUid = UUID.randomUUID().toString();
            runnableWithTracingAndMdc(
                () -> logger.error(
                    "The output could not be serialized. A default error response will be used instead. "
                    + "error_uid={}, unserializable_class={}",
                    errorUid, output.getClass().getName(), e
                ),
                ctx
            ).run();
            responseInfo.getHeaders().set("error_uid", errorUid);
            return HORRIBLE_EXPLOSION_DEFAULT_RESPONSE.replace("%UUID%", errorUid);
        }
    }

    /**
     * Outputs a chunk of the response to the user via the given ctx argument, depending on the type of the given msg
     * argument. This method only works on chunked responses (where {@link ResponseInfo#isChunkedResponse()} is true).
     * <p/>
     * The given requestInfo argument is used to help determine the Trace ID that should be output to the user in the
     * headers as well as to determine if the user wants the connection kept alive. Once the final chunk in the response
     * is successfully sent, this method sets the channel state's {@link
     * HttpProcessingState#setResponseWriterFinalChunkChannelFuture(ChannelFuture)}
     * to the result of the {@link ChannelHandlerContext#write(Object)} call to indicate that the response was sent for
     * handlers further down the chain and to allow them to attach listeners that are fired when the response is fully
     * sent. {@link ResponseInfo#isResponseSendingStarted()} and {@link ResponseInfo#isResponseSendingLastChunkSent()}
     * will also be set appropriately based on which chunk was sent (assuming this call is successful).
     * <p/>
     * This will throw an {@link IllegalArgumentException} if {@link ResponseInfo#isChunkedResponse()} is false (since
     * this method is only for chunked responses). It will log an error and do nothing if the the response has already
     * been sent.
     * <p/>
     * The type of the msg argument is important - if this is the first chunk (based on the value of {@link
     * ResponseInfo#isResponseSendingStarted()}) then msg must be a {@link
     * OutboundMessageSendHeadersChunkFromResponseInfo} or else an {@link IllegalStateException} will be thrown.
     * Similarly if it is *not* the first chunk, then msg must be a {@link OutboundMessageSendContentChunk} or else an
     * {@link IllegalStateException} will be thrown.
     */
    public void sendResponseChunk(ChannelHandlerContext ctx, RequestInfo<?> requestInfo, ResponseInfo<?> responseInfo,
                                  ChunkedOutboundMessage msg) {
        if (!responseInfo.isChunkedResponse()) {
            throw new IllegalArgumentException(
                "sendResponseChunk() should only be passed a ResponseInfo where ResponseInfo.isChunkedResponse() is "
                + "true. This time it was passed one where isChunkedResponse() was false, indicating a full "
                + "(not chunked) response.");
        }

        if (responseInfo.isResponseSendingLastChunkSent()) {
            runnableWithTracingAndMdc(
                () -> logger.error(
                    "The last response chunk has already been sent. This method should not have been called. Ignoring this method call.",
                    new Exception("This exception and stack trace is for debugging purposes")
                ),
                ctx
            ).run();
            return;
        }

        if (!responseInfo.isResponseSendingStarted()) {
            // This is the first chunk.
            if (msg == null || !(msg instanceof OutboundMessageSendHeadersChunkFromResponseInfo)) {
                String msgClass = (msg == null) ? "null" : msg.getClass().getName();
                throw new IllegalStateException(
                    "Expected the first chunk of the response's message to be a "
                    + "OutboundMessageSendHeadersChunkFromResponseInfo, instead received: " + msgClass);
            }

            sendFirstChunk(ctx, requestInfo, responseInfo, null);
        }
        else {
            // This is not the first chunk.
            if (msg == null || !(msg instanceof OutboundMessageSendContentChunk)) {
                String msgClass = (msg == null) ? "null" : msg.getClass().getName();
                throw new IllegalStateException(
                    "Expected a chunk of the response's message (after the first) to be a "
                    + "OutboundMessageSendContentChunk, instead received: " + msgClass);
            }

            writeChunk(
                ctx, ((OutboundMessageSendContentChunk) msg).contentChunk, requestInfo, responseInfo,
                ChannelAttributes.getHttpProcessingStateForChannel(ctx).get()
            );
        }

        ctx.flush();
    }

    /**
     * Outputs the given *full* responseInfo to the user via the given ctx argument. This method only works on full
     * responses (where {@link ResponseInfo#isChunkedResponse()} is false). If the response's {@link
     * ResponseInfo#getContentForFullResponse()} is not null then the given serializer will be used to convert the
     * content to a string (the {@link #defaultResponseContentSerializer} will be used if the given serializer is
     * null).
     * <p/>
     * The given requestInfo argument is used to help determine the Trace ID that should be output to the user in the
     * headers as well as to determine if the user wants the connection kept alive. Once the response is successfully
     * sent, this method sets the channel state's {@link
     * HttpProcessingState#setResponseWriterFinalChunkChannelFuture(ChannelFuture)}
     * to the result of the {@link ChannelHandlerContext#write(Object)} call to indicate that the response was sent for
     * handlers further down the chain and to allow them to attach listeners that are fired when the response is fully
     * sent. {@link ResponseInfo#isResponseSendingStarted()} and {@link ResponseInfo#isResponseSendingLastChunkSent()}
     * will also be set to true (assuming this call is successful).
     * <p/>
     * This will throw an {@link IllegalArgumentException} if {@link ResponseInfo#isChunkedResponse()} is true (since
     * this method is only for full responses). It will log an error and do nothing if the the response has already been
     * sent.
     */
    public void sendFullResponse(
        ChannelHandlerContext ctx, RequestInfo<?> requestInfo, ResponseInfo<?> responseInfo, ObjectMapper serializer
    ) throws JsonProcessingException {

        if (responseInfo.isChunkedResponse()) {
            throw new IllegalArgumentException(
                "sendFullResponse() should only be passed a ResponseInfo where ResponseInfo.isChunkedResponse() is "
                + "false. This time it was passed one where isChunkedResponse() was true, indicating a chunked "
                + "(not full) response.");
        }

        if (responseInfo.isResponseSendingLastChunkSent()) {
            runnableWithTracingAndMdc(
                () -> logger.error(
                    "The last response chunk has already been sent. This method should not have been called. Ignoring "
                    + "this method call.", new Exception("This exception and stack trace is for debugging purposes")
                ),
                ctx
            ).run();
            return;
        }

        if (serializer == null)
            serializer = defaultResponseContentSerializer;

        // There is only one chunk representing the full request, so send it.
        sendFirstChunk(ctx, requestInfo, responseInfo, serializer);

        ctx.flush();
    }

    protected void sendFirstChunk(ChannelHandlerContext ctx, RequestInfo<?> requestInfo, ResponseInfo<?> responseInfo,
                                  ObjectMapper serializer) {
        // Build the actual response object, which may or may not be a full response with content
        HttpResponse actualResponseObject = createActualResponseObjectForFirstChunk(responseInfo, serializer, ctx);

        synchronizeAndSetupResponseInfoAndFirstChunk(responseInfo, actualResponseObject, requestInfo, ctx);

        // Set the actual response object on the state before sending it through the outbound pipeline
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state != null)
            state.setActualResponseObject(actualResponseObject);

        writeChunk(ctx, actualResponseObject, requestInfo, responseInfo, state);
    }

    protected HttpResponse createActualResponseObjectForFirstChunk(ResponseInfo<?> responseInfo,
                                                                   ObjectMapper serializer, ChannelHandlerContext ctx) {
        HttpResponse actualResponseObject;
        HttpResponseStatus httpStatus =
            HttpResponseStatus.valueOf(responseInfo.getHttpStatusCodeWithDefault(DEFAULT_HTTP_STATUS_CODE));
        determineAndSetCharsetAndMimeTypeForResponseInfoIfNecessary(responseInfo);
        if (responseInfo.isChunkedResponse()) {
            // Chunked response. No content (yet).
            actualResponseObject = new DefaultHttpResponse(HTTP_1_1, httpStatus);
        }
        else {
            // Full response. There may or may not be content.
            if (responseInfo.getContentForFullResponse() == null) {
                // No content, so create a simple full response.
                actualResponseObject = new DefaultFullHttpResponse(HTTP_1_1, httpStatus);
            }
            else {
                // There is content. If it's a raw byte buffer then use it as-is. Otherwise serialize it to a string
                //      using the provided serializer.
                Object content = responseInfo.getContentForFullResponse();
                ByteBuf bytesForResponse;
                if (content instanceof byte[])
                    bytesForResponse = Unpooled.wrappedBuffer((byte[]) content);
                else {
                    bytesForResponse = Unpooled.copiedBuffer(
                        serializeOutput(responseInfo.getContentForFullResponse(), serializer, responseInfo, ctx),
                        responseInfo.getDesiredContentWriterEncoding());
                }
                // Turn the serialized string to bytes for the response content, create the full response with content,
                //      and set the content type header.
                actualResponseObject = new DefaultFullHttpResponse(HTTP_1_1, httpStatus, bytesForResponse);
            }
        }

        return actualResponseObject;
    }

    protected void synchronizeAndSetupResponseInfoAndFirstChunk(ResponseInfo<?> responseInfo,
                                                                HttpResponse actualResponseObject,
                                                                RequestInfo requestInfo, ChannelHandlerContext ctx) {
        // Set the content type header.
        //      NOTE: This is ok even if the response doesn't have a body - in the case of chunked messages we don't
        //            know whether body content will be coming later or not so we have to be proactive here in case
        //            there *is* body content later.
        //      ALSO NOTE: The responseInfo may have already had a Content-Type header specified (e.g. reverse proxied
        //            response), but the way we build the header this will be ok. If responseInfo wanted to override it
        //            we allow that, and if not then the Content-Type in the headers will be honored, and if both of
        //            those are unspecified then the default mime type and charset are used.
        responseInfo.getHeaders().set(CONTENT_TYPE, buildContentTypeHeader(responseInfo));

        // Set the HTTP status code on the ResponseInfo object from the actualResponseObject if necessary.
        if (responseInfo.getHttpStatusCode() == null)
            responseInfo.setHttpStatusCode(actualResponseObject.getStatus().code());

        // Make sure a trace ID is in the headers.
        if (!responseInfo.getHeaders().contains(TraceHeaders.TRACE_ID)) {
            // All responses must contain a trace ID. Try to get it from the request
            //      since it wasn't already in the response.
            String traceId = extractDistributedTraceId(requestInfo, ctx);
            if (traceId == null) {
                // Couldn't find a valid trace ID anywhere, so just create a dummy one, and log what happened so if
                //      someone searches for that ID they'll find something explaining what happened.
                traceId = TraceAndSpanIdGenerator.generateId();
                String warningMsg =
                    "Generating a dummy Trace ID for response header because a real Trace ID did not exist. This "
                    + "probably happened because the request was not processed by the channel pipeline. dummy_trace_id="
                    + traceId;
                runnableWithTracingAndMdc(() -> logger.warn(warningMsg), ctx).run();
            }
            responseInfo.getHeaders().set(TraceHeaders.TRACE_ID, traceId);
        }

        // Handle any keep-alive stuff
        if (responseInfo.isForceConnectionCloseAfterResponseSent()) {
            // We'll be closing the connection after this response is sent, so send the appropriate Connection header.
            responseInfo.getHeaders().set(CONNECTION, HttpHeaders.Values.CLOSE);
        }
        else if (requestInfo.isKeepAliveRequested()) {
            // Add/override the 'Content-Length' header only for a keep-alive connection, and only if we know for sure
            //      what the content length will be.
            if (actualResponseObject instanceof LastHttpContent) {
                responseInfo.getHeaders().set(
                    CONTENT_LENGTH, ((LastHttpContent) actualResponseObject).content().readableBytes()
                );
            }
            else {
                // Some responses should *never* be chunked per RFC 2616 and should always have an empty payload.
                //      If we have one of those responses, we mark it with content-length 0
                if (isContentAlwaysEmpty(responseInfo)) {
                    responseInfo.getHeaders().remove(TRANSFER_ENCODING);
                    responseInfo.getHeaders().set(CONTENT_LENGTH, 0);
                }
                else {
                    // Not a must-be-empty-payload status code. For these there might be a payload and we can't know the
                    //      content length since it's being sent to us in chunks, so we have to set the
                    //      Transfer-Encoding header to chunked in order for the response sending to be successful
                    //      (otherwise the receiving client would just hang waiting for the connection to be closed).
                    // See http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6
                    // and http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6.1 for the technical explanation.
                    // See http://en.wikipedia.org/wiki/Chunked_transfer_encoding for a more straightforward explanation
                    responseInfo.getHeaders().remove(CONTENT_LENGTH);
                    responseInfo.getHeaders().set(TRANSFER_ENCODING, CHUNKED);
                }
            }
            // Add keep alive header as per
            //      http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
            responseInfo.getHeaders().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        }

        // Synchronize the ResponseInfo headers with the actualResponseObject
        //      (copy from responseInfo into actualResponseObject)
        actualResponseObject.headers().add(responseInfo.getHeaders());

        // Add cookies (if any)
        Set<Cookie> cookies = responseInfo.getCookies();
        if (cookies != null && !cookies.isEmpty()) {
            actualResponseObject.headers().add(
                    HttpHeaders.Names.SET_COOKIE, ServerCookieEncoder.LAX.encode(cookies)
            );
        }
    }

    /**
     * Copied from {@link io.netty.handler.codec.http.HttpObjectDecoder#isContentAlwaysEmpty(HttpMessage)} in Netty
     * version 4.0.36-Final.
     *
     * <p>See <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html">RFC 2616 Section 4.4</a> and <a
     * href="https://github.com/netty/netty/issues/222">Netty Issue 222</a> for details on why this logic is necessary.
     *
     * @return true if this response should always be an empty body (per the RFC) and therefore *not* chunked (and with
     * a content-length header of 0), false if the RFC does not forbid a body and it is therefore eligible for
     * chunking.
     */
    protected boolean isContentAlwaysEmpty(ResponseInfo<?> res) {
        int code = res.getHttpStatusCode();

        // Correctly handle return codes of 1xx.
        //
        // See:
        //     - http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html Section 4.4
        //     - https://github.com/netty/netty/issues/222
        if (code >= 100 && code < 200) {
            // One exception: Hixie 76 websocket handshake response
            return !(code == 101 && !res.getHeaders().contains(HttpHeaders.Names.SEC_WEBSOCKET_ACCEPT)
                     && res.getHeaders().contains(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET, true));
        }

        switch (code) {
            case 204:
            case 205:
            case 304:
                return true;
        }

        return false;
    }

    protected void logResponseFirstChunk(HttpResponse response, ChannelHandlerContext ctx) {
        if (logger.isDebugEnabled()) {
            StringBuilder headers = new StringBuilder();
            for (String headerName : response.headers().names()) {
                if (headers.length() > 0)
                    headers.append(", ");

                headers.append(headerName).append("=\"")
                       .append(String.join(",", response.headers().getAll(headerName))).append("\"");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("SENDING RESPONSE:");
            sb.append("\n\tHTTP STATUS: ").append(response.getStatus().code());
            sb.append("\n\tHEADERS: ").append(headers.toString());
            sb.append("\n\tPROTOCOL: ").append(response.getProtocolVersion().text());
            if (response instanceof HttpContent) {
                HttpContent chunk = (HttpContent) response;
                sb.append("\n\tCONTENT CHUNK: ").append(chunk.getClass().getName()).append(", size: ")
                  .append(chunk.content().readableBytes());
            }
            runnableWithTracingAndMdc(() -> logger.debug(sb.toString()), ctx).run();
        }
    }

    protected void logResponseContentChunk(HttpContent chunk, ChannelHandlerContext ctx) {
        if (logger.isDebugEnabled()) {
            runnableWithTracingAndMdc(
                () -> logger.debug("SENDING RESPONSE CHUNK: " + chunk.getClass().getName() + ", size: "
                                   + chunk.content().readableBytes()
                ),
                ctx
            ).run();
        }
    }

    protected void writeChunk(ChannelHandlerContext ctx, HttpObject chunkToWrite, RequestInfo requestInfo,
                              ResponseInfo<?> responseInfo, HttpProcessingState state) {
        if (responseInfo.getUncompressedRawContentLength() == null) {
            // This is the first chunk being sent for this response. Initialize the uncompressed raw content length
            //      value to 0 so we can add to it as we find content.
            responseInfo.setUncompressedRawContentLength(0L);
        }

        // Add to responseInfo's uncompressed content length value if appropriate
        if (chunkToWrite instanceof HttpContent) {
            ByteBuf actualContent = ((HttpContent) chunkToWrite).content();
            if (actualContent != null) {
                long newUncompressedRawContentLengthValue =
                    responseInfo.getUncompressedRawContentLength() + actualContent.readableBytes();
                responseInfo.setUncompressedRawContentLength(newUncompressedRawContentLengthValue);
            }
        }

        // We have to update some state info before the write() call because we could have other messages processed by
        //      the pipeline before the write is finished, and they need to know that the response sending has started
        //      (or finished).
        boolean isLastChunk = chunkToWrite instanceof LastHttpContent;
        if (state != null) {
            // Update the ResponseInfo to indicate that the response sending has been started if this is the first chunk
            if (chunkToWrite instanceof HttpResponse)
                responseInfo.setResponseSendingStarted(true);

            // Update ResponseInfo to indicate that the last chunk has been sent if this is the last chunk.
            if (isLastChunk) {
                responseInfo.setResponseSendingLastChunkSent(true);
            }
        }

        if (chunkToWrite instanceof HttpResponse)
            logResponseFirstChunk((HttpResponse) chunkToWrite, ctx);
        else if (chunkToWrite instanceof HttpContent)
            logResponseContentChunk((HttpContent) chunkToWrite, ctx);
        else
            throw new IllegalStateException("What is this?: " + chunkToWrite.getClass().getName());

        // Write the response, which will send it through the outbound pipeline
        //      (where it might be modified by outbound handlers).
        ChannelFuture writeFuture = ctx.write(chunkToWrite);

        // Handle state-related bookkeeping
        if (state != null && isLastChunk) {
            // Set the state's responseWriterFinalChunkChannelFuture so that handlers can hook into it if desired.
            state.setResponseWriterFinalChunkChannelFuture(writeFuture);
        }

        // Always attach a listener that logs write errors.
        writeFuture.addListener(logOnWriteErrorOperationListener(ctx));

        // Finally, add the appropriate always-close-channel or close-channel-only-on-failure listener.
        //      We only ever want to do a hard always-close in the case that this is the last chunk *and* one of the
        //      following is true:
        //      (1) it's *not* a keep-alive connection
        //          *or*
        //      (2) this is a force-close situation
        //      Any other situation should be a close-only-on-failure.
        if (isLastChunk
            && (!requestInfo.isKeepAliveRequested() || responseInfo.isForceConnectionCloseAfterResponseSent())
        ) {
            writeFuture.addListener(ChannelFutureListener.CLOSE);
        }
        else
            writeFuture.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    /**
     * Helper method that just calls {@link #sendFullResponse(io.netty.channel.ChannelHandlerContext, RequestInfo,
     * ResponseInfo, ObjectMapper)} and passes in the {@link #defaultResponseContentSerializer} as the response
     * serializer. This method only supports full responses (see the javadocs of {@link
     * #sendFullResponse(ChannelHandlerContext, RequestInfo, ResponseInfo, ObjectMapper)} for more details of the
     * requirements for calling this method).
     */
    @SuppressWarnings("unused")
    public void sendFullResponse(
        ChannelHandlerContext ctx, RequestInfo requestInfo, ResponseInfo<?> responseInfo
    ) throws JsonProcessingException {
        sendFullResponse(ctx, requestInfo, responseInfo, defaultResponseContentSerializer);
    }

    /**
     * Sets an error_uid header based on the given error response's {@link ErrorResponseBody#errorId()} and replaces the
     * {@link ErrorResponseBody} found in the {@link ResponseInfo#getContentForFullResponse()} with the String result of
     * calling {@link ErrorResponseBodySerializer#serializeErrorResponseBodyToString(ErrorResponseBody)} on {@link
     * #errorResponseBodySerializer}. The modified {@link ResponseInfo} is then sent to {@link
     * #sendFullResponse(io.netty.channel.ChannelHandlerContext, RequestInfo, ResponseInfo, ObjectMapper)} for passing
     * back to the client.
     * <p/>
     * NOTE: This assumes a full (not chunked) response, and uses {@link ResponseInfo#getContentForFullResponse()} to
     * retrieve the {@link ErrorResponseBody} object. Therefore this method will throw an {@link
     * IllegalArgumentException} if you pass in a response object that returns true for {@link
     * ResponseInfo#isChunkedResponse()}.
     */
    public void sendErrorResponse(ChannelHandlerContext ctx,
                                  RequestInfo requestInfo,
                                  ResponseInfo<ErrorResponseBody> responseInfo) throws JsonProcessingException {

        if (responseInfo.isChunkedResponse()) {
            throw new IllegalArgumentException("The responseInfo argument is marked as being a chunked response, but "
                                               + "sendErrorResponse(...) only works with full responses");
        }

        responseInfo.getHeaders().set("error_uid", responseInfo.getContentForFullResponse().errorId());

        @SuppressWarnings("UnnecessaryLocalVariable")
        ErrorResponseBody bodyToSerialize = responseInfo.getContentForFullResponse();
        if (bodyToSerialize != null) {
            String errorBodyAsString = errorResponseBodySerializer.serializeErrorResponseBodyToString(bodyToSerialize);
            //noinspection unchecked
            ((ResponseInfo) responseInfo).setContentForFullResponse(errorBodyAsString);
        }

        sendFullResponse(ctx, requestInfo, responseInfo, defaultResponseContentSerializer);
    }

    protected String extractDistributedTraceId(RequestInfo requestInfo, ChannelHandlerContext ctx) {
        String traceId = (requestInfo == null) ? null : requestInfo.getHeaders().get(TraceHeaders.TRACE_ID);
        if (traceId == null) {
            traceId = supplierWithTracingAndMdc(
                () -> {
                    Span currentSpanFromTracer = Tracer.getInstance().getCurrentSpan();
                    if (currentSpanFromTracer != null)
                        return currentSpanFromTracer.getTraceId();

                    return null;
                },
                ctx
            ).get();
        }

        return traceId;
    }

    protected void determineAndSetCharsetAndMimeTypeForResponseInfoIfNecessary(ResponseInfo<?> responseInfo) {
        Charset charsetToUse = determineCharsetToUse(responseInfo);

        // Now that we know the charset to use, make sure the ResponseInfo object's desiredContentWriterEncoding
        //      reflects it (for informational purposes if someone down the road is curious)
        responseInfo.setDesiredContentWriterEncoding(charsetToUse);

        String mimeTypeToUse = determineMimeTypeToUse(responseInfo);

        // Now that we know the mime type to use, make sure the ResponseInfo object's desiredContentWriterMimeType
        //      reflects it (for informational purposes if someone down the road is curious)
        responseInfo.setDesiredContentWriterMimeType(mimeTypeToUse);
    }

    protected Charset determineCharsetToUse(ResponseInfo<?> responseInfo) {
        // Figure out what charset to use. If responseInfo has one specified via desiredContentWriterEncoding then use
        //      that, otherwise attempt to extract it from the response headers, and use
        //      ResponseInfo.DEFAULT_CONTENT_ENCODING as the "nobody has an opinion" default.
        return (responseInfo.getDesiredContentWriterEncoding() == null)
               ? HttpUtils
                   .determineCharsetFromContentType(responseInfo.getHeaders(), ResponseInfo.DEFAULT_CONTENT_ENCODING)
               : responseInfo.getDesiredContentWriterEncoding();
    }

    protected String determineMimeTypeToUse(ResponseInfo<?> responseInfo) {
        // Figure out what mime type to use. If responseInfo has one specified via desiredContentWriterMimeType then use
        //      that, otherwise attempt to extract it from the response headers, and use
        //      ResponseInfo.DEFAULT_MIME_TYPE as the "nobody has an opinion" default.
        return (responseInfo.getDesiredContentWriterMimeType() == null)
               ? extractMimeTypeFromContentTypeHeader(responseInfo.getHeaders(), ResponseInfo.DEFAULT_MIME_TYPE)
               : responseInfo.getDesiredContentWriterMimeType();
    }

    protected String extractMimeTypeFromContentTypeHeader(HttpHeaders headers, String def) {
        if (headers == null)
            return def;

        String contentTypeHeader = headers.get(HttpHeaders.Names.CONTENT_TYPE);
        if (contentTypeHeader == null || contentTypeHeader.trim().length() == 0)
            return def;

        if (contentTypeHeader.contains(";"))
            contentTypeHeader = contentTypeHeader.substring(0, contentTypeHeader.indexOf(";"));

        return contentTypeHeader.trim();
    }

    protected String buildContentTypeHeader(ResponseInfo<?> responseInfo) {
        if (responseInfo.getDesiredContentWriterEncoding() == null)
            throw new IllegalArgumentException("responseInfo.getDesiredContentWriterEncoding() cannot be null");

        if (responseInfo.getDesiredContentWriterMimeType() == null)
            throw new IllegalArgumentException("responseInfo.getDesiredContentWriterMimeType() cannot be null");

        return responseInfo.getDesiredContentWriterMimeType() + "; charset="
               + responseInfo.getDesiredContentWriterEncoding().name();
    }
}
