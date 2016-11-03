package com.nike.riposte.server.channelpipeline;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.server.http.ProcessingState;
import com.nike.riposte.server.http.ProxyRouterProcessingState;

import java.util.Arrays;
import java.util.Collection;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * Class for holding attribute keys for use with {@link io.netty.channel.Channel#attr(AttributeKey)}.
 *
 * @author Nic Munroe
 */
public class ChannelAttributes {

    // Intentionally private - all access should come through the static helper methods.
    private ChannelAttributes() { /* do nothing */ }

    /**
     * Attr key for the {@link HttpProcessingState} associated with a channel.
     */
    public static final AttributeKey<HttpProcessingState> HTTP_PROCESSING_STATE_ATTRIBUTE_KEY =
        AttributeKey.valueOf("HTTP_PROCESSING_STATE");
    /**
     * Attr key for the {@link ProxyRouterProcessingState} associated with a channel. Generally only used with
     * proxy/edge/domain routing servers.
     */
    public static final AttributeKey<ProxyRouterProcessingState> PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY =
        AttributeKey.valueOf("PROXY_ROUTER_PROCESSING_STATE");

    /**
     * Collection of all attribute keys that represent {@link ProcessingState} objects.
     */
    public static final Collection<ProcessingStateClassAndKeyPair<? extends ProcessingState>>
        PROCESSING_STATE_ATTRIBUTE_KEYS = Arrays.asList(
            ProcessingStateClassAndKeyPair.of(HttpProcessingState.class, HTTP_PROCESSING_STATE_ATTRIBUTE_KEY),
            ProcessingStateClassAndKeyPair.of(ProxyRouterProcessingState.class,
                                              PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY)
    );

    /**
     * Helper method for retrieving the {@link HttpProcessingState} for the given {@link ChannelHandlerContext}'s {@link
     * io.netty.channel.Channel}. This will never return null, however the {@link Attribute} it returns may not be set,
     * and therefore {@link Attribute#get()} (and related methods) may return null.
     */
    public static Attribute<HttpProcessingState> getHttpProcessingStateForChannel(ChannelHandlerContext ctx) {
        return ctx.channel().attr(HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
    }

    /**
     * Helper method for retrieving the {@link ProxyRouterProcessingState} for the given {@link ChannelHandlerContext}'s
     * {@link io.netty.channel.Channel}. This will never return null, however the {@link Attribute} it returns may not
     * be set, and therefore {@link Attribute#get()} (and related methods) may return null. This is generally only used
     * with proxy/edge/domain routing endpoints and will therefore not be populated for other endpoint types.
     */
    public static Attribute<ProxyRouterProcessingState> getProxyRouterProcessingStateForChannel(
        ChannelHandlerContext ctx) {
        return ctx.channel().attr(PROXY_ROUTER_PROCESSING_STATE_ATTRIBUTE_KEY);
    }

    /**
     * Custom extension of {@link Pair} that guarantees the left and right fields have correctly matching generics.
     */
    public static class ProcessingStateClassAndKeyPair<T extends ProcessingState>
        extends Pair<Class<T>, AttributeKey<T>> {

        public final Class<T> left;
        public final AttributeKey<T> right;

        public static <T extends ProcessingState> ProcessingStateClassAndKeyPair<T> of(final Class<T> left,
                                                                                       final AttributeKey<T> right) {
            return new ProcessingStateClassAndKeyPair<>(left, right);
        }

        /**
         * Create a new pair instance.
         */
        ProcessingStateClassAndKeyPair(final Class<T> left, final AttributeKey<T> right) {
            super();
            this.left = left;
            this.right = right;
        }

        //-----------------------------------------------------------------------

        /**
         * {@inheritDoc}
         */
        @Override
        public Class<T> getLeft() {
            return left;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public AttributeKey<T> getRight() {
            return right;
        }

        /**
         * <p>Throws {@code UnsupportedOperationException}.</p>
         *
         * <p>This pair is immutable, so this operation is not supported.</p>
         *
         * @param value
         *     the value to set
         *
         * @return never
         *
         * @throws UnsupportedOperationException
         *     as this operation is not supported
         */
        @Override
        public AttributeKey<T> setValue(final AttributeKey<T> value) {
            throw new UnsupportedOperationException();
        }
    }

}
