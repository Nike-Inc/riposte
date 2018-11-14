package com.nike.riposte.server.config.distributedtracing;

import org.jetbrains.annotations.NotNull;

/**
 * A basic impl of {@link DistributedTracingConfig} where the methods simply return whatever objects you pass into
 * the constructor. This makes sure those objects are only created once.
 *
 * <p>See {@code com.nike.riposte.server.config.distributedtracing.DefaultRiposteDistributedTracingConfigImpl} in
 * the {@code riposte-core} library for a concrete extension of this class that is setup for Riposte's current
 * Wingtips-only environment (see the class-level javadocs for {@link DistributedTracingConfig} for an explanation).
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class DistributedTracingConfigImpl<S> implements DistributedTracingConfig<S> {

    protected final @NotNull ServerSpanNamingAndTaggingStrategy<S> serverSpanNamingAndTaggingStrategy;
    protected final @NotNull Class<S> spanClassType;

    /**
     * Creates a new instance with the given arguments.
     *
     * @param serverSpanNamingAndTaggingStrategy The object that should be returned when
     * {@link #getServerSpanNamingAndTaggingStrategy()} is called.
     * @param spanClassType The object that should be returned when {@link #getSpanClassType()} is called.
     */
    @SuppressWarnings("ConstantConditions")
    public DistributedTracingConfigImpl(
        @NotNull ServerSpanNamingAndTaggingStrategy<S> serverSpanNamingAndTaggingStrategy,
        @NotNull Class<S> spanClassType
    ) {
        if (serverSpanNamingAndTaggingStrategy == null) {
            throw new IllegalArgumentException("serverSpanNamingAndTaggingStrategy cannot be null");
        }

        if (spanClassType == null) {
            throw new IllegalArgumentException("spanClassType cannot be null");
        }

        if (!"com.nike.wingtips.Span".equals(spanClassType.getName())) {
            throw new IllegalArgumentException("Riposte currently only supports Wingtips Spans");
        }

        this.serverSpanNamingAndTaggingStrategy = serverSpanNamingAndTaggingStrategy;
        this.spanClassType = spanClassType;
    }

    @Override
    public @NotNull ServerSpanNamingAndTaggingStrategy<S> getServerSpanNamingAndTaggingStrategy() {
        return serverSpanNamingAndTaggingStrategy;
    }

    @Override
    public @NotNull Class<S> getSpanClassType() {
        return spanClassType;
    }
}
