package com.nike.riposte.server.http;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.ReferenceCountUtil;

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

    protected String serializeOutputToString(Object output, ObjectMapper serializer, ResponseInfo<?> responseInfo,
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
        // Sanitize the responseInfo
        sanitizeResponseInfo(responseInfo, requestInfo, serializer, ctx);

        // Build the actual response object, which may or may not be a full response with content, and then synchronize
        //      it with responseInfo.
        HttpResponse actualResponseObject = createActualResponseObjectForFirstChunk(
            responseInfo, requestInfo, serializer, ctx
        );

        synchronizeAndSetupResponseInfoAndFirstChunk(responseInfo, actualResponseObject, requestInfo, ctx);

        // Set the actual response object on the state before sending it through the outbound pipeline
        HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
        if (state != null)
            state.setActualResponseObject(actualResponseObject);

        writeChunk(ctx, actualResponseObject, requestInfo, responseInfo, state);
    }

    protected void sanitizeResponseInfo(
        ResponseInfo<?> responseInfo,
        RequestInfo<?> requestInfo,
        ObjectMapper serializer,
        ChannelHandlerContext ctx
    ) {
        // Set the HTTP status code on the ResponseInfo object to the default if it is currently unspecified.
        if (responseInfo.getHttpStatusCode() == null) {
            responseInfo.setHttpStatusCode(DEFAULT_HTTP_STATUS_CODE);
        }

        // Determine and set responseInfo's charset and mime type fields if they are not already set (note this does
        //      not by itself affect any headers, so it is safe even for proxied responses).
        determineAndSetCharsetAndMimeTypeForResponseInfoIfNecessary(responseInfo);

        // Set the content type header, but only for full responses. We *don't* do this for chunked responses because
        //      at the moment chunked responses can only come from ProxyRouterEndpoints, and we should not be guessing
        //      what the downstream system's content type will be if they didn't specify one.
        // TODO: If we ever have the ability to specify chunked responses that *aren't* ProxyRouterEndpoints, then this
        //      may need to be adjusted to only omit proxy calls rather than blindly triggering on isChunkedResponse().
        if (!responseInfo.isChunkedResponse()) {
            // NOTE: This is ok even if the response doesn't have a body (may even be desired for things like HEAD
            //      requests where there's no body but you want to tell the caller what the content-type would be).
            responseInfo.getHeaders().set(CONTENT_TYPE, buildContentTypeHeader(responseInfo));
        }

        // Make sure a trace ID is in the response headers.
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

        // Do some RFC conforming and helpful calculation/sanitization regarding transfer-encoding and content-length
        //      headers. We only do this for non-chunked responses as we don't want to modify anything passing through
        //      when it's a ProxyRouterEndpoint response.
        if (!responseInfo.isChunkedResponse()) {
            // If this response should *not* have a payload per the HTTP spec, then we should remove any payload
            //      from responseInfo.
            if (isContentAlwaysEmpty(requestInfo, responseInfo)) {
                Object origResponseContent = responseInfo.getContentForFullResponse();
                if (origResponseContent != null) {
                    // The response should have an empty payload per the HTTP spec, but we found non-empty content in
                    //      responseInfo. Remove the payload. This may or may not be an error on the part of whoever
                    //      generated the responseInfo.
                    responseInfo.setContentForFullResponse(null);

                    if (isAllowedToLieAboutContentLength(requestInfo, responseInfo)) {
                        // This is a HEAD request or 304 response, and for those cases you are allowed to specify
                        //      content-length to indicate the size of what *would* have been returned for a normal GET
                        //      with 2xx response even though you don't actually return a payload.
                        //      See https://tools.ietf.org/html/rfc7230#section-3.3.2 for the explanation on how the
                        //      HEAD method and 304 HTTP status code relates to the content-length header.
                        // Practically speaking, we honor any explicit content-length value the user specified in
                        //      responseInfo in case they know exactly what value they want to return, or if no
                        //      content-length is specified then we fallback to the actual content object they had set
                        //      on responseInfo. That way the endpoints can use the same logic as they would for a GET
                        //      request, including specifying non-serialized payload, and we'll calculate the
                        //      content-length for them the same way we would have for the GET request.
                        if (responseInfo.getHeaders().get(CONTENT_LENGTH) == null) {
                            // No explicit content-length header, and responseInfo did contain some content. Serialize
                            //      that content the same way as what would have been done for a non-HEAD/304 request
                            //      and use the resulting size-in-bytes for the content-length header.
                            ByteBuf serializedBytes = serializeOutputToByteBufForResponse(
                                origResponseContent, responseInfo, serializer, ctx
                            );

                            try {
                                responseInfo.getHeaders().set(CONTENT_LENGTH, serializedBytes.readableBytes());
                            }
                            finally {
                                // We're not actually going to use the serializedBytes ByteBuf, so we need to make sure
                                //      its memory is released.
                                if (serializedBytes.refCnt() > 0) {
                                    ReferenceCountUtil.safeRelease(serializedBytes);
                                }
                            }
                        }
                    }
                    else {
                        // Not a HEAD request or 304 response, so the payload on responseInfo was invalid. Log a
                        //      warning so the dev knows why their payload got stripped out.
                        logger.warn(
                            "The response contained non-empty payload, but per the HTTP specification the request's "
                            + "HTTP method and/or the response's HTTP status code means we MUST NOT return a payload, "
                            + "so we will prevent a payload from being returned. "
                            + "request_http_method={}, response_http_status_code={}",
                            requestInfo.getMethod(), responseInfo.getHttpStatusCode()
                        );
                    }
                }
            }

            // This is a full response (not chunked) so we should not have chunked transfer-encoding or it will cause
            //      problems.
            removeTransferEncodingChunked(responseInfo.getHeaders());

            if (isContentLengthHeaderShouldBeMissing(requestInfo, responseInfo)) {
                // This request/response combo should *never* return content-length header as per
                //      https://tools.ietf.org/html/rfc7230#section-3.3.2.
                responseInfo.getHeaders().remove(CONTENT_LENGTH);
            }
        }
    }

    protected void removeTransferEncodingChunked(HttpHeaders headers) {
        if (headers.contains(TRANSFER_ENCODING, CHUNKED, true)) {
            List<String> transferEncodingsMinusChunked =
                headers.getAll(TRANSFER_ENCODING).stream()
                       .filter(encoding -> !CHUNKED.equalsIgnoreCase(encoding))
                       .collect(Collectors.toList());

            if (transferEncodingsMinusChunked.isEmpty()) {
                headers.remove(TRANSFER_ENCODING);
            }
            else {
                headers.set(TRANSFER_ENCODING, transferEncodingsMinusChunked);
            }
        }
    }

    protected HttpResponse createActualResponseObjectForFirstChunk(
        ResponseInfo<?> responseInfo,
        RequestInfo<?> requestInfo,
        ObjectMapper serializer,
        ChannelHandlerContext ctx
    ) {
        HttpResponseStatus httpStatus =
            HttpResponseStatus.valueOf(responseInfo.getHttpStatusCodeWithDefault(DEFAULT_HTTP_STATUS_CODE));

        if (responseInfo.isChunkedResponse()) {
            // Chunked response. No content (yet). Return a DefaultHttpResponse (not a full one) for the first chunk
            //      of a chunked response.
            return new DefaultHttpResponse(HTTP_1_1, httpStatus);
        }
        else {
            // Full response. There may or may not be content.
            Object content = responseInfo.getContentForFullResponse();
            if (content == null || isContentAlwaysEmpty(requestInfo, responseInfo)) {
                // No content, or this is a response status code that MUST NOT send a payload, so return a simple full
                //      response without a payload.
                return new DefaultFullHttpResponse(HTTP_1_1, httpStatus);
            }
            else {
                // There is content and this is not a response that prohibits a payload. Serialize the content to a
                //      ByteBuf for the response.
                ByteBuf bytesForResponse = serializeOutputToByteBufForResponse(
                    responseInfo.getContentForFullResponse(), responseInfo, serializer, ctx
                );
                // Return a full response with the serialized payload.
                return new DefaultFullHttpResponse(HTTP_1_1, httpStatus, bytesForResponse);
            }
        }
    }

    protected ByteBuf serializeOutputToByteBufForResponse(
        Object content,
        ResponseInfo<?> responseInfo,
        ObjectMapper serializer,
        ChannelHandlerContext ctx
    ) {
        // If the content is a raw byte array then use it as-is via a wrapped ByteBuf. Otherwise serialize it to a
        //      string using the provided serializer.
        if (content instanceof byte[]) {
            return Unpooled.wrappedBuffer((byte[]) content);
        }
        else {
            return Unpooled.copiedBuffer(
                serializeOutputToString(content, serializer, responseInfo, ctx),
                responseInfo.getDesiredContentWriterEncoding()
            );
        }
    }

    protected void synchronizeAndSetupResponseInfoAndFirstChunk(
        ResponseInfo<?> responseInfo,
        HttpResponse actualResponseObject,
        RequestInfo requestInfo,
        ChannelHandlerContext ctx
    ) {
        // Handle any keep-alive stuff - we unfortunately can't do all of this in sanitizeResponseInfo because we don't
        //      yet have the Netty actualResponseObject, so we'll do it here.
        if (responseInfo.isForceConnectionCloseAfterResponseSent()) {
            // We'll be closing the connection after this response is sent, so send the appropriate Connection header.
            responseInfo.getHeaders().set(CONNECTION, HttpHeaders.Values.CLOSE);
        }
        else if (requestInfo.isKeepAliveRequested()) {
            // Set keep alive header as per
            //      http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
            responseInfo.getHeaders().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
            
            // Add/override the 'Content-Length' header only for a keep-alive connection, and only if we know for sure
            //      what the content length will/should be (i.e. actualResponseObject must be a LastHttpContent
            //      indicating a full response where we have the full payload).
            if (actualResponseObject instanceof LastHttpContent) {
                if (isAllowedToLieAboutContentLength(requestInfo, responseInfo)
                    && responseInfo.getHeaders().contains(CONTENT_LENGTH)
                ) {
                    // Do nothing - this response status code is allowed to lie about its content-length, and the
                    //      responseInfo has explicitly specified something.
                }
                else {
                    // Not allowed to lie about content-length or not explicitly specified in responseInfo, so set it
                    //      to whatever the response actually contains.
                    responseInfo.getHeaders().set(
                        CONTENT_LENGTH, ((LastHttpContent) actualResponseObject).content().readableBytes()
                    );
                }
            }
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
     * version 4.0.36-Final, and adjusted to include HEAD requests which are also *not* allowed to have a response
     * payload per the HTTP spec.
     *
     * <p>See <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html">RFC 2616 Section 4.4</a>,
     * <a href="https://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.4">RFC 2616 Section 9.4</a>, and
     * <a href="https://github.com/netty/netty/issues/222">Netty Issue 222</a> for details on why this logic is
     * necessary.
     *
     * Note: If this is true it doesn't necessarily mean content-length of 0, for example 204 is not allowed to return
     * a content-length header at all, and HEAD and 304 responses are allowed to "lie" about content-length (i.e. tell
     * the caller what the content-length would have been for a normal GET request or 200 response). See
     * <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230 Section 3.3.2</a> for details on
     * content-length, but bottom line is that this method should only be used to see if any payload should be stripped
     * from a response, *not* for determining what content-length should be.
     *
     * @return true if this response should always be an empty body (per the RFC), false if the RFC does not forbid a
     * body.
     */
    protected boolean isContentAlwaysEmpty(RequestInfo<?> req, ResponseInfo<?> res) {
        if (HttpMethod.HEAD.equals(req.getMethod())) {
            return true;
        }

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

    /**
     * The content-length header should never be returned for status codes 1xx and 204, or when it was a CONNECT
     * request with a 2xx response. See
     * <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230 Section 3.3.2</a> for details.
     *
     * @return true if this is a 1xx or 204 response HTTP status code, or if it was a CONNECT request with a 2xx
     * response, false otherwise.
     */
    protected boolean isContentLengthHeaderShouldBeMissing(RequestInfo<?> req, ResponseInfo<?> res) {
        int statusCode = res.getHttpStatusCode();

        if (statusCode >= 100 && statusCode < 200) {
            return true;
        }

        if (statusCode == 204) {
            return true;
        }

        //noinspection RedundantIfStatement
        if (HttpMethod.CONNECT.equals(req.getMethod()) && statusCode >= 200 && statusCode < 300) {
            return true;
        }

        return false;
    }

    /**
     * Some response scenarios are allowed to lie about content-length.
     *
     * <p>See <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230 Section 3.3.2</a>.
     *
     * @return true if the given response is allowed to lie about its content length, false otherwise.
     */
    private boolean isAllowedToLieAboutContentLength(RequestInfo<?> req, ResponseInfo<?> res) {
        return HttpMethod.HEAD.equals(req.getMethod()) || res.getHttpStatusCode() == 304;
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

            // Always attach a listener that sets response end time.
            writeFuture.addListener(future -> state.setResponseEndTimeNanosToNowIfNotAlreadySet());
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
