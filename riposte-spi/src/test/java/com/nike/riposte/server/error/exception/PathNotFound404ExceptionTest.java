package com.nike.riposte.server.error.exception;

import org.junit.Test;

import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link com.nike.riposte.server.error.exception.PathNotFound404Exception}
 */
public class PathNotFound404ExceptionTest {

    @Test
    public void should_honor_constructor_params() {
        //given
        String message = UUID.randomUUID().toString();

        //when
        PathNotFound404Exception ex = new PathNotFound404Exception(message);

        //then
        assertThat(ex.getMessage(), is(message));
    }

}