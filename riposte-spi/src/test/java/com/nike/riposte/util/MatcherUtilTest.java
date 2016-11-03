package com.nike.riposte.util;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link MatcherUtil}
 */
public class MatcherUtilTest {

    @Test
    public void code_coverage_hoops() {
        // jump!
        new MatcherUtil();
    }

    @Test
    public void stripEndSlash_should_strip_an_end_slash() {
        String path = "/something/ending/in/slash/";
        assertThat(MatcherUtil.stripEndSlash(path), is("/something/ending/in/slash"));
    }
}
