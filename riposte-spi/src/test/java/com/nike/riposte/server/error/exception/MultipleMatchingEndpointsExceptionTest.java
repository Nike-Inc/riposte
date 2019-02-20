package com.nike.riposte.server.error.exception;

import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.util.Matcher;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the functionality of {@link com.nike.riposte.server.error.exception.MultipleMatchingEndpointsException}
 */
public class MultipleMatchingEndpointsExceptionTest {

    @Test
    public void should_honor_constructor_params() {
        //given
        Endpoint<?> endpoint1 = new EndpointOne();
        Endpoint<?> endpoint2 = new EndpointTwo();

        List<Endpoint<?>> endpointList = Arrays.asList(endpoint1, endpoint2);
        String requestPath = UUID.randomUUID().toString();
        String requestMethod = UUID.randomUUID().toString();
        String message = UUID.randomUUID().toString();

        //when
        MultipleMatchingEndpointsException ex = new MultipleMatchingEndpointsException(message, endpointList, requestPath, requestMethod);

        //then
        assertThat(ex.getMessage(), is(message));
        assertThat(ex.matchingEndpointsDetails, is(Arrays.asList(EndpointOne.class.getName(), EndpointTwo.class.getName())));
        assertThat(ex.requestPath, is(requestPath));
        assertThat(ex.requestMethod, is(requestMethod));

    }

    public static class EndpointOne implements Endpoint {
        @Override
        public @NotNull Matcher requestMatcher() {
            return null;
        }
    }

    public static class EndpointTwo implements Endpoint {
        @Override
        public @NotNull Matcher requestMatcher() {
            return null;
        }
    }
}