package com.nike.riposte.server.error.exception;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.impl.RequestInfoImpl;

import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.Test;

import java.util.UUID;

import io.netty.handler.codec.http.HttpMethod;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link com.nike.riposte.server.error.exception.RequestContentDeserializationException}
 */
public class RequestContentDeserializationExceptionTest {

    @Test
    public void should_honor_constructor_params() {
        //given
        String message = UUID.randomUUID().toString();
        Throwable cause = new Exception("kaboom");
        RequestInfo<?> requestInfo = new RequestInfoImpl<>("/some/uri/path", HttpMethod.PATCH, null, null, null, null, null, null, null, false, true, false);
        TypeReference<?> typeReferenceMock = mock(TypeReference.class);

        //when
        RequestContentDeserializationException ex = new RequestContentDeserializationException(message, cause, requestInfo, typeReferenceMock);

        //then
        assertThat(ex.getMessage(), is(message));
        assertThat(ex.getCause(), is(cause));
        assertThat(ex.httpMethod, is(requestInfo.getMethod().name()));
        assertThat(ex.requestPath, is(requestInfo.getPath()));
        assertThat(ex.desiredObjectType, is(typeReferenceMock));
    }

}