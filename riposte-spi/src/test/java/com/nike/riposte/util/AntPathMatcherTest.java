package com.nike.riposte.util;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.Comparator;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link com.nike.riposte.util.AntPathMatcher}.
 */
@RunWith(DataProviderRunner.class)
public class AntPathMatcherTest {

    private AntPathMatcher matcher = new AntPathMatcher();

    @Test
    public void setPathSeparator_works() {
        // given
        String newPathSeparator = UUID.randomUUID().toString();

        // when
        matcher.setPathSeparator(newPathSeparator);

        // then
        assertThat(Whitebox.getInternalState(matcher, "pathSeparator"), is(newPathSeparator));
    }

    @Test
    public void setPathSeparator_uses_default_if_passed_null() {
        // when
        matcher.setPathSeparator(null);

        // then
        assertThat(Whitebox.getInternalState(matcher, "pathSeparator"), is(AntPathMatcher.DEFAULT_PATH_SEPARATOR));
    }

    @Test
    public void setTrimTokens_works() {
        // given
        boolean newValue = false;

        // when
        matcher.setTrimTokens(newValue);

        // then
        assertThat(Whitebox.getInternalState(matcher, "trimTokens"), is(newValue));
    }

    @Test
    public void setCachePatterns_works() {
        // given
        boolean newValue = false;

        // when
        matcher.setCachePatterns(newValue);

        // then
        assertThat(Whitebox.getInternalState(matcher, "cachePatterns"), is(newValue));
    }

    @Test
    @DataProvider(value = {
            "/some/{path}/foo       |   /some/bar/foo           |   true",
            "/some/{path}/f?o       |   /some/bar/fio           |   true",
            "/some/{:path}/foo      |   /some/bar/foo           |   false",
            "some/{path}/foo        |   /some/bar/foo           |   false",
            "/some/{path}/foo       |   some/bar/foo            |   false",
            "/some/{path}/foo/      |   /some/bar/foo           |   false",
            "/some/{path}/foo       |   /some/bar/foo/          |   false",
            "/some/{path}/foo/      |   /some/bar/foo/          |   true",
            "/some/{path}/foo       |   /some/bar/foo/no        |   false",
            "/bef{path}aft          |   /befTHINGaft            |   true",
            "/bef{path}aft          |   /befTHINGaft/whoops     |   false",
            "/foo/all.{the}.things  |   /foo/all.bar.things     |   true",
            "/foo/all.{the}.things  |   /foo/no/all.bar.things  |   false",
            "/foo/all.{the}.*/**    |   /foo/all..bar.aa/ee/oo  |   true",
            "/foo/all.{the}.*/**    |   /foo/all..bar.          |   true",
            "/foo/**/bar/**         |   /foo/it/bar/stuff/whee  |   true",
            "/foo/**/bar/**         |   /foo/it/barwhoops/it    |   false",
            "/foo/**/bar/**/whee    |   /foo/it/bar/stuff/whee  |   true",
            "/foo/**/bar/**baz**    |   /foo/it/bar/1baz2       |   true",
            "/foo/**/bar/**baz**    |   /foo/it/bar/baz2        |   true",
            "/foo/**/bar/**baz**    |   /foo/it/bar/1baz        |   true",
            "/foo/**/bar/**baz**    |   /foo/it/bar/blegh       |   false"
    }, splitBy = "\\|")
    public void match_method_works_as_expected_for_known_data(String pattern, String path, boolean expectedMatchValue) {
        // expect
        assertThat(matcher.match(pattern, path), is(expectedMatchValue));
    }

    @Test
    @DataProvider(value = {
            "/some/{path}/foo       |   /some/bar/foo           |   true",
            "/some/{path}/f?o       |   /some/bar/fio           |   true",
            "some/{path}/foo        |   /some/bar/foo           |   false",
            "/some/{path}/foo       |   some/bar/foo            |   false",
            "/some/{path}/foo/      |   /some/bar/foo           |   false",
            "/some/{path}/foo       |   /some/bar/foo/          |   false",
            "/some/{path}/foo/      |   /some/bar/foo/          |   true",
            "/some/{path}/foo       |   /some/bar/foo/no        |   false",
            "/bef{path}aft          |   /befTHINGaft            |   true",
            "/bef{path}aft          |   /befTHINGaft/whoops     |   false",
            "/foo/all.{the}.things  |   /foo/all.bar.things     |   true",
            "/foo/all.{the}.things  |   /foo/no/all.bar.things  |   false",
            "/foo/all.{the}.*/**    |   /foo/all..bar.aa/ee/oo  |   true",
            "/foo/all.{the}.*/**    |   /foo/all..bar.          |   true",
            "/foo/**/bar/**         |   /foo/it/bar/stuff/whee  |   true",
            "/foo/**/bar/**         |   /foo/it/barwhoops/it    |   true",
            "/foo/**/bar/**/whee    |   /foo/it/bar/stuff/whee  |   true",
            "/foo/**/bar/**baz**    |   /foo/it/bar/1baz2       |   true",
            "/foo/**/bar/**baz**    |   /foo/it/bar/baz2        |   true",
            "/foo/**/bar/**baz**    |   /foo/it/bar/1baz        |   true",
            "/foo/**/bar/**baz**    |   /foo/it/bar/blegh       |   true"
    }, splitBy = "\\|")
    public void matchStart_method_works_as_expected_for_known_data(String pattern, String path, boolean expectedMatchValue) {
        // expect
        assertThat(matcher.matchStart(pattern, path), is(expectedMatchValue));
    }

    @Test
    @DataProvider(value = {
            "/docs/cvs/commit.html  |   /docs/cvs/commit.html   |   ",
            "/docs/*                |   /docs/cvs/commit        |   cvs/commit",
            "/docs/cvs/*.html       |   /docs/cvs/commit.html   |   commit.html",
            "/docs/**               |   /docs/cvs/commit        |   cvs/commit",
            "/docs/**/*.html        |   /docs/cvs/commit.html   |   cvs/commit.html",
            "/*.html                |   /docs/cvs/commit.html   |   docs/cvs/commit.html",
            "*.html                 |   /docs/cvs/commit.html   |   /docs/cvs/commit.html",
            "*                      |   /docs/cvs/commit.html   |   /docs/cvs/commit.html"
    }, splitBy = "\\|")
    public void extractPathWithinPattern_works_as_expected_for_known_data(String pattern, String path, String expected) {
        // expect
        assertThat(matcher.extractPathWithinPattern(pattern, path), is(expected));
    }

    @Test
    @DataProvider(value = {
            "           |                   |   ",
            "/hotels    |   null            |   /hotels",
            "null       |   /hotels         |   /hotels",
            "/hotels    |   /bookings       |   /hotels/bookings",
            "/hotels    |   bookings        |   /hotels/bookings",
            "/hotels/*  |   /bookings       |   /hotels/bookings",
            "/hotels/** |   /bookings       |   /hotels/**/bookings",
            "/hotels    |   {hotel}         |   /hotels/{hotel}",
            "/hotels/*  |   {hotel}         |   /hotels/{hotel}",
            "/hotels/** |   {hotel}         |   /hotels/**/{hotel}",
            "/*.html    |   /hotels.html    |   /hotels.html",
            "/*.html    |   /hotels         |   /hotels.html",
            "/*         |   /hotel          |   /hotel",
            "/*.*       |   /*.html         |   /*.html",
            "/usr       |   /user           |   /usr/user",
            "/{foo}     |   /bar            |   /{foo}/bar",
            "foo        |   foo             |   foo/foo",
            "/*.html    |   bar.things      |   bar.html",
    }, splitBy = "\\|")
    public void combine_works_as_expected_for_known_data(String pattern1, String pattern2, String expected) {
        // expect
        assertThat(matcher.combine(pattern1, pattern2), is(expected));
    }

    @Test
    public void hasText_returns_false_for_whitespace() {
        // expect
        assertThat(AntPathMatcher.hasText(" "), is(false));
    }

    @Test
    @DataProvider(value = {
            "null       |   null        |   0",
            "blah       |   null        |   0",
            "null       |   blah        |   0",
            "           |   blah        |   0",
            "blah       |               |   0",
            "foofoofoo  |   foo         |   3"
    }, splitBy = "\\|")
    public void countOccurrencesOf_works_as_expected_for_known_data(String str, String sub, int expected) {
        // expect
        assertThat(AntPathMatcher.countOccurrencesOf(str, sub), is(expected));
    }

    @Test
    @DataProvider(value = {
            "null       |   null        |   0",
            "/**        |   /**         |   0",
            "/**        |   blah        |   1",
            "blah       |   null        |   -1",
            "foo        |   foo         |   0",
            "foo        |   blah        |   -1",
            "blah       |   foo         |   1",
            "a*b.*      |   z*y*x{w}    |   -2",
            "bla        |   blahh       |   2",
            "a{b}       |   a*          |   -1",
            "a*         |   a{b}        |   1",
            "a{{}bc     |   a{{c        |   0"
    }, splitBy = "\\|")
    public void AntPatternComparator_works_as_expected_for_known_data(String pattern1, String pattern2, int expected) {
        // given
        Comparator<String> comparator = matcher.getPatternComparator("foo");

        // expect
        assertThat(comparator.compare(pattern1, pattern2), is(expected));
    }

    @Test
    @DataProvider(value = {
            "bla*h      |   true",
            "bla?h      |   true",
            "blah       |   false"
    }, splitBy = "\\|")
    public void isPattern_works_as_expected_for_known_data(String path, boolean expected) {
        // expect
        assertThat(matcher.isPattern(path), is(expected));
    }

    @Test
    public void toStringArray_returns_null_if_passed_null() {
        // expect
        assertThat(AntPathMatcher.toStringArray(null), nullValue());
    }

    @Test
    public void tokenizeToStringArray_returns_null_if_passed_null() {
        // expect
        assertThat(AntPathMatcher.tokenizeToStringArray(null, null, false, false), nullValue());
    }

    @Test(expected = IllegalStateException.class)
    public void assertState_throws_IllegalStateException_if_passed_false_expression() {
        // expect
        AntPathMatcher.assertState(false, "blah");
    }

    @Test(expected = IllegalArgumentException.class)
    public void assertIsTrue_throws_IllegalArgumentException_if_passed_false_expression() {
        // expect
        AntPathMatcher.assertIsTrue(false, "blah");
    }
}