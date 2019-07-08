package com.nike.riposte.server.config.distributedtracing;

import com.nike.trace.netty.RiposteWingtipsNettyClientTagAdapter;
import com.nike.wingtips.Span;
import com.nike.wingtips.SpanMutator;
import com.nike.wingtips.tags.HttpTagAndSpanNamingAdapter;
import com.nike.wingtips.tags.HttpTagAndSpanNamingStrategy;
import com.nike.wingtips.tags.ZipkinHttpTagStrategy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import static com.nike.internal.util.StringUtils.isNotBlank;

/**
 * A concrete implementation of {@link ProxyRouterSpanNamingAndTaggingStrategy} that works with Wingtips {@link Span}s,
 * and delegates the work to the Wingtips {@link HttpTagAndSpanNamingStrategy} and {@link HttpTagAndSpanNamingAdapter}
 * classes.
 *
 * <p>By default (default constructor, or {@link #getDefaultInstance()}) you'll get {@link
 * ZipkinHttpTagStrategy#getDefaultInstance()} for the Wingtips strategy and {@link
 * RiposteWingtipsNettyClientTagAdapter#getDefaultInstanceForProxy()} for the adapter.
 *
 * <p>You can use the alternate constructor if you want different implementations, e.g. you could pass a custom {@link
 * RiposteWingtipsNettyClientTagAdapter} that overrides {@link
 * RiposteWingtipsNettyClientTagAdapter#getInitialSpanName(HttpRequest)} and/or {@link
 * RiposteWingtipsNettyClientTagAdapter#getFinalSpanName(HttpRequest, HttpResponse)} if you want to adjust the span
 * names that are generated.
 *
 * @author Nic Munroe
 */
public class DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy
    extends ProxyRouterSpanNamingAndTaggingStrategy<Span> {

    protected final @NotNull HttpTagAndSpanNamingStrategy<HttpRequest, HttpResponse> tagAndNamingStrategy;
    protected final @NotNull HttpTagAndSpanNamingAdapter<HttpRequest, HttpResponse> tagAndNamingAdapter;
    
    protected static final DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy DEFAULT_INSTANCE =
        new DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy();

    /**
     * @return A reusable, thread-safe, singleton instance of this class that can be used by anybody who wants to use
     * this class and does not need any customization.
     */
    public static DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Creates a new instance that uses {@link ZipkinHttpTagStrategy#getDefaultInstance()} and {@link
     * RiposteWingtipsNettyClientTagAdapter#getDefaultInstanceForProxy()} to do the work of span naming and tagging.
     */
    public DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy() {
        this(
            ZipkinHttpTagStrategy.getDefaultInstance(),
            RiposteWingtipsNettyClientTagAdapter.getDefaultInstanceForProxy()
        );
    }

    /**
     * Creates a new instance that uses the given arguments to do the work of span naming and tagging.
     *
     * @param tagAndNamingStrategy The {@link HttpTagAndSpanNamingStrategy} to use.
     * @param tagAndNamingAdapter The {@link HttpTagAndSpanNamingAdapter} to use.
     */
    @SuppressWarnings("ConstantConditions")
    public DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy(
        @NotNull HttpTagAndSpanNamingStrategy<HttpRequest, HttpResponse> tagAndNamingStrategy,
        @NotNull HttpTagAndSpanNamingAdapter<HttpRequest, HttpResponse> tagAndNamingAdapter
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
    protected @Nullable String doGetInitialSpanName(@NotNull HttpRequest request) {
        return tagAndNamingStrategy.getInitialSpanName(request, tagAndNamingAdapter);
    }

    @Override
    protected void doChangeSpanName(@NotNull Span span, @NotNull String newName) {
        SpanMutator.changeSpanName(span, newName);
    }

    @Override
    protected void doHandleRequestTagging(@NotNull Span span, @NotNull HttpRequest request) {
        tagAndNamingStrategy.handleRequestTagging(span, request, tagAndNamingAdapter);
    }

    @Override
    protected void doHandleResponseTaggingAndFinalSpanName(
        @NotNull Span span, @Nullable HttpRequest request, @Nullable HttpResponse response, @Nullable Throwable error
    ) {
        // Capture the original span name in case we want to revert back to it.
        String origSpanName = span.getSpanName();

        // Do the final naming and tagging stuff.
        tagAndNamingStrategy.handleResponseTaggingAndFinalSpanName(
            span, request, response, error, tagAndNamingAdapter
        );

        String finalSpanName = span.getSpanName();

        // See if we should revert back to the original span name.
        String httpMethodStr = (request == null) ? null : request.method().name();
        if (httpMethodStr != null
            && (!finalSpanName.equals(origSpanName))
            && (finalSpanName.equals("proxy-" + httpMethodStr) || finalSpanName.equals(httpMethodStr))
            && isNotBlank(origSpanName)
        ) {
            // The new span name is a basic default one, with just the HTTP method, but the original span name was
            //      different - probably due to an override from
            //      ProxyRouterSpanNamingAndTaggingStrategy.getInitialSpanNameOverride(). We should revert back to
            //      the original span name.
            changeSpanName(span, origSpanName);
        }
    }
}
