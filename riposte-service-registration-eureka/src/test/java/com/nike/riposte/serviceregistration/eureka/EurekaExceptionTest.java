package com.nike.riposte.serviceregistration.eureka;

import org.junit.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link EurekaException}.
 *
 * @author Nic Munroe
 */
public class EurekaExceptionTest {

    @Test
    public void constructor_works_as_expected() {
        // given
        String msg = UUID.randomUUID().toString();
        Throwable cause = mock(Throwable.class);

        // when
        EurekaException ex = new EurekaException(msg, cause);

        // then
        assertThat(ex).hasMessage(msg).hasCause(cause);
    }

}