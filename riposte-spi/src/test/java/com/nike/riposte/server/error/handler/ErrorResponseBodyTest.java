package com.nike.riposte.server.error.handler;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the default methods of {@link ErrorResponseBody}.
 *
 * @author Nic Munroe
 */
public class ErrorResponseBodyTest {
    @Test
    public void bodyToSerialize_returns_same_instance_by_default() {
        ErrorResponseBody instance = () -> "someErrorId";
        assertThat(instance.bodyToSerialize()).isSameAs(instance);
    }
}