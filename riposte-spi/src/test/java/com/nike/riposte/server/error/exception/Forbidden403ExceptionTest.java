package com.nike.riposte.server.error.exception;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

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
