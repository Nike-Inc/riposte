package com.nike.riposte.client.asynchttp.ning;

import com.nike.riposte.server.config.distributedtracing.SpanNamingAndTaggingStrategy;
import com.nike.wingtips.Span;
import com.nike.wingtips.SpanMutator;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;
import com.nike.wingtips.tags.HttpTagAndSpanNamingStrategy;
import com.nike.wingtips.tags.ZipkinHttpTagStrategy;

import com.ning.http.client.Response;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A concrete implementation of {@link SpanNamingAndTaggingStrategy} for {@link AsyncHttpClientHelper} that works with
 * {@link RequestBuilderWrapper} requests, Ning {@link Response}s, and Wingtips {@link Span}s. This class
 * delegates the actual work to Wingtips {@link HttpTagAndSpanNamingStrategy} and {@link
 * HttpTagAndSpanNamingAdapter} classes.
 *
 * <p>By default (default constructor, or {@link #getDefaultInstance()}) you'll get {@link
 * ZipkinHttpTagStrategy#getDefaultInstance()} for the Wingtips strategy and {@link
 * AsyncHttpClientHelperTagAdapter#getDefaultInstance()} for the adapter.
 *
 * <p>You can use the alternate constructor if you want different implementations, e.g. you could pass a custom {@link
 * AsyncHttpClientHelperTagAdapter} that overrides {@link AsyncHttpClientHelperTagAdapter#getInitialSpanName(Object)}
 * and/or {@link AsyncHttpClientHelperTagAdapter#getFinalSpanName(Object, Object)} if you want to adjust the span
 * names that are generated.
 *
 * @author Nic Munroe
 */
public class DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy
    extends SpanNamingAndTaggingStrategy<RequestBuilderWrapper, Response, Span> {

    protected final @NotNull HttpTagAndSpanNamingStrategy<RequestBuilderWrapper, Response> tagAndNamingStrategy;
    protected final @NotNull HttpTagAndSpanNamingAdapter<RequestBuilderWrapper, Response> tagAndNamingAdapter;

    protected static final DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy DEFAULT_INSTANCE =
        new DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy();

    /**
     * @return A reusable, thread-safe, singleton instance of this class that can be used by anybody who wants to use
     * this class and does not need any customization.
     */
    public static DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Creates a new instance that uses {@link ZipkinHttpTagStrategy#getDefaultInstance()} and {@link
     * AsyncHttpClientHelperTagAdapter#getDefaultInstance()} to do the work of span naming and tagging.
     */
    public DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy() {
        this(ZipkinHttpTagStrategy.getDefaultInstance(), AsyncHttpClientHelperTagAdapter.getDefaultInstance());
    }

    /**
     * Creates a new instance that uses the given arguments to do the work of span naming and tagging.
     *
     * @param tagAndNamingStrategy The {@link HttpTagAndSpanNamingStrategy} to use.
     * @param tagAndNamingAdapter The {@link HttpTagAndSpanNamingAdapter} to use.
     */
    @SuppressWarnings("ConstantConditions")
    public DefaultAsyncHttpClientHelperSpanNamingAndTaggingStrategy(
        @NotNull HttpTagAndSpanNamingStrategy<RequestBuilderWrapper, Response> tagAndNamingStrategy,
        @NotNull HttpTagAndSpanNamingAdapter<RequestBuilderWrapper, Response> tagAndNamingAdapter
    ) {
        if (tagAndNamingStrategy == null) {
            throw new IllegalArgumentException(
                "tagAndNamingStrategy cannot be null - if you really want no strategy, use NoOpHttpTagStrategy"
            );
        }

        if (tagAndNamingAdapter == null) {
            throw new IllegalArgumentException(
                "tagAndNamingAdapter cannot be null - if you really want no adapter, use NoOpHttpTagAdapter"
            );
        }

        this.tagAndNamingStrategy = tagAndNamingStrategy;
        this.tagAndNamingAdapter = tagAndNamingAdapter;
    }

    @Override
    public @Nullable String doGetInitialSpanName(
        @NotNull RequestBuilderWrapper request
    ) {
        return tagAndNamingStrategy.getInitialSpanName(request, tagAndNamingAdapter);
    }

    @Override
    public void doChangeSpanName(@NotNull Span span, @NotNull String newName) {
        SpanMutator.changeSpanName(span, newName);
    }

    @Override
    public void doHandleRequestTagging(
        @NotNull Span span, @NotNull RequestBuilderWrapper request
    ) {
        tagAndNamingStrategy.handleRequestTagging(span, request, tagAndNamingAdapter);
    }

    @Override
    public void doHandleResponseTaggingAndFinalSpanName(
        @NotNull Span span,
        @Nullable RequestBuilderWrapper request,
        @Nullable Response response,
        @Nullable Throwable error
    ) {
        tagAndNamingStrategy.handleResponseTaggingAndFinalSpanName(
            span, request, response, error, tagAndNamingAdapter
        );
    }
}
