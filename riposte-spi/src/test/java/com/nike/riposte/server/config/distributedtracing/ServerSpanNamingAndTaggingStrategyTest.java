package com.nike.riposte.server.config.distributedtracing;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the default functionality of {@link ServerSpanNamingAndTaggingStrategy}.
 *
 * @author Nic Munroe
 */
public class ServerSpanNamingAndTaggingStrategyTest {

    @Test
    public void default_method_implementations_return_expected_values() {
        // given
        ServerSpanNamingAndTaggingStrategy<?> defaultImpl = new DefaultServerSpanNamingAndTaggingStrategy();
        ResponseInfo<?> responseMock = mock(ResponseInfo.class);
        Throwable errorMock = mock(Throwable.class);

        // expect
        assertThat(defaultImpl.shouldAddWireReceiveStartAnnotation()).isTrue();
        assertThat(defaultImpl.wireReceiveStartAnnotationName()).isEqualTo("wr.start");
        assertThat(defaultImpl.shouldAddWireReceiveFinishAnnotation()).isTrue();
        assertThat(defaultImpl.wireReceiveFinishAnnotationName()).isEqualTo("wr.finish");
        assertThat(defaultImpl.shouldAddWireSendStartAnnotation()).isTrue();
        assertThat(defaultImpl.wireSendStartAnnotationName()).isEqualTo("ws.start");
        assertThat(defaultImpl.shouldAddWireSendFinishAnnotation()).isTrue();
        assertThat(defaultImpl.wireSendFinishAnnotationName()).isEqualTo("ws.finish");
        assertThat(defaultImpl.shouldAddErrorAnnotationForCaughtException(responseMock, errorMock)).isTrue();
        assertThat(defaultImpl.errorAnnotationName(responseMock, errorMock)).isEqualTo("error");

        verifyZeroInteractions(responseMock, errorMock);
    }

    private static class DefaultServerSpanNamingAndTaggingStrategy<S> implements ServerSpanNamingAndTaggingStrategy<S> {

        @Override
        public @Nullable String getInitialSpanName(@NotNull RequestInfo<?> request) { return null; }

        @Override
        public void changeSpanName(@NotNull S span, @NotNull String newName) { }

        @Override
        public void handleResponseTaggingAndFinalSpanName(
            @NotNull S span, @Nullable RequestInfo<?> request, @Nullable ResponseInfo<?> response,
            @Nullable Throwable error
        ) { }

        @Override
        public void handleRequestTagging(@NotNull S span, @NotNull RequestInfo<?> request) { }
    }

}