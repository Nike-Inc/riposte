package com.nike.riposte.util;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import com.nike.riposte.testutils.Whitebox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import io.netty.handler.codec.http.HttpMethod;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link MultiMatcher}
 */
@RunWith(DataProviderRunner.class)
public class MultiMatcherTest {

    @SuppressWarnings("ConstantConditions")
    @Test
    public void constructor_sets_fields_as_expected() {
        // given
        String path1 = "/" + UUID.randomUUID().toString();
        String path2 = "/" + UUID.randomUUID().toString();
        Collection<String> paths = new ArrayList<String>() {{ add(path1); add(path2); }};
        Collection<HttpMethod> methods = Arrays.asList(HttpMethod.CONNECT, HttpMethod.PATCH);
        boolean matchAllMethods = false;

        // when
        MultiMatcher matcher = new MultiMatcher(paths, methods, matchAllMethods);

        // then
        assertThat(matcher.matchingPathTemplates(), is(paths));
        assertThat(matcher.matchingMethods(), is(methods));
        assertThat(matcher.isMatchAllMethods(), is(matchAllMethods));
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_on_null_or_empty_path_template(boolean useNull) {
        // given
        Collection<String> pathTemplates = (useNull) ? null : Collections.emptyList();
        // expect
        new MultiMatcher(pathTemplates, Collections.emptyList(), false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_on_path_template_that_does_not_start_with_forward_slash() {
        Collection<String> paths = new ArrayList<String>() {{ add("whoops"); }};
        // expect
        new MultiMatcher(paths, Collections.emptyList(), false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_throws_IllegalArgumentException_on_null_method_collection() {
        Collection<String> paths = new ArrayList<String>() {{ add("/some/path"); }};
        // expect
        new MultiMatcher(paths, null, false);
    }

    @Test
    public void static_factory_with_path_arg_only_sets_values_as_expected() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        Collection<String> paths = new ArrayList<String>() {{ add(path); }};

        // when
        MultiMatcher matcher = MultiMatcher.match(paths);

        // then
        assertThat(matcher.matchingPathTemplates(), is(paths));
        assertThat(matcher.matchingMethods(), notNullValue());
        assertThat(matcher.matchingMethods().isEmpty(), is(true));
        assertThat(matcher.isMatchAllMethods(), is(true));
    }

    @Test
    public void static_factory_with_path_and_methods_varargs_sets_values_as_expected() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        Collection<String> paths = new ArrayList<String>() {{ add(path); }};

        HttpMethod[] methodVarargs = new HttpMethod[]{HttpMethod.GET, HttpMethod.PUT};

        // when
        MultiMatcher matcher = MultiMatcher.match(paths, methodVarargs);

        // then
        assertThat(matcher.matchingPathTemplates(), is(paths));
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
        Collection<String> paths = new ArrayList<String>() {{ add(path); }};

        // expect
        MultiMatcher.match(paths, (HttpMethod[]) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void static_factory_with_path_and_methods_varargs_throws_IllegalArgumentException_if_passed_empty_vararg_array() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        Collection<String> paths = new ArrayList<String>() {{ add(path); }};

        HttpMethod[] methodVarargs = new HttpMethod[0];

        // expect
        MultiMatcher.match(paths, methodVarargs);
    }

    @Test
    public void static_factory_with_path_and_methods_collection_sets_values_as_expected() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        Collection<String> paths = new ArrayList<String>() {{ add(path); }};
        Collection<HttpMethod> methodCollection = Arrays.asList(HttpMethod.GET, HttpMethod.PUT);

        // when
        MultiMatcher matcher = MultiMatcher.match(paths, methodCollection);

        // then
        assertThat(matcher.matchingPathTemplates(), is(paths));
        assertThat(matcher.matchingMethods(), is(methodCollection));
        assertThat(matcher.isMatchAllMethods(), is(false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void static_factory_with_path_and_methods_collection_throws_IllegalArgumentException_if_passed_null_collection() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        Collection<String> paths = new ArrayList<String>() {{ add(path); }};

        // expect
        MultiMatcher.match(paths, (Collection<HttpMethod>) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void static_factory_with_path_and_methods_collection_throws_IllegalArgumentException_if_passed_empty_collection() {
        // given
        String path = "/" + UUID.randomUUID().toString();
        Collection<String> paths = new ArrayList<String>() {{ add(path); }};
        Collection<HttpMethod> methodCollection = Collections.emptyList();

        // expect
        MultiMatcher.match(paths, methodCollection);
    }

    @Test
    public void matchesPath_returns_empty_if_request_is_null() {
        // given
        String path = "/some/path";
        Collection<String> paths = new ArrayList<String>() {{ add(path); }};
        MultiMatcher matcher = MultiMatcher.match(paths);

        // expect
        assertThat(matcher.matchesPath(null), is(Optional.empty()));
    }

    @DataProvider
    public static Object[][] knownData() {
        // @formatter:off
        return new Object[][] {
                { Arrays.asList("/some/path"),                null,                      Optional.empty() },
                { Arrays.asList("/some/{path}/foo"),          "/some/bar/foo",           Optional.of("/some/{path}/foo") },
                { Arrays.asList("/some/{path}/foo/"),         "/some/bar/foo",           Optional.of("/some/{path}/foo") },
                { Arrays.asList("/some/{path}/foo"),          "/some/bar/foo/",          Optional.of("/some/{path}/foo") },
                { Arrays.asList("/some/{path}/foo/"),         "/some/bar/foo/",          Optional.of("/some/{path}/foo") },
                { Arrays.asList("/some/{path}/foo"),          "/some/bar/foo/no",        Optional.empty() },
                { Arrays.asList("/bef{path}aft"),             "/befTHINGaft",            Optional.of("/bef{path}aft") },
                { Arrays.asList("/bef{path}aft"),             "/befTHINGaft/whoops",     Optional.empty() },
                { Arrays.asList("/foo/all.{the}.things"),     "/foo/all.bar.things",     Optional.of("/foo/all.{the}.things") },
                { Arrays.asList("/foo/all.{the}.things"),     "/foo/no/all.bar.things",  Optional.empty() },
                { Arrays.asList("/foo/all.{the}.*/**"),       "/foo/all..bar.aa/ee/oo",  Optional.of("/foo/all.{the}.*/**") },
                { Arrays.asList("/foo/miss", "/foo/hit"),     "/foo/hit",                Optional.of("/foo/hit") },
                { Arrays.asList("/foo/miss","/foo/heretoo"),  "/foo/hit",                Optional.empty() }
        };
        // @formatter:on
    }

    @Test
    @UseDataProvider("knownData")
    public void matchesPath_works_as_expected_for_known_data(List<String> matcherPathTemplates, String requestPath, Optional<String> expectedMatchValue) {
        // given
        MultiMatcher matcher = MultiMatcher.match(matcherPathTemplates);
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        Whitebox.setInternalState(requestInfo, "path", requestPath);

        // expect
        assertThat(matcher.matchesPath(requestInfo), is(expectedMatchValue));
    }

    @Test
    public void matchesMethod_returns_true_if_matchAllMethods_is_true() {
        // given
        MultiMatcher matcher = MultiMatcher.match(Collections.singleton("/foo"));

        // expect
        assertThat(matcher.matchesMethod(null), is(true));
    }

    @Test
    public void matchesMethod_returns_false_if_request_is_null() {
        // given
        MultiMatcher matcher = MultiMatcher.match(Collections.singleton("/foo"), HttpMethod.GET);

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
        Collection<String> paths = new ArrayList<String>() {{ add("/foo"); }};
        MultiMatcher matcher = MultiMatcher.match(paths, matcherMethods);
        RequestInfo<?> requestInfo = RequestInfoImpl.dummyInstanceForUnknownRequests();
        if (requestMethodString == null)
            Whitebox.setInternalState(requestInfo, "method", null);
        else
            Whitebox.setInternalState(requestInfo, "method", HttpMethod.valueOf(requestMethodString));

        // expect
        assertThat(matcher.matchesMethod(requestInfo), is(expectedMatchValue));
    }
}