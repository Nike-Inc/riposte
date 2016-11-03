package com.nike.riposte.server.error.exception;

import org.junit.Test;

import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link com.nike.riposte.server.error.exception.UnexpectedMajorErrorHandlingError}
 */
public class UnexpectedMajorErrorHandlingErrorTest {

    @Test
    public void should_honor_constructor_params() {
        //given
        String message = UUID.randomUUID().toString();
        Throwable cause = new Exception("kaboom");

        //when
        UnexpectedMajorErrorHandlingError ex = new UnexpectedMajorErrorHandlingError(message, cause);

        //then
        assertThat(ex.getMessage(), is(message));
        assertThat(ex.getCause(), is(cause));
    }

}