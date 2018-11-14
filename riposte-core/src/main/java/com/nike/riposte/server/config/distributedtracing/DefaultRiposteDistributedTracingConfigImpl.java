package com.nike.riposte.server.config.distributedtracing;

import com.nike.wingtips.Span;

import org.jetbrains.annotations.NotNull;

/**
 * An extension of {@link DistributedTracingConfigImpl} that expects Wingtips {@link Span} for the span type.
 * By default (default constructor, or {@link #getDefaultInstance()}), you'll get {@link
 * DefaultRiposteServerSpanNamingAndTaggingStrategy#getDefaultInstance()} for the server span naming and tagging
 * strategy. You can also use the alternate constructor to specify a custom {@link ServerSpanNamingAndTaggingStrategy}
 * if needed.
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
     * for the {@link #getServerSpanNamingAndTaggingStrategy()}.
     */
    public DefaultRiposteDistributedTracingConfigImpl() {
        this(DefaultRiposteServerSpanNamingAndTaggingStrategy.getDefaultInstance());
    }

    /**
     * Creates a new instance that uses the given {@link ServerSpanNamingAndTaggingStrategy} for the
     * {@link #getServerSpanNamingAndTaggingStrategy()}.
     *
     * @param serverSpanNamingAndTaggingStrategy The {@link ServerSpanNamingAndTaggingStrategy} to use when
     * {@link #getServerSpanNamingAndTaggingStrategy()} is called.
     */
    public DefaultRiposteDistributedTracingConfigImpl(
        @NotNull ServerSpanNamingAndTaggingStrategy<Span> serverSpanNamingAndTaggingStrategy
    ) {
        super(serverSpanNamingAndTaggingStrategy, Span.class);
    }
}
