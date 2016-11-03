package com.nike.riposte.server.handler.base;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class PipelineContinuationBehaviorTest {

    @Test
    public void exercise_enum_methods_because_code_coverage_tools_are_stupid() {
        for (PipelineContinuationBehavior enumValue : PipelineContinuationBehavior.values()) {
            assertThat(PipelineContinuationBehavior.valueOf(enumValue.name()), is(enumValue));
        }
    }

}