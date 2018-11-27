package com.nike.riposte.server.config.distributedtracing;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Tests the functionality of {@link SpanNamingAndTaggingStrategy}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class SpanNamingAndTaggingStrategyTest {

    private DefaultSpanNamingAndTaggingStrategy defaultImplSpy;
    private FooRequest requestMock;
    private FooResponse responseMock;
    private FooSpan spanMock;
    private Throwable errorMock;

    @Before
    public void beforeMethod() {
        defaultImplSpy = spy(new DefaultSpanNamingAndTaggingStrategy());
        requestMock = mock(FooRequest.class);
        responseMock = mock(FooResponse.class);
        spanMock = mock(FooSpan.class);
        errorMock = mock(Throwable.class);
    }

    @Test
    public void default_annotation_method_implementations_return_expected_values() {
        // expect
        assertThat(defaultImplSpy.shouldAddWireReceiveStartAnnotation()).isTrue();
        assertThat(defaultImplSpy.wireReceiveStartAnnotationName()).isEqualTo("wr.start");
        assertThat(defaultImplSpy.shouldAddWireReceiveFinishAnnotation()).isTrue();
        assertThat(defaultImplSpy.wireReceiveFinishAnnotationName()).isEqualTo("wr.finish");
        assertThat(defaultImplSpy.shouldAddWireSendStartAnnotation()).isTrue();
        assertThat(defaultImplSpy.wireSendStartAnnotationName()).isEqualTo("ws.start");
        assertThat(defaultImplSpy.shouldAddWireSendFinishAnnotation()).isTrue();
        assertThat(defaultImplSpy.wireSendFinishAnnotationName()).isEqualTo("ws.finish");
        assertThat(defaultImplSpy.shouldAddErrorAnnotationForCaughtException(responseMock, errorMock)).isTrue();
        assertThat(defaultImplSpy.errorAnnotationName(responseMock, errorMock)).isEqualTo("error");

        verifyZeroInteractions(responseMock, errorMock);
    }

    @Test
    public void getInitialSpanName_delegates_to_doGetInitialSpanName() {
        // given
        String expectedResult = UUID.randomUUID().toString();
        doReturn(expectedResult).when(defaultImplSpy).doGetInitialSpanName(requestMock);

        // when
        String result = defaultImplSpy.getInitialSpanName(requestMock);

        // then
        assertThat(result).isEqualTo(expectedResult);
        verify(defaultImplSpy).doGetInitialSpanName(requestMock);
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(requestMock);
    }

    @Test
    public void getInitialSpanName_does_not_propagate_exception_from_doGetInitialSpanName() {
        // given
        doThrow(new RuntimeException("intentional exception"))
            .when(defaultImplSpy).doGetInitialSpanName(any(FooRequest.class));

        // when
        String result = defaultImplSpy.getInitialSpanName(requestMock);

        // then
        assertThat(result).isNull();
        verify(defaultImplSpy).doGetInitialSpanName(requestMock);
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(requestMock);
    }

    @Test
    public void getInitialSpanName_returns_null_if_passed_null() {
        // given
        doReturn("someNonNullThing").when(defaultImplSpy).doGetInitialSpanName(requestMock);

        // when
        String result = defaultImplSpy.getInitialSpanName(null);

        // then
        assertThat(result).isNull();
        verify(defaultImplSpy, never()).doGetInitialSpanName(requestMock);
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(requestMock);
    }

    @Test
    public void changeSpanName_delegates_to_doChangeSpanName() {
        // given
        String newName = UUID.randomUUID().toString();

        // when
        defaultImplSpy.changeSpanName(spanMock, newName);

        // then
        verify(defaultImplSpy).doChangeSpanName(spanMock, newName);
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(spanMock);
    }

    @Test
    public void changeSpanName_does_not_propagate_exception_from_doChangeSpanName() {
        // given
        String newName = UUID.randomUUID().toString();
        doThrow(new RuntimeException("intentional exception"))
            .when(defaultImplSpy).doChangeSpanName(any(FooSpan.class), anyString());

        // when
        defaultImplSpy.changeSpanName(spanMock, newName);

        // then
        verify(defaultImplSpy).doChangeSpanName(spanMock, newName);
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(spanMock);
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true"
    }, splitBy = "\\|")
    @Test
    public void changeSpanName_does_nothing_if_passed_null_span_or_newName(
        boolean spanIsNull, boolean newNameIsNull
    ) {
        // given
        FooSpan span = (spanIsNull) ? null : spanMock;
        String newName = (newNameIsNull) ? null : UUID.randomUUID().toString();

        // when
        defaultImplSpy.changeSpanName(span, newName);

        // then
        verify(defaultImplSpy, never()).doChangeSpanName(any(FooSpan.class), anyString());
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(spanMock);
    }

    @Test
    public void handleRequestTagging_delegates_to_doHandleRequestTagging() {
        // when
        defaultImplSpy.handleRequestTagging(spanMock, requestMock);

        // then
        verify(defaultImplSpy).doHandleRequestTagging(spanMock, requestMock);
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(spanMock, requestMock);
    }

    @Test
    public void handleRequestTagging_does_not_propagate_exception_from_doHandleRequestTagging() {
        // given
        doThrow(new RuntimeException("intentional exception"))
            .when(defaultImplSpy).doHandleRequestTagging(any(FooSpan.class), any(FooRequest.class));

        // when
        defaultImplSpy.handleRequestTagging(spanMock, requestMock);

        // then
        verify(defaultImplSpy).doHandleRequestTagging(spanMock, requestMock);
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(spanMock, requestMock);
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true"
    }, splitBy = "\\|")
    @Test
    public void handleRequestTagging_does_nothing_if_passed_null_span_or_request(
        boolean spanIsNull, boolean requestIsNull
    ) {
        // given
        FooSpan span = (spanIsNull) ? null : spanMock;
        FooRequest request = (requestIsNull) ? null : requestMock;

        // when
        defaultImplSpy.handleRequestTagging(span, request);

        // then
        verify(defaultImplSpy, never()).doHandleRequestTagging(any(FooSpan.class), any(FooRequest.class));
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(spanMock, requestMock);
    }

    @Test
    public void handleResponseTaggingAndFinalSpanName_delegates_to_doHandleResponseTaggingAndFinalSpanName() {
        // when
        defaultImplSpy.handleResponseTaggingAndFinalSpanName(spanMock, requestMock, responseMock, errorMock);

        // then
        verify(defaultImplSpy).doHandleResponseTaggingAndFinalSpanName(spanMock, requestMock, responseMock, errorMock);
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(spanMock, requestMock, responseMock, errorMock);
    }

    @Test
    public void handleResponseTaggingAndFinalSpanName_delegates_to_doHandleResponseTaggingAndFinalSpanName_even_if_args_other_than_span_are_null() {
        // when
        defaultImplSpy.handleResponseTaggingAndFinalSpanName(spanMock, null, null, null);

        // then
        verify(defaultImplSpy).doHandleResponseTaggingAndFinalSpanName(spanMock, null, null, null);
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(spanMock, requestMock, responseMock, errorMock);
    }

    @Test
    public void handleResponseTaggingAndFinalSpanName_does_not_propagate_exception_from_doHandleResponseTaggingAndFinalSpanName() {
        // given
        doThrow(new RuntimeException("intentional exception"))
            .when(defaultImplSpy)
            .doHandleResponseTaggingAndFinalSpanName(
                any(FooSpan.class), any(FooRequest.class), any(FooResponse.class), any(Throwable.class)
            );

        // when
        defaultImplSpy.handleResponseTaggingAndFinalSpanName(spanMock, requestMock, responseMock, errorMock);

        // then
        verify(defaultImplSpy).doHandleResponseTaggingAndFinalSpanName(spanMock, requestMock, responseMock, errorMock);
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(spanMock, requestMock, responseMock, errorMock);
    }

    @Test
    public void handleResponseTaggingAndFinalSpanName_does_nothing_if_passed_null_span() {
        // when
        defaultImplSpy.handleResponseTaggingAndFinalSpanName(null, requestMock, responseMock, errorMock);

        // then
        verify(defaultImplSpy, never()).doHandleResponseTaggingAndFinalSpanName(
            any(FooSpan.class), any(FooRequest.class), any(FooResponse.class), any(Throwable.class)
        );
        verifyNoMoreInteractions(defaultImplSpy);
        verifyZeroInteractions(spanMock, requestMock, responseMock, errorMock);
    }

    private static class DefaultSpanNamingAndTaggingStrategy
        extends SpanNamingAndTaggingStrategy<FooRequest, FooResponse, FooSpan> {

        @Override
        protected @Nullable String doGetInitialSpanName(@NotNull FooRequest request) { return null; }

        @Override
        protected void doChangeSpanName(@NotNull FooSpan span, @NotNull String newName) { }

        @Override
        protected void doHandleRequestTagging(@NotNull FooSpan span, @NotNull FooRequest request) { }

        @Override
        protected void doHandleResponseTaggingAndFinalSpanName(
            @NotNull FooSpan span,
            @Nullable FooRequest request,
            @Nullable FooResponse response,
            @Nullable Throwable error
        ) { }
    }

    private static class FooRequest {}

    private static class FooResponse {}

    private static class FooSpan {}

}