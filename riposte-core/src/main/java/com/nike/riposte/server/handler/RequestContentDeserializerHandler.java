package com.nike.riposte.server.handler;

import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.error.exception.RequestContentDeserializationException;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport;
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.RequestInfo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Looks at the current channel state's {@link HttpProcessingState#getEndpointForExecution()} to see if the endpoint
 * wants the incoming request content deserialized into an object. If deserialization is requested then this handler
 * makes sure that {@link RequestInfo#setupContentDeserializer(ObjectMapper, TypeReference)} is called with the
 * appropriate arguments so that {@link RequestInfo#getContent()} is populated for future handlers in the pipeline. If
 * deserialization fails then a {@link RequestContentDeserializationException} will be thrown. This handler will use
 * {@link Endpoint#customRequestContentDeserializer(RequestInfo)} if the endpoint returns one, otherwise it will use
 * {@link #defaultRequestContentDeserializer}.
 * <p/>
 * This must come after {@link com.nike.riposte.server.handler.RequestInfoSetterHandler} and {@link
 * com.nike.riposte.server.handler.RoutingHandler} in the pipeline to make sure that the {@link
 * HttpProcessingState#getRequestInfo()} and {@link HttpProcessingState#getEndpointForExecution()} have both had a
 * chance to be populated.
 *
 * @author Nic Munroe
 */
public class RequestContentDeserializerHandler extends BaseInboundHandlerWithTracingAndMdcSupport {

    @SuppressWarnings("FieldCanBeLocal")
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final ObjectMapper defaultRequestContentDeserializer;

    public RequestContentDeserializerHandler(ObjectMapper defaultRequestContentDeserializer) {
        if (defaultRequestContentDeserializer == null) {
            logger.debug( "No defaultRequestContentDeserializer specified - using a new no-arg ObjectMapper as the "
                          + "default request deserializer");
            defaultRequestContentDeserializer = new ObjectMapper();
        }

        this.defaultRequestContentDeserializer = defaultRequestContentDeserializer;
    }

    @Override
    public PipelineContinuationBehavior doChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LastHttpContent) {
            HttpProcessingState state = ChannelAttributes.getHttpProcessingStateForChannel(ctx).get();
            Endpoint<?> endpoint = state.getEndpointForExecution();
            RequestInfo reqInfo = state.getRequestInfo();
            // Don't bother trying to deserialize until we have an endpoint and the request content has fully arrived
            if (endpoint != null && reqInfo.isCompleteRequestWithAllChunks()) {
                // Setup the content deserializer if desired
                TypeReference<?> contentTypeRef = endpoint.requestContentType();
                if (contentTypeRef != null) {
                    // A non-null TypeReference is available, so deserialization is possible. Retrieve the appropriate
                    //      deserializer and setup the RequestInfo so that it can lazily deserialize when requested.
                    ObjectMapper deserializer = endpoint.customRequestContentDeserializer(reqInfo);
                    if (deserializer == null)
                        deserializer = defaultRequestContentDeserializer;

                    //noinspection unchecked
                    reqInfo.setupContentDeserializer(deserializer, contentTypeRef);
                }
            }
        }

        return PipelineContinuationBehavior.CONTINUE;
    }

    @Override
    protected boolean argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
        HandlerMethodToExecute methodToExecute, ChannelHandlerContext ctx, Object msgOrEvt, Throwable cause
    ) {
        // To save on extraneous linking/unlinking, we'll do it as-necessary in this class.
        return false;
    }
}
