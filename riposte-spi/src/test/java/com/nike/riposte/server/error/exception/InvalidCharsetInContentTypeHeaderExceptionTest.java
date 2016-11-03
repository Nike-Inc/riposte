package com.nike.riposte.server.error.exception;

import org.junit.Test;

import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link com.nike.riposte.server.error.exception.InvalidCharsetInContentTypeHeaderException}
 *
 * @author Nic Munroe
 */
public class InvalidCharsetInContentTypeHeaderExceptionTest {

    @Test
    public void should_honor_constructor_params() {
        //given
        String invalidHeaderValue = UUID.randomUUID().toString();
        String message = UUID.randomUUID().toString();
        Exception cause = new Exception("kaboom");

        //when
        InvalidCharsetInContentTypeHeaderException ex = new InvalidCharsetInContentTypeHeaderException(message, cause, invalidHeaderValue);

        //then
        assertThat(ex.getMessage(), is(message));
        assertThat(ex.getCause(), is(cause));
        assertThat(ex.invalidContentTypeHeader, is(invalidHeaderValue));
    }

}
