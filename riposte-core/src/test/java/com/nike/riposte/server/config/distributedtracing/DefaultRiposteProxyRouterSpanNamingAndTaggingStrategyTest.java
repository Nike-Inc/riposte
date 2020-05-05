package com.nike.riposte.server.config.distributedtracing;

import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.InitialSpanNameArgs;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.RequestTaggingArgs;
import com.nike.riposte.server.testutils.ArgCapturingHttpTagAndSpanNamingStrategy.ResponseTaggingArgs;
import com.nike.trace.netty.RiposteWingtipsNettyClientTagAdapter;
import com.nike.wingtips.Span;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;
import com.nike.wingtips.tags.HttpTagAndSpanNamingStrategy;
import com.nike.wingtips.tags.ZipkinHttpTagStrategy;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class DefaultRiposteProxyRouterSpanNamingAndTaggingStrategyTest {

    private DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy impl;

    private Span spanMock;
    private HttpRequest requestMock;
    private HttpResponse responseMock;
    private Throwable errorMock;

    private HttpTagAndSpanNamingStrategy<HttpRequest, HttpResponse> wingtipsStrategy;
    private HttpTagAndSpanNamingAdapter<HttpRequest, HttpResponse> wingtipsAdapterMock;
    private AtomicReference<String> initialSpanNameFromStrategy;
    private AtomicBoolean strategyInitialSpanNameMethodCalled;
    private AtomicBoolean strategyRequestTaggingMethodCalled;
    private AtomicBoolean strategyResponseTaggingAndFinalSpanNameMethodCalled;
    private AtomicReference<InitialSpanNameArgs<HttpRequest>> strategyInitialSpanNameArgs;
    private AtomicReference<RequestTaggingArgs<HttpRequest>> strategyRequestTaggingArgs;
    private AtomicReference<ResponseTaggingArgs<HttpRequest, HttpResponse>> strategyResponseTaggingArgs;

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

        impl = new DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy(wingtipsStrategy, wingtipsAdapterMock);

        requestMock = mock(HttpRequest.class);
        responseMock = mock(HttpResponse.class);
        errorMock = mock(Throwable.class);
        spanMock = mock(Span.class);

        doReturn(HttpMethod.GET).when(requestMock).method();
    }

    @Test
    public void getDefaultInstance_returns_DEFAULT_INSTANCE() {
        // when
        DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy instance =
            DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy.getDefaultInstance();

        // then
        assertThat(instance)
            .isSameAs(DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy.DEFAULT_INSTANCE);
        assertThat(instance.tagAndNamingStrategy).isSameAs(ZipkinHttpTagStrategy.getDefaultInstance());
        assertThat(instance.tagAndNamingAdapter).isSameAs(RiposteWingtipsNettyClientTagAdapter.getDefaultInstanceForProxy());
    }

    @Test
    public void default_constructor_creates_instance_using_default_ZipkinHttpTagStrategy_and_RiposteWingtipsNettyClientTagAdapter() {
        // when
        DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy instance =
            new DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy();

        // then
        assertThat(instance.tagAndNamingStrategy).isSameAs(ZipkinHttpTagStrategy.getDefaultInstance());
        assertThat(instance.tagAndNamingAdapter).isSameAs(RiposteWingtipsNettyClientTagAdapter.getDefaultInstanceForProxy());
    }

    @Test
    public void alternate_constructor_creates_instance_using_specified_wingtips_strategy_and_adapter() {
        // given
        HttpTagAndSpanNamingStrategy<HttpRequest, HttpResponse> wingtipsStrategyMock =
            mock(HttpTagAndSpanNamingStrategy.class);
        HttpTagAndSpanNamingAdapter<HttpRequest, HttpResponse> wingtipsAdapterMock =
            mock(HttpTagAndSpanNamingAdapter.class);

        // when
        DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy instance =
            new DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy(wingtipsStrategyMock, wingtipsAdapterMock);

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

        public final HttpTagAndSpanNamingStrategy<HttpRequest, HttpResponse> wingtipsStrategy;
        public final HttpTagAndSpanNamingAdapter<HttpRequest, HttpResponse> wingtipsAdapter;
        public final String expectedExceptionMessage;

        NullArgsScenario(
            HttpTagAndSpanNamingStrategy<HttpRequest, HttpResponse> wingtipsStrategy,
            HttpTagAndSpanNamingAdapter<HttpRequest, HttpResponse> wingtipsAdapter,
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
        DefaultRiposteProxyRouterSpanNamingAndTaggingStrategyTest.NullArgsScenario scenario
    ) {
        // when
        Throwable ex = catchThrowable(
            () -> new DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy(
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

    private enum FinalSpanNameScenario {
        REVERT_SCENARIO_WITH_PREFIX(
            HttpMethod.GET, "proxy-GET /foo/bar/{baz}", "proxy-GET", false, true
        ),
        REVERT_SCENARIO_NO_PREFIX(
            HttpMethod.GET, "some-orig-span-name-" + UUID.randomUUID().toString(), "GET", false, true
        ),
        REQUEST_IS_NULL(
            HttpMethod.GET, "proxy-GET /foo/bar/{baz}", "proxy-GET", true, false
        ),
        SPAN_NAME_FROM_STRATEGY_MATCHES_ORIG_SPAN_NAME(
            HttpMethod.GET, "foo", "foo", false, false
        ),
        SPAN_NAME_FROM_STRATEGY_IS_NOT_BASIC_SPAN_NAME(
            HttpMethod.GET, "foo", "some-custom-name", false, false
        ),
        ORIG_SPAN_NAME_IS_BLANK(
            HttpMethod.GET, "   ", "proxy-GET", false, false
        );

        public final HttpMethod httpMethod;
        public final String origSpanName;
        public final String finalNameFromStrategy;
        public final boolean requestIsNull;
        public final boolean expectSpanNameReversion;

        FinalSpanNameScenario(
            HttpMethod httpMethod, String origSpanName, String finalNameFromStrategy, boolean requestIsNull,
            boolean expectSpanNameReversion
        ) {
            this.httpMethod = httpMethod;
            this.origSpanName = origSpanName;
            this.finalNameFromStrategy = finalNameFromStrategy;
            this.requestIsNull = requestIsNull;
            this.expectSpanNameReversion = expectSpanNameReversion;
        }
    }

    @DataProvider(value = {
        "REVERT_SCENARIO_WITH_PREFIX",
        "REVERT_SCENARIO_NO_PREFIX",
        "REQUEST_IS_NULL",
        "SPAN_NAME_FROM_STRATEGY_MATCHES_ORIG_SPAN_NAME",
        "SPAN_NAME_FROM_STRATEGY_IS_NOT_BASIC_SPAN_NAME",
        "ORIG_SPAN_NAME_IS_BLANK",
    })
    @Test
    public void doHandleResponseTaggingAndFinalSpanName_delegates_to_wingtips_strategy_but_reverts_span_name_if_necessary(
        FinalSpanNameScenario scenario
    ) {
        // given
        DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy implSpy = spy(impl);
        doAnswer(new Answer() {
            int invocationCount = 0;

            @Override
            public Object answer(InvocationOnMock invocation) {
                if (invocationCount == 0) {
                    invocationCount++;
                    return scenario.origSpanName;
                }

                return scenario.finalNameFromStrategy;
            }
        }).when(spanMock).getSpanName();

        doReturn(scenario.httpMethod).when(requestMock).method();
        HttpRequest requestToUse = (scenario.requestIsNull) ? null : requestMock;

        // when
        implSpy.doHandleResponseTaggingAndFinalSpanName(spanMock, requestToUse, responseMock, errorMock);

        // then
        // The strategy should always be called.
        strategyResponseTaggingArgs.get().verifyArgs(
            spanMock, requestToUse, responseMock, errorMock, wingtipsAdapterMock
        );

        // The span name may or may not have been reverted back to the original span name, though.
        if (scenario.expectSpanNameReversion) {
            verify(implSpy).doChangeSpanName(spanMock, scenario.origSpanName);
        }
        else {
            verify(implSpy, never()).doChangeSpanName(any(Span.class), anyString());
        }
    }
}