package com.nike.riposte.server.config.distributedtracing;

import org.jetbrains.annotations.NotNull;

/**
 * An abstraction for distributed tracing behavior in Riposte to allow you some control over how Riposte implements
 * distributed tracing.
 *
 * <p>See {@link DistributedTracingConfigImpl} for a basic implementation that gives you the flexibility to provide
 * whatever objects you want for the various methods in this interface to ensure you're only creating them once.
 * See {@code com.nike.riposte.server.config.distributedtracing.DefaultRiposteDistributedTracingConfigImpl} in
 * the {@code riposte-core} library for a concrete extension of {@link DistributedTracingConfigImpl} that is setup
 * for Riposte's current Wingtips-only environment (see the warning below for an explanation).
 *
 * <p>WARNING: This interface (and related distributed tracing configs) are a work-in-progress. They will likely
 * change in the future to give you more and more control over tracing behavior. It may eventually be flexible enough
 * to allow you to use any tracer implementation, however for now Riposte is tightly coupled to
 * <a href="https://github.com/Nike-Inc/wingtips">Wingtips</a>, and therefore {@link #getSpanClassType()} must be
 * the class type for {@code com.nike.wingtips.Span}, and the associated helper configs like {@link
 * #getServerSpanNamingAndTaggingStrategy()} must deal in Wingtips spans.
 *
 * @param <S> The type of Span this {@link DistributedTracingConfig} and all its sub-configs and helpers/strategies/etc
 * will handle. Riposte is currently tied to Wingtips, so this type must be {@code com.nike.wingtips.Span} for now
 * (if you try to do something else, Riposte will throw an exception on startup). It is genericized into a type param
 * to avoid tying the {@code riposte-spi} library with the Wingtips dependency, and in anticipation of a future
 * version of Riposte that can support any underlying distributed tracing system.
 *
 * @author Nic Munroe
 */
public interface DistributedTracingConfig<S> {

    /**
     * @return The {@link ServerSpanNamingAndTaggingStrategy} that will control span names and auto-tagging/annotations
     * for the overall request spans that cover incoming requests into Riposte.
     */
    @NotNull ServerSpanNamingAndTaggingStrategy<S> getServerSpanNamingAndTaggingStrategy();

    /**
     * @return The type of Span this {@link DistributedTracingConfig} and all its sub-configs and helpers/strategies/etc
     * will handle. Riposte is currently tied to Wingtips, so this type must be {@code com.nike.wingtips.Span} for now
     * (if you try to do something else, Riposte will throw an exception on startup).
     */
    @NotNull Class<S> getSpanClassType();

}
