package com.nike.riposte.server.error.exception;

import org.junit.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link RequestTooBigException}.
 *
 * @author Nic Munroe
 */
public class RequestTooBigExceptionTest {

    @Test
    public void no_arg_constructor_works_as_expected() {
        // given
        RequestTooBigException ex = new RequestTooBigException();

        // expect
        assertThat(ex)
            .hasMessage(null)
            .hasNoCause();
    }

    @Test
    public void message_only_constructor_works_as_expected() {
        // given
        String message = UUID.randomUUID().toString();
        RequestTooBigException ex = new RequestTooBigException(message);

        // expect
        assertThat(ex)
            .hasMessage(message)
            .hasNoCause();
    }

    @Test
    public void cause_only_constructor_works_as_expected() {
        // given
        Throwable cause = mock(Throwable.class);
        RequestTooBigException ex = new RequestTooBigException(cause);

        // expect
        assertThat(ex)
            .hasMessage(cause.toString())
            .hasCause(cause);
    }

    @Test
    public void kitchen_sink_constructor_works_as_expected() {
        // given
        String message = UUID.randomUUID().toString();
        Throwable cause = mock(Throwable.class);
        RequestTooBigException ex = new RequestTooBigException(message, cause);

        // expect
        assertThat(ex)
            .hasMessage(message)
            .hasCause(cause);
    }

}