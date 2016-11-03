package com.nike.riposte.server.http;

import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.util.Matcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Interface for an endpoint. Concrete implementations must implement {@link #requestMatcher()} to define what requests
 * they want to handle. If your endpoint expects request body content (e.g. POST or PUT requests) then you'll probably
 * want to override {@link #requestContentType()} to return a non-null value so that the request body content is
 * deserialized into the expected object type for you. NOTE: Rather than require every endpoint to override {@link
 * #requestContentType()} you might consider determining it dynamically in the constructor of a base class using
 * reflection magic similar to how {@link TypeReference} itself works - see the source code of the {@link
 * TypeReference#TypeReference()} constructor for details ({@code StandardEndpoint} does this if you want a more direct
 * example).
 * <p/>
 * An {@code execute(...)} type method is not defined here since different endpoint types will have different
 * requirements for arguments they take in as well as what they return, however the typical endpoint is {@code
 * StandardEndpoint} from {@code riposte-core} and is usually what you want your endpoints to extend.
 *
 * @author Nic Munroe
 */
public interface Endpoint<I> {

    /**
     * @return The {@link Matcher} for determining whether this endpoint should be called for a given request. Concrete
     * implementations will usually just call and return {@link Matcher#match(String,
     * io.netty.handler.codec.http.HttpMethod...)} or one of the other common static construction helper methods in
     * {@link Matcher}, although you can do whatever you want if you have custom requirements.
     */
    Matcher requestMatcher();

    /**
     * @return The overall timeout value in milliseconds that you want for this specific endpoint, or null if you want
     * to use the app-wide default timeout returned by {@link
     * ServerConfig#defaultCompletableFutureTimeoutInMillisForNonblockingEndpoints()}.
     */
    default Long completableFutureTimeoutOverrideMillis() {
        // Return null by default so that the app-wide timeout value will be used unless you override this method.
        return null;
    }

    /**
     * @return A {@link com.fasterxml.jackson.core.type.TypeReference} for the type you want the request body content to
     * be. This will be used to populate the {@link RequestInfo#getContent()} for the request info passed into the
     * {@code execute(...)} method of the concrete endpoint implementation. The standard procedure for implementing this
     * method is a single line: {@code return new TypeReference<ExpectedObjectType>(){};}. This method can safely return
     * null - if this returns null then no deserialization will be performed and {@link RequestInfo#getContent()} will
     * be null when the {@code execute(...)} is called (you can still get the raw content string via {@link
     * RequestInfo#getRawContent()}).
     * <p/>
     * NOTE: Since this is used to deserialize request body content you only need to override it if you expect request
     * body content to be non-empty (e.g. POSTs or PUTs). For standard GET calls that don't have a request body you can
     * leave this default implementation as-is.
     * <p/>
     * ALSO NOTE: Rather than require every endpoint to override this method individually you might consider determining
     * it dynamically in the constructor of a base class using reflection magic similar to how {@link TypeReference}
     * itself works - see the source code of the {@link TypeReference#TypeReference()} constructor for details ({@code
     * StandardEndpoint} does this if you want a more direct example).
     */
    default TypeReference<I> requestContentType() {
        return null;
    }

    /**
     * @return true if this endpoint wants validation on the {@link RequestInfo#getContent()}, false if validation
     * should not be performed. NOTE: The content must not be null for validation to be performed, which means this
     * method must return true *and* {@link #requestContentType()} must return a non-null value (in order for the
     * content to be deserialized) if you want validation done on the content.
     */
    default boolean isValidateRequestContent(@SuppressWarnings("UnusedParameters") RequestInfo<?> request) {
        return true;
    }

    /**
     * @return true if this endpoint knows that deserializing and validating the payload in the given request is fast
     * enough that it will finish processing in less than half a millisecond or so (and therefore not block Netty worker
     * threads to the point it becomes a bottleneck and adversely affects throughput), false otherwise when
     * deserialization and validation should be run asynchronously off the Netty worker thread. The default
     * implementation requests asynchronous processing if the request's payload is greater than 50k. This is just a
     * ballpark guess however since payloads are different, especially in how long validation takes. Therefore you
     * should performance test your system to make sure this default will work for your payloads, and adjust what this
     * method returns if needed.
     */
    default boolean shouldValidateAsynchronously(RequestInfo<?> request) {
        return request.getRawContentLengthInBytes() > 50000;
    }

    /**
     * @return The array of validation groups that should be used when applying validation to the given request for this
     * endpoint, or null if you just want to use the default validation group. This is primarily used for validating the
     * same object type in different ways in different situations (e.g. JSR 303 validation groups).
     */
    default Class<?>[] validationGroups(@SuppressWarnings("UnusedParameters") RequestInfo<?> request) {
        return null;
    }

    /**
     * Override this if you want to use a custom content deserializer for this endpoint to populate {@link
     * RequestInfo#getContent()} for when the {@code execute(...)} method is called. You can safely return null - if
     * this returns null then the {@link ServerConfig#defaultRequestContentDeserializer()}
     * will be used.
     */
    default ObjectMapper customRequestContentDeserializer(
        @SuppressWarnings("UnusedParameters") RequestInfo<?> request) {
        return null;
    }

    /**
     * Override this if you want to use a custom content serializer for this endpoint when the response is output. You
     * can safely return null - if this returns null then the {@link ServerConfig#defaultResponseContentSerializer()}
     * will be used.
     */
    default ObjectMapper customResponseContentSerializer(@SuppressWarnings("UnusedParameters") RequestInfo<?> request) {
        return null;
    }

}
