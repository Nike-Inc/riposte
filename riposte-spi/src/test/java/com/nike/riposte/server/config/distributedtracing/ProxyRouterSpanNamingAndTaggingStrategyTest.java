package com.nike.riposte.server.config.distributedtracing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link ProxyRouterSpanNamingAndTaggingStrategy}.
 *
 * @author Nic Munroe
 */
public class ProxyRouterSpanNamingAndTaggingStrategyTest {
    
    @Test
    public void default_annotation_method_implementations_return_expected_values() {
        // given
        ProxyRouterSpanNamingAndTaggingStrategy<?> defaultImpl = new DefaultProxyRouterSpanNamingAndTaggingStrategy();
        HttpResponse responseMock = mock(HttpResponse.class);
        Throwable errorMock = mock(Throwable.class);

        // expect
        assertThat(defaultImpl.shouldAddConnStartAnnotation()).isTrue();
        assertThat(defaultImpl.connStartAnnotationName()).isEqualTo("conn.start");
        assertThat(defaultImpl.shouldAddConnFinishAnnotation()).isTrue();
        assertThat(defaultImpl.connFinishAnnotationName()).isEqualTo("conn.finish");

        verifyZeroInteractions(responseMock, errorMock);
    }

    private static class DefaultProxyRouterSpanNamingAndTaggingStrategy<S>
        extends ProxyRouterSpanNamingAndTaggingStrategy<S> {

        @Override
        public @Nullable String doGetInitialSpanName(@NotNull HttpRequest request) { return null; }

        @Override
        public void doChangeSpanName(@NotNull S span, @NotNull String newName) { }

        @Override
        public void doHandleResponseTaggingAndFinalSpanName(
            @NotNull S span, @Nullable HttpRequest request, @Nullable HttpResponse response,
            @Nullable Throwable error
        ) { }

        @Override
        public void doHandleRequestTagging(@NotNull S span, @NotNull HttpRequest request) { }
    }
}