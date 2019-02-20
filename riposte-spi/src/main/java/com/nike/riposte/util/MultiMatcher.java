package com.nike.riposte.util;

import com.nike.riposte.server.http.RequestInfo;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Collectors;

import io.netty.handler.codec.http.HttpMethod;

/**
 * Class for determining whether a request should be matched to a given endpoint or not. You can call {@link
 * #matchesPath(RequestInfo)} to see if the endpoint matches the given request's path, and {@link
 * #matchesMethod(RequestInfo)} to see if the endpoint matches the given request's HTTP method. If both method calls
 * return true then the endpoint wants to handle the request. Order for the matchingPathTemplates collection may be
 * important depending on what your path templates are as the first one matched is used. If order is important you
 * should use a deterministic collection (List, SortedSet, etc) when creating the MultiMatcher.
 * <p/>
 * There are a few static factory methods for creating a new instance of this class using common argument patterns:
 * {@link #match(String)}, {@link #match(Collection, HttpMethod...)}, and {@link #match(Collection, Collection)}.
 * <p/>
 * This class supports path parameters. An Ant path matcher is used to determine matches. See {@link
 * RequestInfo#getPathParams()} for a quick description of the easy/common path template structure that results in path
 * parameters.
 */
@SuppressWarnings("WeakerAccess")
public class MultiMatcher implements Matcher {

    protected static final AntPathMatcher pathParamExtractor = new AntPathMatcher();

    protected final @NotNull Collection<String> matchingPathTemplates;
    protected final @NotNull Collection<HttpMethod> matchingMethods;
    protected final boolean matchAllMethods;

    @SuppressWarnings("ConstantConditions")
    protected MultiMatcher(
        @NotNull Collection<String> matchingPathTemplates,
        @NotNull Collection<HttpMethod> matchingMethods,
        boolean matchAllMethods
    ) {
        // If the path template doesn't start with a forward slash it has no hope of ever matching any incoming request.
        if (matchingPathTemplates == null
            || matchingPathTemplates.isEmpty()
            || matchingPathTemplates.stream().anyMatch(path -> !path.startsWith("/"))) {

            throw new IllegalArgumentException("matchingPathTemplates cannot be null or empty and paths must start "
                                               + "with a forward slash '/'");
        }

        if (matchingMethods == null) {
            throw new IllegalArgumentException("matchingMethods cannot be null");
        }

        this.matchingMethods = matchingMethods;
        this.matchingPathTemplates = matchingPathTemplates.stream()
                                                          .map(MatcherUtil::stripEndSlash)
                                                          .collect(Collectors.toList());
        this.matchAllMethods = matchAllMethods;
    }

    /**
     * @return A new multi-matcher with the given path template that matches all HTTP methods ({@link
     * #isMatchAllMethods()} will return true).
     */
    public static @NotNull MultiMatcher match(@NotNull Collection<String> matchingPathTemplates) {
        return new MultiMatcher(matchingPathTemplates, Collections.emptyList(), true);
    }

    /**
     * @return A new multi-matcher with the given path templates that match the given HTTP methods.
     */
    public static @NotNull MultiMatcher match(
        @NotNull Collection<String> matchingPathTemplates,
        @NotNull HttpMethod... matchingMethods
    ) {
        //noinspection ConstantConditions
        if (matchingMethods == null || matchingMethods.length == 0) {
            throw new IllegalArgumentException("matchingMethods cannot be null or empty. If you want to match all "
                                               + "methods use the single-arg match(Collection<String>) method.");
        }

        return new MultiMatcher(matchingPathTemplates, Arrays.asList(matchingMethods), false);
    }

    /**
     * @return A new multi-matcher with the given path templates that match the given HTTP methods.
     */
    public static @NotNull MultiMatcher match(
        @NotNull Collection<String> matchingPathTemplates,
        @NotNull Collection<HttpMethod> matchingMethods
    ) {
        //noinspection ConstantConditions
        if (matchingMethods == null || matchingMethods.isEmpty()) {
            throw new IllegalArgumentException("matchingMethods cannot be null or empty. If you want to match all "
                                               + "methods use the single-arg match(Collection<String>) method.");
        }

        return new MultiMatcher(matchingPathTemplates, matchingMethods, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull Collection<HttpMethod> matchingMethods() {
        return matchingMethods;
    }

    /**
     * {@inheritDoc}
     */
    public @NotNull Collection<String> matchingPathTemplates() {
        return matchingPathTemplates;
    }


    /**
     * @return Returns the first pattern found in the collection that matches the given request if one exists. Order of
     * paths can be significant so when creating a MultiMatcher use an ordered collection if some path may match
     * multiple patterns.
     */
    @Override
    public @NotNull Optional<String> matchesPath(@NotNull RequestInfo<?> request) {
        //noinspection ConstantConditions
        if (request == null || request.getPath() == null)
            return Optional.empty();

        // Ignore trailing slashes on the actual path.
        String path = MatcherUtil.stripEndSlash(request.getPath());

        return matchingPathTemplates
            .stream()
            // Ignore trailing slashes on the template.
            .filter(pathTemplate -> pathParamExtractor.match(pathTemplate, path))
            .findFirst();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean matchesMethod(@NotNull RequestInfo<?> request) {
        if (matchAllMethods)
            return true;

        //noinspection ConstantConditions
        if (request == null || request.getMethod() == null)
            return false;

        return matchingMethods().contains(request.getMethod());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMatchAllMethods() {
        return matchAllMethods;
    }
}
