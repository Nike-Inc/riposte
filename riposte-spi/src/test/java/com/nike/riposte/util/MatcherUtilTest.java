package com.nike.riposte.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(MatcherUtil.stripEndSlash(path)).isEqualTo("/something/ending/in/slash");
    }

    @Test
    public void stripEndSlash_does_nothing_if_path_is_root_path() {
        // when
        String result = MatcherUtil.stripEndSlash("/");

        // then
        assertThat(result).isEqualTo("/");
    }
}
