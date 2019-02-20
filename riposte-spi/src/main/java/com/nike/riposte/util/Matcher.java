package com.nike.riposte.util;

import com.nike.riposte.server.http.RequestInfo;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Optional;

import io.netty.handler.codec.http.HttpMethod;

/**
 * Interface for determining whether a request should be matched to a given endpoint or not. You can call {@link
 * #matchesPath(RequestInfo)} to see if the endpoint matches the given request's path, and {@link
 * #matchesMethod(RequestInfo)} to see if the endpoint matches the given request's HTTP method. If both method calls
 * return true then the endpoint wants to handle the request.
 */
public interface Matcher {

    /**
     * @return The collection of explicitly defined HTTP methods that this instance matches against. NOTE: This is just
     * the *explicitly defined* HTTP methods, so it's entirely possible for this to be empty and still have {@link
     * #isMatchAllMethods()} return true. This means {@link #isMatchAllMethods()} should be checked first, and this
     * method is only relevant if {@link #isMatchAllMethods()} returns false. This should never return null.
     */
    @NotNull Collection<HttpMethod> matchingMethods();

    /**
     * @return The path templates that this instance matches against. This is for informational purposes. Rely on
     * matchesPath to find the path that matches your request. This should never return null.
     */
    @NotNull Collection<String> matchingPathTemplates();

    /**
     * @return An optional string of the pattern matched if this instance matches the path from the given request. If
     * this returns a string then {@link #matchesMethod(RequestInfo)} should be checked next to see if the endpoint
     * attached to this instance should handle the request. If this returns empty then the endpoint attached to this
     * instance should not handle the request. This should never return null.
     */
    @NotNull Optional<String> matchesPath(@NotNull RequestInfo<?> request);

    /**
     * @return true if this instance handles the HTTP method from the given request, false otherwise. If this returns
     * false then the endpoint attached to this instance should not handle the request.
     */
    boolean matchesMethod(@NotNull RequestInfo<?> request);

    /**
     * @return true if this instance wants to match all HTTP methods, false otherwise. If this returns true then {@link
     * #matchingMethods()} should be ignored. {@link #matchingMethods()} is only relevant if this method returns false.
     */
    boolean isMatchAllMethods();

    /**
     * Convenience function to create a SingleMatcher from a pattern
     */
    static @NotNull Matcher match(@NotNull String matchingPathTemplate) {
        return SingleMatcher.match(matchingPathTemplate);
    }

    /**
     * Convenience function to create a SingleMatcher from a pattern and varargs of HttpMethod
     */
    static @NotNull Matcher match(
        @NotNull String matchingPathTemplate,
        @NotNull HttpMethod... matchingMethods
    ) {
        return SingleMatcher.match(matchingPathTemplate, matchingMethods);
    }

    /**
     * Convenience function to create a SingleMatcher from a pattern and collection of HttpMethods
     */
    static @NotNull Matcher match(
        @NotNull String matchingPathTemplate,
        @NotNull Collection<HttpMethod> matchingMethods
    ) {
        return SingleMatcher.match(matchingPathTemplate, matchingMethods);
    }

    /**
     * Convenience function to create a MultiMatcher from a Collection of patterns. First match is used so and ordered
     * collection may be needed if a path could match a more general pattern.
     */
    static @NotNull Matcher multiMatch(@NotNull Collection<String> matchingPathTemplates) {
        return MultiMatcher.match(matchingPathTemplates);
    }

    /**
     * Convenience function to create a MultiMatcher from a Collection of patterns and varargs of HttpMethod.
     * First match is used so and ordered collection may be needed if a path could match a more general pattern.
     */
    static @NotNull Matcher multiMatch(
        @NotNull Collection<String> matchingPathTemplates,
        @NotNull HttpMethod... matchingMethods
    ) {
        return MultiMatcher.match(matchingPathTemplates, matchingMethods);
    }

    /**
     * Convenience function to create a MultiMatcher from a Collection of patterns and collection of HttpMethods.
     * First match is used so and ordered collection may be needed if a path could match a more general pattern.
     */
    static @NotNull Matcher multiMatch(
        @NotNull Collection<String> matchingPathTemplates,
        @NotNull Collection<HttpMethod> matchingMethods
    ) {
        return MultiMatcher.match(matchingPathTemplates, matchingMethods);
    }
}
