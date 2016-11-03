package com.nike.riposte.util;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.*;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link SingleMatcher}
 */
@RunWith(DataProviderRunner.class)
public class SingleMatcherTest {

    @SuppressWarnings("ConstantConditions")
    @Test
    public void constructor_sets_fields_as_expected() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        Collection<HttpMethod> methods = Arrays.asList(HttpMethod.CONNECT, HttpMethod.PATCH);
        boolean matchAllMethods = false;

        // when
        SingleMatcher matcher = new SingleMatcher(path, methods, matchAllMethods);

        // then
        assertThat(matcher.matchingPathTemplates(), is(Arrays.asList(path)));
        assertThat(matcher.matchingMethods(), is(methods));
        assertThat(matcher.isMatchAllMethods(), is(matchAllMethods));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_on_null_path_template() {
        // expect
        new SingleMatcher(null, Collections.emptyList(), false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_on_path_template_that_does_not_start_with_forward_slash() {
        // expect
        new SingleMatcher("whoops", Collections.emptyList(), false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_on_null_method_collection() {
        // expect
        new SingleMatcher("/some/path", null, false);
    }

    @Test
    public void static_factory_with_path_arg_only_sets_values_as_expected() {
        // given
        String path = "/" + UUID.randomUUID().toString();

        // when
        SingleMatcher matcher = SingleMatcher.match(path);

        // then
        assertThat(matcher.matchingPathTemplates(), is(Arrays.asList(path)));
        assertThat(matcher.matchingMethods(), notNullValue());
        assertThat(matcher.matchingMethods().isEmpty(), is(true));
        assertThat(matcher.isMatchAllMethods(), is(true));
    }

    @Test
    public void static_factory_with_path_and_methods_varargs_sets_values_as_expected() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        HttpMethod[] methodVarargs = new HttpMethod[]{HttpMethod.GET, HttpMethod.PUT};

        // when
        SingleMatcher matcher = SingleMatcher.match(path, methodVarargs);

        // then
        assertThat(matcher.matchingPathTemplates(), is(Arrays.asList(path)));
        assertThat(matcher.matchingMethods(), notNullValue());
        assertThat(matcher.matchingMethods().size(), is(methodVarargs.length));
        for (HttpMethod expectedMethod : methodVarargs) {
            assertThat(matcher.matchingMethods().contains(expectedMethod), is(true));
        }
        assertThat(matcher.isMatchAllMethods(), is(false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void static_factory_with_path_and_methods_varargs_throws_IllegalArgumentException_if_passed_null_vararg_array() {
        // given
        String path = "/" + UUID.randomUUID().toString();

        // expect
        SingleMatcher.match(path, (HttpMethod[]) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void static_factory_with_path_and_methods_varargs_throws_IllegalArgumentException_if_passed_empty_vararg_array() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        HttpMethod[] methodVarargs = new HttpMethod[0];

        // expect
        SingleMatcher.match(path, methodVarargs);
    }

    @Test
    public void static_factory_with_path_and_methods_collection_sets_values_as_expected() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        Collection<HttpMethod> methodCollection = Arrays.asList(HttpMethod.GET, HttpMethod.PUT);

        // when
        SingleMatcher matcher = SingleMatcher.match(path, methodCollection);

        // then
        assertThat(matcher.matchingPathTemplates(), is(Arrays.asList(path)));
        assertThat(matcher.matchingMethods(), is(methodCollection));
        assertThat(matcher.isMatchAllMethods(), is(false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void static_factory_with_path_and_methods_collection_throws_IllegalArgumentException_if_passed_null_collection() {
        // given
        String path = "/" + UUID.randomUUID().toString();

        // expect
        SingleMatcher.match(path, (Collection<HttpMethod>) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void static_factory_with_path_and_methods_collection_throws_IllegalArgumentException_if_passed_empty_collection() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        Collection<HttpMethod> methodCollection = Collections.emptyList();

        // expect
        SingleMatcher.match(path, methodCollection);
    }

    @Test
    public void matchesPath_returns_empty_if_request_is_null() {
        // given
        SingleMatcher matcher = SingleMatcher.match("/some/path");

        // expect
        assertThat(matcher.matchesPath(null), is(Optional.empty()));
    }

    @DataProvider
    public static Object[][] knownData() {
        // @formatter:off
        return new Object[][] {
            { "/some/path",             null, Optional.empty() },
            { "/some/{path}/foo",       "/some/bar/foo", Optional.of("/some/{path}/foo") },
            { "/some/{path}/foo/",      "/some/bar/foo",           Optional.of("/some/{path}/foo") },
            { "/some/{path}/foo",       "/some/bar/foo/",          Optional.of("/some/{path}/foo") },
            { "/some/{path}/foo/",      "/some/bar/foo/",          Optional.of("/some/{path}/foo") },
            { "/some/{path}/foo",       "/some/bar/foo/no",        Optional.empty() },
            { "/bef{path}aft",          "/befTHINGaft",            Optional.of("/bef{path}aft") },
            { "/bef{path}aft",          "/befTHINGaft/whoops",     Optional.empty() },
            { "/foo/all.{the}.things",  "/foo/all.bar.things",     Optional.of("/foo/all.{the}.things") },
            { "/foo/all.{the}.things",  "/foo/no/all.bar.things",  Optional.empty() },
            { "/foo/all.{the}.*/**",    "/foo/all..bar.aa/ee/oo",  Optional.of("/foo/all.{the}.*/**") }
        };
        // @formatter:on
    }

    @Test
    @UseDataProvider("knownData")
    public void matchesPath_works_as_expected_for_known_data(String matcherPathTemplate, String requestPath, Optional<String> expectedMatchValue) {
        // given
        SingleMatcher matcher = SingleMatcher.match(matcherPathTemplate);
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Whitebox.setInternalState(requestInfo, "path", requestPath);

        // expect
        assertThat(matcher.matchesPath(requestInfo), is(expectedMatchValue));
    }

    @Test
    public void matchesMethod_returns_true_if_matchAllMethods_is_true() {
        // given
        SingleMatcher matcher = SingleMatcher.match("/foo");

        // expect
        assertThat(matcher.matchesMethod(null), is(true));
    }

    @Test
    public void matchesMethod_returns_false_if_request_is_null() {
        // given
        SingleMatcher matcher = SingleMatcher.match("/foo", HttpMethod.GET);

        // expect
        assertThat(matcher.matchesMethod(null), is(false));
    }

    @Test
    @DataProvider(value = {
            "GET,POST   |   GET     |   true",
            "GET,POST   |   POST    |   true",
            "GET,POST   |   PUT     |   false",
            "GET,POST   |   null    |   false"
    }, splitBy = "\\|")
    public void matchesMethod_works_as_expected_for_known_data(String matcherMethodStrings, String requestMethodString, boolean expectedMatchValue) {
        // given
        List<HttpMethod> matcherMethods = Arrays.asList(matcherMethodStrings.split(",")).stream().map(HttpMethod::valueOf).collect(Collectors.toList());
        SingleMatcher matcher = SingleMatcher.match("/foo", matcherMethods);
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        if (requestMethodString == null)
            Whitebox.setInternalState(requestInfo, "method", null);
        else
            Whitebox.setInternalState(requestInfo, "method", HttpMethod.valueOf(requestMethodString));

        // expect
        assertThat(matcher.matchesMethod(requestInfo), is(expectedMatchValue));
    }
}