package com.nike.riposte.server.error.exception;

import org.junit.Test;

import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link com.nike.riposte.server.error.exception.MethodNotAllowed405Exception}
 *
 * @author Nic Munroe
 */
public class MethodNotAllowed405ExceptionTest {

    @Test
    public void should_honor_constructor_params() {
        //given
        String requestPath = UUID.randomUUID().toString();
        String requestMethod = UUID.randomUUID().toString();
        String message = UUID.randomUUID().toString();

        //when
        MethodNotAllowed405Exception ex = new MethodNotAllowed405Exception(message, requestPath, requestMethod);

        //then
        assertThat(ex.getMessage(), is(message));
        assertThat(ex.requestPath, is(requestPath));
        assertThat(ex.requestMethod, is(requestMethod));
    }

}
