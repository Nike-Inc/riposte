package com.nike.riposte.server.config.distributedtracing;

import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.trace.netty.RiposteWingtipsServerTagAdapter;
import com.nike.wingtips.Span;
import com.nike.wingtips.SpanMutator;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;
import com.nike.wingtips.tags.HttpTagAndSpanNamingStrategy;
import com.nike.wingtips.tags.ZipkinHttpTagStrategy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A concrete implementation of {@link ServerSpanNamingAndTaggingStrategy} that works with Wingtips {@link Span}s,
 * and delegates the work to the Wingtips {@link HttpTagAndSpanNamingStrategy} and {@link HttpTagAndSpanNamingAdapter}
 * classes.
 *
 * <p>By default (default constructor, or {@link #getDefaultInstance()}) you'll get {@link
 * ZipkinHttpTagStrategy#getDefaultInstance()} for the Wingtips strategy and {@link
 * RiposteWingtipsServerTagAdapter#getDefaultInstance()} for the adapter.
 *
 * <p>You can use the alternate constructor if you want different implementations, e.g. you could pass a custom {@link
 * RiposteWingtipsServerTagAdapter} that overrides {@link RiposteWingtipsServerTagAdapter#getInitialSpanName(Object)}
 * and/or {@link RiposteWingtipsServerTagAdapter#getFinalSpanName(Object, Object)} if you want to adjust the span
 * names that are generated.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class DefaultRiposteServerSpanNamingAndTaggingStrategy implements ServerSpanNamingAndTaggingStrategy<Span> {

    protected final @NotNull HttpTagAndSpanNamingStrategy<RequestInfo<?>, ResponseInfo<?>> tagAndNamingStrategy;
    protected final @NotNull HttpTagAndSpanNamingAdapter<RequestInfo<?>, ResponseInfo<?>> tagAndNamingAdapter;

    @SuppressWarnings("WeakerAccess")
    protected static final DefaultRiposteServerSpanNamingAndTaggingStrategy DEFAULT_INSTANCE =
        new DefaultRiposteServerSpanNamingAndTaggingStrategy();

    /**
     * @return A reusable, thread-safe, singleton instance of this class that can be used by anybody who wants to use
     * this class and does not need any customization.
     */
    @SuppressWarnings("unchecked")
    public static DefaultRiposteServerSpanNamingAndTaggingStrategy getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Creates a new instance that uses {@link ZipkinHttpTagStrategy#getDefaultInstance()} and {@link
     * RiposteWingtipsServerTagAdapter#getDefaultInstance()} to do the work of span naming and tagging.
     */
    public DefaultRiposteServerSpanNamingAndTaggingStrategy() {
        this(ZipkinHttpTagStrategy.getDefaultInstance(), RiposteWingtipsServerTagAdapter.getDefaultInstance());
    }

    /**
     * Creates a new instance that uses the given arguments to do the work of span naming and tagging.
     *
     * @param tagAndNamingStrategy The {@link HttpTagAndSpanNamingStrategy} to use.
     * @param tagAndNamingAdapter The {@link HttpTagAndSpanNamingAdapter} to use.
     */
    @SuppressWarnings("ConstantConditions")
    public DefaultRiposteServerSpanNamingAndTaggingStrategy(
        @NotNull HttpTagAndSpanNamingStrategy<RequestInfo<?>, ResponseInfo<?>> tagAndNamingStrategy,
        @NotNull HttpTagAndSpanNamingAdapter<RequestInfo<?>, ResponseInfo<?>> tagAndNamingAdapter
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
    public @Nullable String getInitialSpanName(
        @NotNull RequestInfo<?> request
    ) {
        return tagAndNamingStrategy.getInitialSpanName(request, tagAndNamingAdapter);
    }

    @Override
    public void changeSpanName(@NotNull Span span, @NotNull String newName) {
        SpanMutator.changeSpanName(span, newName);
    }

    @Override
    public void handleRequestTagging(
        @NotNull Span span, @NotNull RequestInfo<?> request
    ) {
        tagAndNamingStrategy.handleRequestTagging(span, request, tagAndNamingAdapter);
    }

    @Override
    public void handleResponseTaggingAndFinalSpanName(
        @NotNull Span span,
        @Nullable RequestInfo<?> request,
        @Nullable ResponseInfo<?> response,
        @Nullable Throwable error
    ) {
        tagAndNamingStrategy.handleResponseTaggingAndFinalSpanName(
            span, request, response, error, tagAndNamingAdapter
        );
    }
}
