package com.nike.riposte.server.error.handler.impl;

import org.junit.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tests the functionality of {@link DelegatedErrorResponseBody}.
 *
 * @author Nic Munroe
 */
public class DelegatedErrorResponseBodyTest {

    @Test
    public void constructor_sets_fields_as_expected() {
        // given
        String errorId = UUID.randomUUID().toString();
        Object someObject = new Object();

        // when
        DelegatedErrorResponseBody impl = new DelegatedErrorResponseBody(errorId, someObject);

        // then
        assertThat(impl.errorId).isEqualTo(errorId);
        assertThat(impl.errorId()).isEqualTo(errorId);
        assertThat(impl.bodyToSerialize).isSameAs(someObject);
        assertThat(impl.bodyToSerialize()).isSameAs(someObject);
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_passed_null_errorId() {
        // when
        Throwable ex = catchThrowable(() -> new DelegatedErrorResponseBody(null, new Object()));

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("errorId cannot be null.");
    }

}
