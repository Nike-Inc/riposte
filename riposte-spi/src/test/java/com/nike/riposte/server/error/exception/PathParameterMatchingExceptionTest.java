package com.nike.riposte.server.error.exception;

import org.junit.Test;

import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link com.nike.riposte.server.error.exception.PathParameterMatchingException}
 */
public class PathParameterMatchingExceptionTest {

    @Test
    public void should_honor_constructor_params() {
        //given
        String message = UUID.randomUUID().toString();
        String pathTemplate = UUID.randomUUID().toString();
        String nonMatchingPath = UUID.randomUUID().toString();

        //when
        PathParameterMatchingException ex = new PathParameterMatchingException(message, pathTemplate, nonMatchingPath);

        //then
        assertThat(ex.getMessage(), is(message));
        assertThat(ex.pathTemplate, is(pathTemplate));
        assertThat(ex.nonMatchingUriPath, is(nonMatchingPath));
    }

}