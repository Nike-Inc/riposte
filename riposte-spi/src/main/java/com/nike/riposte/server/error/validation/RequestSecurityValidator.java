package com.nike.riposte.server.error.validation;

import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;

import java.util.Collection;

/**
 * Interface for a request security validator. Concrete implementations might perform Authorization header validation,
 * or custom validation based on specific object types, or anything else your application needs. If an endpoint wants
 * the request security context stored for arbitrary later use it should do so by adding the request security context as
 * a request attribute via {@link RequestInfo#addRequestAttribute(String, Object)} using {@link
 * #REQUEST_SECURITY_ATTRIBUTE_KEY} as the attribute key.
 * <p/>
 * IMPORTANT NOTE: If the security validation performs any non-trivial work (e.g. anything cryptographic) that would
 * take more than half a millisecond or so, then you *might* want to indicate it's long-running by having {@link
 * #isFastEnoughToRunOnNettyWorkerThread()} return false, although in practice you should performance test under
 * realistic traffic patterns to verify it is actually better to run asynchronously (by returning false), as sometimes
 * the results are counterintuitive and it may still be better to run synchronously on the Netty worker thread. If the
 * security validation work is fast enough to finish in under that amount of time on average then you can almost surely
 * return true instead and not worry about blocking the Netty I/O worker threads to the point where it becomes a
 * bottleneck.
 *
 * @author Florin Dragu
 */
public interface RequestSecurityValidator {

    /**
     * The recommended attribute key to use when calling {@link RequestInfo#addRequestAttribute(String, Object)} to
     * store any security context information for later use.
     */
    String REQUEST_SECURITY_ATTRIBUTE_KEY = "REQUEST_SECURITY_ATTRIBUTE";

    /**
     * Performs security validation on the given request {@link RequestInfo}, for the given {@link Endpoint}.
     */
    void validateSecureRequestForEndpoint(RequestInfo<?> requestInfo, Endpoint<?> endpoint);

    /**
     * The collection of endpoints that should be run through this security validator.
     */
    Collection<Endpoint<?>> endpointsToValidate();

    /**
     * @return true if this security validator is fast enough that {@link #validateSecureRequestForEndpoint(RequestInfo,
     * Endpoint)} can run without unnecessarily blocking Netty worker threads to the point it becomes a bottleneck and
     * adversely affecting throughput, false otherwise when {@link #validateSecureRequestForEndpoint(RequestInfo,
     * Endpoint)} should be run asynchronously off the Netty worker thread. Defaults to true because security validators
     * are usually actively crunching numbers and the cost of context switching to an async thread is often worse than
     * just doing the work on the Netty worker thread. <b>Bottom line: This is affected heavily by numerous factors and
     * your specific use case - you should test under high load with this turned on and off for your security validator
     * and see which one causes better behavior.</b>
     */
    default boolean isFastEnoughToRunOnNettyWorkerThread() {
        return true;
    }
}
