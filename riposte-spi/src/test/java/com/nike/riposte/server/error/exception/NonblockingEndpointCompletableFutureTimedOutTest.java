package com.nike.riposte.server.error.exception;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link com.nike.riposte.server.error.exception.NonblockingEndpointCompletableFutureTimedOut}
 */
public class NonblockingEndpointCompletableFutureTimedOutTest {

    @Test
    public void should_honor_constructor_params() {
        //given
        long timeoutValue = 42;

        //when
        NonblockingEndpointCompletableFutureTimedOut ex = new NonblockingEndpointCompletableFutureTimedOut(timeoutValue);

        //then
        assertThat(ex.timeoutValueMillis, is(timeoutValue));
    }

}