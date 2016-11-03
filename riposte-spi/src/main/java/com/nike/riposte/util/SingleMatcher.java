package com.nike.riposte.util;

import com.nike.riposte.server.http.RequestInfo;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

import io.netty.handler.codec.http.HttpMethod;

/**
 * Class for determining whether a request should be matched to a given endpoint or not. You can call {@link
 * #matchesPath(RequestInfo)} to see if the endpoint matches the given request's path, and {@link
 * #matchesMethod(RequestInfo)} to see if the endpoint matches the given request's HTTP method. If both method calls
 * return true then the endpoint wants to handle the request.
 * <p/>
 * There are a few static factory methods for creating a new instance of this class using common argument patterns:
 * {@link #match(String)}, {@link #match(String, io.netty.handler.codec.http.HttpMethod...)}, and {@link #match(String,
 * java.util.Collection)}.
 * <p/>
 * This class supports path parameters. An Ant path matcher is used to determine matches. See {@link
 * RequestInfo#getPathParams()} for a quick description of the easy/common path template structure that results in path
 * parameters.
 */
@SuppressWarnings("WeakerAccess")
public class SingleMatcher implements Matcher {

    protected static final AntPathMatcher pathParamExtractor = new AntPathMatcher();

    protected final Collection<HttpMethod> matchingMethods;
    protected final String matchingPathTemplate;
    protected final boolean matchAllMethods;
    protected final Collection<String> matchingPathTemplates;
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    protected final Optional<String> cachedMatchesPathResponse;

    public SingleMatcher(String matchingPathTemplate, Collection<HttpMethod> matchingMethods, boolean matchAllMethods) {
        // If the path template doesn't start with a forward slash it has no hope of ever matching any incoming request.
        if (matchingPathTemplate == null || !matchingPathTemplate.startsWith("/")) {
            throw new IllegalArgumentException("matchingPathTemplate cannot be null and must start with a forward "
                                               + "slash '/'");
        }

        if (matchingMethods == null)
            throw new IllegalArgumentException("matchingMethods cannot be null");

        matchingPathTemplate = MatcherUtil.stripEndSlash(matchingPathTemplate);

        this.matchingMethods = matchingMethods;
        this.matchingPathTemplate = matchingPathTemplate;
        this.matchingPathTemplates = Collections.singletonList(matchingPathTemplate);
        this.matchAllMethods = matchAllMethods;
        this.cachedMatchesPathResponse = Optional.of(matchingPathTemplate);
    }

    /**
     * @return A new single matcher with the given path template that matches all HTTP methods ({@link
     * #isMatchAllMethods()} will return true).
     */
    public static SingleMatcher match(String matchingPathTemplate) {
        return new SingleMatcher(matchingPathTemplate, Collections.emptyList(), true);
    }

    /**
     * @return A new single matcher with the given path template that matches the given HTTP methods.
     */
    public static SingleMatcher match(String matchingPathTemplate, HttpMethod... matchingMethods) {
        if (matchingMethods == null || matchingMethods.length == 0) {
            throw new IllegalArgumentException("matchingMethods cannot be null or empty. If you want to match all "
                                               + "methods use the single-arg match(String) method.");
        }

        return new SingleMatcher(matchingPathTemplate, Arrays.asList(matchingMethods), false);
    }

    /**
     * @return A new single matcher with the given path template that matches the given HTTP methods.
     */
    public static SingleMatcher match(String matchingPathTemplate, Collection<HttpMethod> matchingMethods) {
        if (matchingMethods == null || matchingMethods.isEmpty()) {
            throw new IllegalArgumentException("matchingMethods cannot be null or empty. If you want to match all "
                                               + "methods use the single-arg match(String) method.");
        }

        return new SingleMatcher(matchingPathTemplate, matchingMethods, false);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<HttpMethod> matchingMethods() {
        return matchingMethods;
    }

    /**
     * {@inheritDoc}
     */
    public Collection<String> matchingPathTemplates() {
        return matchingPathTemplates;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isMatchAllMethods() {
        return matchAllMethods;
    }

    /**
     * {@inheritDoc}
     */
    public Optional<String> matchesPath(RequestInfo<?> request) {
        if (request == null || request.getPath() == null)
            return Optional.empty();

        // Ignore trailing slashes on actual path.
        String path = MatcherUtil.stripEndSlash(request.getPath());

        if (pathParamExtractor.match(matchingPathTemplate, path)) {
            return cachedMatchesPathResponse;
        }
        else {
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean matchesMethod(RequestInfo<?> request) {
        if (matchAllMethods)
            return true;

        //noinspection SimplifiableIfStatement
        if (request == null || request.getMethod() == null)
            return false;

        return matchingMethods().contains(request.getMethod());
    }
}
