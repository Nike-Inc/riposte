package com.nike.riposte.server.config.distributedtracing;

import com.nike.wingtips.Span;

import org.jetbrains.annotations.NotNull;

/**
 * An extension of {@link DistributedTracingConfigImpl} that expects Wingtips {@link Span} for the span type.
 * By default (default constructor, or {@link #getDefaultInstance()}), you'll get {@link
 * DefaultRiposteServerSpanNamingAndTaggingStrategy#getDefaultInstance()} for the server span naming and tagging
 * strategy, and {@link DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy#getDefaultInstance()} for the
 * proxy/router span naming and tagging strategy. You can also use the alternate constructor to specify a custom
 * {@link ServerSpanNamingAndTaggingStrategy} and {@link ProxyRouterSpanNamingAndTaggingStrategy} if needed.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class DefaultRiposteDistributedTracingConfigImpl extends DistributedTracingConfigImpl<Span> {

    protected static final DefaultRiposteDistributedTracingConfigImpl DEFAULT_INSTANCE =
        new DefaultRiposteDistributedTracingConfigImpl();

    /**
     * @return A reusable, thread-safe, singleton instance of this class that can be used by anybody who wants to use
     * this class and does not need any customization.
     */
    @SuppressWarnings("unchecked")
    public static DefaultRiposteDistributedTracingConfigImpl getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Creates a new instance that uses {@link DefaultRiposteServerSpanNamingAndTaggingStrategy#getDefaultInstance()}
     * for the {@link #getServerSpanNamingAndTaggingStrategy()}, and {@link
     * DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy#getDefaultInstance()} for the {@link
     * #getProxyRouterSpanNamingAndTaggingStrategy()}.
     */
    public DefaultRiposteDistributedTracingConfigImpl() {
        this(
            DefaultRiposteServerSpanNamingAndTaggingStrategy.getDefaultInstance(),
            DefaultRiposteProxyRouterSpanNamingAndTaggingStrategy.getDefaultInstance()
        );
    }

    /**
     * Creates a new instance that uses the given {@link ServerSpanNamingAndTaggingStrategy} for the
     * {@link #getServerSpanNamingAndTaggingStrategy()}, and the given {@link ProxyRouterSpanNamingAndTaggingStrategy}
     * for the {@link #getProxyRouterSpanNamingAndTaggingStrategy()}.
     *
     * @param serverSpanNamingAndTaggingStrategy The {@link ServerSpanNamingAndTaggingStrategy} to use when
     * {@link #getServerSpanNamingAndTaggingStrategy()} is called.
     * @param proxyRouterSpanNamingAndTaggingStrategy The object that should be returned when
     * {@link #getProxyRouterSpanNamingAndTaggingStrategy()} is called.
     */
    public DefaultRiposteDistributedTracingConfigImpl(
        @NotNull ServerSpanNamingAndTaggingStrategy<Span> serverSpanNamingAndTaggingStrategy,
        @NotNull ProxyRouterSpanNamingAndTaggingStrategy<Span> proxyRouterSpanNamingAndTaggingStrategy
    ) {
        super(serverSpanNamingAndTaggingStrategy, proxyRouterSpanNamingAndTaggingStrategy, Span.class);
    }
}
