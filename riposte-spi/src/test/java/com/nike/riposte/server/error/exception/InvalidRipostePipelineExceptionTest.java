package com.nike.riposte.server.error.exception;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the functionality of {@link InvalidRipostePipelineException}.
 *
 * @author Nic Munroe
 */
public class InvalidRipostePipelineExceptionTest {

    @Test
    public void zero_arg_constructor_works() {
        // when
        InvalidRipostePipelineException ex = new InvalidRipostePipelineException();

        // then
        assertThat(ex).hasMessage(null).hasCause(null);
    }

    @Test
    public void message_constructor_works() {
        // when
        InvalidRipostePipelineException ex = new InvalidRipostePipelineException("foo");

        // then
        assertThat(ex).hasMessage("foo").hasCause(null);
    }

    @Test
    public void cause_constructor_works() {
        // given
        Throwable cause = new Exception("kaboom");

        // when
        InvalidRipostePipelineException ex = new InvalidRipostePipelineException(cause);

        // then
        assertThat(ex).hasMessage(cause.toString()).hasCause(cause);
    }

    @Test
    public void message_and_cause_constructor_works() {
        // given
        Throwable cause = new Exception("kaboom");

        // when
        InvalidRipostePipelineException ex = new InvalidRipostePipelineException("foo", cause);

        // then
        assertThat(ex).hasMessage("foo").hasCause(cause);
    }
}