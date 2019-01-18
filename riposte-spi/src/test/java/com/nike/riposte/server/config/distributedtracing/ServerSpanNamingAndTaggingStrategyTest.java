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
    public void default_annotation_method_implementations_return_expected_values() {
        // given
        ServerSpanNamingAndTaggingStrategy<?> defaultImpl = new DefaultServerSpanNamingAndTaggingStrategy();
        ResponseInfo<?> responseMock = mock(ResponseInfo.class);
        Throwable errorMock = mock(Throwable.class);

        // expect
        assertThat(defaultImpl.shouldAddEndpointStartAnnotation()).isTrue();
        assertThat(defaultImpl.endpointStartAnnotationName()).isEqualTo("endpoint.start");
        assertThat(defaultImpl.shouldAddEndpointFinishAnnotation()).isTrue();
        assertThat(defaultImpl.endpointFinishAnnotationName()).isEqualTo("endpoint.finish");

        verifyZeroInteractions(responseMock, errorMock);
    }

    private static class DefaultServerSpanNamingAndTaggingStrategy<S> extends ServerSpanNamingAndTaggingStrategy<S> {

        @Override
        public @Nullable String doGetInitialSpanName(@NotNull RequestInfo<?> request) { return null; }

        @Override
        public void doChangeSpanName(@NotNull S span, @NotNull String newName) { }

        @Override
        public void doHandleResponseTaggingAndFinalSpanName(
            @NotNull S span, @Nullable RequestInfo<?> request, @Nullable ResponseInfo<?> response,
            @Nullable Throwable error
        ) { }

        @Override
        public void doHandleRequestTagging(@NotNull S span, @NotNull RequestInfo<?> request) { }
    }

}