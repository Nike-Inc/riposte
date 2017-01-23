package com.nike.riposte.server.error.exception;

import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class Forbidden403ExceptionTest {

    @Before
    public void setUp() throws Exception {
    }

    @Test
    public void should_honor_constructor_params() {
        //given
        String requestPath = UUID.randomUUID().toString();
        String authorizationHeader = UUID.randomUUID().toString();
        String message = UUID.randomUUID().toString();

        //when
        Forbidden403Exception ex = new Forbidden403Exception(message, requestPath, authorizationHeader);

        //then
        assertThat(ex.getMessage(), is(message));
        assertThat(ex.requestPath, is(requestPath));
        assertThat(ex.authorizationHeader, is(authorizationHeader));
    }

}
