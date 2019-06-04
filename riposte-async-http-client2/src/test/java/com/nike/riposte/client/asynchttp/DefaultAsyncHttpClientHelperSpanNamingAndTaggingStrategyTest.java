package com.nike.riposte.client.asynchttp;

import com.nike.riposte.client.asynchttp.testutils.ArgCapturingHttpTagAndSpanNamingStrategy;
import com.nike.riposte.client.asynchttp.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.InitialSpanNameArgs;
import com.nike.riposte.client.asynchttp.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.RequestTaggingArgs;
import com.nike.riposte.client.asynchttp.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.ResponseTaggingArgs;
import com.nike.wingtips.Span;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;
import com.nike.wingtips.tags.HttpTagAndSpanNamingStrategy;
import com.nike.wingtips.tags.ZipkinHttpTagStrategy;

import org.asynchttpclient.Response;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategyTest {

    private DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy impl;

    private Span spanMock;
    private RequestBuilderWrapper requestMock;
    private Response responseMock;
    private Throwable errorMock;

    private HttpTagAndSpanNamingStrategy<RequestBuilderWrapper, Response> wingtipsStrategy;
    private HttpTagAndSpanNamingAdapter<RequestBuilderWrapper, Response> wingtipsAdapterMock;
    private AtomicReference<String> initialSpanNameFromStrategy;
    private AtomicBoolean strategyInitialSpanNameMethodCalled;
    private AtomicBoolean strategyRequestTaggingMethodCalled;
    private AtomicBoolean strategyResponseTaggingAndFinalSpanNameMethodCalled;
    private AtomicReference<InitialSpanNameArgs<RequestBuilderWrapper>> strategyInitialSpanNameArgs;
    private AtomicReference<RequestTaggingArgs<RequestBuilderWrapper>> strategyRequestTaggingArgs;
    private AtomicReference<ResponseTaggingArgs<RequestBuilderWrapper, Response>> strategyResponseTaggingArgs;

    @Before
    public void beforeMethod() {
        initialSpanNameFromStrategy = new AtomicReference<>("span-name-from-strategy-" + UUID.randomUUID().toString());
        strategyInitialSpanNameMethodCalled = new AtomicBoolean(false);
        strategyRequestTaggingMethodCalled = new AtomicBoolean(false);
        strategyResponseTaggingAndFinalSpanNameMethodCalled = new AtomicBoolean(false);
        strategyInitialSpanNameArgs = new AtomicReference<>(null);
        strategyRequestTaggingArgs = new AtomicReference<>(null);
        strategyResponseTaggingArgs = new AtomicReference<>(null);
        wingtipsStrategy = new ArgCapturingHttpTagAndSpanNamingStrategy<>(
                initialSpanNameFromStrategy, strategyInitialSpanNameMethodCalled, strategyRequestTaggingMethodCalled,
                strategyResponseTaggingAndFinalSpanNameMethodCalled, strategyInitialSpanNameArgs,
                strategyRequestTaggingArgs, strategyResponseTaggingArgs
        );
        wingtipsAdapterMock = mock(HttpTagAndSpanNamingAdapter.class);

        impl = new DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy(wingtipsStrategy, wingtipsAdapterMock);

        requestMock = mock(RequestBuilderWrapper.class);
        responseMock = mock(Response.class);
        errorMock = mock(Throwable.class);
        spanMock = mock(Span.class);
    }

    @Test
    public void getDefaultInstance_returns_DEFAULT_INSTANCE() {
        // when
        DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy instance =
                DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy.getDefaultInstance();

        // then
        assertThat(instance)
                .isSameAs(DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy.DEFAULT_INSTANCE);
        assertThat(instance.tagAndNamingStrategy).isSameAs(ZipkinHttpTagStrategy.getDefaultInstance());
        assertThat(instance.tagAndNamingAdapter).isSameAs(AsyncHttpClientHelperTagAdapter.getDefaultInstance());
    }

    @Test
    public void default_constructor_creates_instance_using_default_ZipkinHttpTagStrategy_and_AsyncHttpClientHelperTagAdapter() {
        // when
        DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy instance =
                new DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy();

        // then
        assertThat(instance.tagAndNamingStrategy).isSameAs(ZipkinHttpTagStrategy.getDefaultInstance());
        assertThat(instance.tagAndNamingAdapter).isSameAs(AsyncHttpClientHelperTagAdapter.getDefaultInstance());
    }

    @Test
    public void alternate_constructor_creates_instance_using_specified_wingtips_strategy_and_adapter() {
        // given
        HttpTagAndSpanNamingStrategy<RequestBuilderWrapper, Response> wingtipsStrategyMock =
                mock(HttpTagAndSpanNamingStrategy.class);
        HttpTagAndSpanNamingAdapter<RequestBuilderWrapper, Response> wingtipsAdapterMock =
                mock(HttpTagAndSpanNamingAdapter.class);

        // when
        DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy instance =
                new DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy(wingtipsStrategyMock, wingtipsAdapterMock);

        // then
        assertThat(instance.tagAndNamingStrategy).isSameAs(wingtipsStrategyMock);
        assertThat(instance.tagAndNamingAdapter).isSameAs(wingtipsAdapterMock);
    }

    private enum NullArgsScenario {
        NULL_WINGTIPS_STRATEGY(
                null,
                mock(HttpTagAndSpanNamingAdapter.class),
                "tagAndNamingStrategy cannot be null - if you really want no strategy, use NoOpHttpTagStrategy"
        ),
        NULL_WINGTIPS_ADAPTER(
                mock(HttpTagAndSpanNamingStrategy.class),
                null,
                "tagAndNamingAdapter cannot be null - if you really want no adapter, use NoOpHttpTagAdapter"
        );

        public final HttpTagAndSpanNamingStrategy<RequestBuilderWrapper, Response> wingtipsStrategy;
        public final HttpTagAndSpanNamingAdapter<RequestBuilderWrapper, Response> wingtipsAdapter;
        public final String expectedExceptionMessage;

        NullArgsScenario(
                HttpTagAndSpanNamingStrategy<RequestBuilderWrapper, Response> wingtipsStrategy,
                HttpTagAndSpanNamingAdapter<RequestBuilderWrapper, Response> wingtipsAdapter,
                String expectedExceptionMessage
        ) {
            this.wingtipsStrategy = wingtipsStrategy;
            this.wingtipsAdapter = wingtipsAdapter;
            this.expectedExceptionMessage = expectedExceptionMessage;
        }
    }

    @DataProvider(value = {
            "NULL_WINGTIPS_STRATEGY",
            "NULL_WINGTIPS_ADAPTER"
    })
    @Test
    public void alternate_constructor_throws_IllegalArgumentException_if_passed_null_args(
            NullArgsScenario scenario
    ) {
        // when
        Throwable ex = catchThrowable(
                () -> new DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy(
                        scenario.wingtipsStrategy, scenario.wingtipsAdapter
                )
        );

        // then
        assertThat(ex)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(scenario.expectedExceptionMessage);
    }

    @Test
    public void doGetInitialSpanName_delegates_to_wingtips_strategy() {
        // when
        String result = impl.doGetInitialSpanName(requestMock);

        // then
        assertThat(result).isEqualTo(initialSpanNameFromStrategy.get());
        strategyInitialSpanNameArgs.get().verifyArgs(requestMock, wingtipsAdapterMock);
    }

    @DataProvider(value = {
            "null           |   false",
            "               |   false",
            "[whitespace]   |   false",
            "fooNewName     |   true"
    }, splitBy = "\\|")
    @Test
    public void doChangeSpanName_changes_span_name_as_expected(String newName, boolean expectNameToBeChanged) {
        // given
        if ("[whitespace]".equals(newName)) {
            newName = "  \r\n\t  ";
        }

        String initialSpanName = UUID.randomUUID().toString();
        Span span = Span.newBuilder(initialSpanName, Span.SpanPurpose.CLIENT).build();

        String expectedSpanName = (expectNameToBeChanged) ? newName : initialSpanName;

        // when
        impl.doChangeSpanName(span, newName);

        // then
        assertThat(span.getSpanName()).isEqualTo(expectedSpanName);
    }

    @Test
    public void doHandleRequestTagging_delegates_to_wingtips_strategy() {
        // when
        impl.doHandleRequestTagging(spanMock, requestMock);

        // then
        strategyRequestTaggingArgs.get().verifyArgs(spanMock, requestMock, wingtipsAdapterMock);
    }

    @Test
    public void doHandleResponseTaggingAndFinalSpanName_delegates_to_wingtips_strategy() {
        // when
        impl.doHandleResponseTaggingAndFinalSpanName(spanMock, requestMock, responseMock, errorMock);

        // then
        strategyResponseTaggingArgs.get().verifyArgs(
                spanMock, requestMock, responseMock, errorMock, wingtipsAdapterMock
        );
    }
}