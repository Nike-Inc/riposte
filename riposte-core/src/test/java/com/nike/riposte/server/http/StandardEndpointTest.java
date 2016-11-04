package com.nike.riposte.server.http;

import com.nike.riposte.util.Matcher;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.channel.ChannelHandlerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * Tests the default functionality of {@link StandardEndpoint}.
 *
 * @author Nic Munroe
 */
public class StandardEndpointTest {

    @Test
    public void basic_default_methods_return_expected_values() {
        // given
        StandardEndpoint<Void, String> defaultInstance = new StandardEndpoint<Void, String>() {
            @Override
            public Matcher requestMatcher() {
                return null;
            }

            @Override
            public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
                return null;
            }
        };

        // expect
        assertThat(defaultInstance.completableFutureTimeoutOverrideMillis()).isNull();
        assertThat(defaultInstance.getCustomTimeoutExceptionCause(mock(RequestInfo.class), mock(ChannelHandlerContext.class))).isNull();
    }

    @Test
    public void constructor_correctly_infers_inputType_and_TypeReference_for_String_type() throws IOException {
        // given
        List<StandardEndpoint<String, ?>> stringInputInstances = Arrays.asList(
            new StandardEndpoint<String, String>() {
                @Override
                public Matcher requestMatcher() {
                    return null;
                }

                @Override
                public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<String> request, Executor longRunningTaskExecutor,
                                                                       ChannelHandlerContext ctx) {
                    return null;
                }
            },
            new BasicGenericImpl<String, Object>(){},
            new BasicHardcodedStringImpl()
        );


        // expect
        stringInputInstances.forEach(instance -> {
            assertThat(instance.inputType).isEqualTo(String.class);
            assertThat(instance.inferredTypeReference.getType()).isEqualTo(String.class);
            assertThat(instance.requestContentType()).isSameAs(instance.inferredTypeReference);
        });
    }

    @Test
    public void constructor_correctly_infers_inputType_and_TypeReference_for_specific_object_type() throws IOException {
        // given
        List<StandardEndpoint<FooObject, ?>> fooObjectInputInstances = Arrays.asList(
            new StandardEndpoint<FooObject, String>() {
                @Override
                public Matcher requestMatcher() {
                    return null;
                }

                @Override
                public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<FooObject> request, Executor longRunningTaskExecutor,
                                                                       ChannelHandlerContext ctx) {
                    return null;
                }
            },
            new BasicGenericImpl<FooObject, Object>(){},
            new BasicHardcodedFooImpl()
        );


        // expect
        fooObjectInputInstances.forEach(instance -> {
            assertThat(instance.inputType).isEqualTo(FooObject.class);
            assertThat(instance.inferredTypeReference.getType()).isEqualTo(FooObject.class);
            assertThat(instance.requestContentType()).isSameAs(instance.inferredTypeReference);
        });
    }

    @Test
    public void constructor_correctly_sets_TypeReference_to_null_when_inputType_is_Void() throws IOException {
        // given
        List<StandardEndpoint<Void, ?>> voidInputInstances = Arrays.asList(
            new StandardEndpoint<Void, String>() {
                @Override
                public Matcher requestMatcher() {
                    return null;
                }

                @Override
                public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor,
                                                                       ChannelHandlerContext ctx) {
                    return null;
                }
            },
            new BasicGenericImpl<Void, Object>(){},
            new BasicHardcodedVoidImpl()
        );


        // expect
        voidInputInstances.forEach(instance -> {
            assertThat(instance.inputType).isEqualTo(Void.class);
            assertThat(instance.inferredTypeReference).isNull();
            assertThat(instance.requestContentType()).isNull();
        });
    }

    @Test
    public void constructor_correctly_sets_TypeReference_to_null_when_inputType_is_raw_type() throws IOException {
        // given
        List<StandardEndpoint> voidInputInstances = Arrays.asList(
            new StandardEndpoint() {
                @Override
                public Matcher requestMatcher() {
                    return null;
                }

                @Override
                public CompletableFuture<ResponseInfo> execute(RequestInfo request, Executor longRunningTaskExecutor,
                                                                       ChannelHandlerContext ctx) {
                    return null;
                }
            },
            new BasicGenericImpl(){},
            new BasicHardcodedRawTypeImpl()
        );


        // expect
        voidInputInstances.forEach(instance -> {
            assertThat(instance.inputType).isNull();
            assertThat(instance.inferredTypeReference).isNull();
            assertThat(instance.requestContentType()).isNull();
        });
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_inputType_is_nonspecific_generic_type_test1() {
        // when
        Throwable ex = catchThrowable(() -> new BasicGenericImpl<>());

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("A StandardEndpoint was constructed with non-specific type information");
    }

    @Test
    public void constructor_throws_IllegalArgumentException_if_inputType_is_nonspecific_generic_type_test2() {
        // when
        Throwable ex = catchThrowable(() -> new BasicGenericImpl<String, String>());

        // then
        assertThat(ex)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("A StandardEndpoint was constructed with non-specific type information");
    }

    private static class BasicGenericImpl<I, O> extends StandardEndpoint<I, O> {
        @Override
        public CompletableFuture<ResponseInfo<O>> execute(RequestInfo<I> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            return null;
        }

        @Override
        public Matcher requestMatcher() {
            return null;
        }
    }

    private static class BasicHardcodedFooImpl extends StandardEndpoint<FooObject, Void> {
        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<FooObject> request, Executor longRunningTaskExecutor,
                                                             ChannelHandlerContext ctx) {
            return null;
        }

        @Override
        public Matcher requestMatcher() {
            return null;
        }
    }

    private static class BasicHardcodedStringImpl extends StandardEndpoint<String, Void> {
        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<String> request, Executor longRunningTaskExecutor,
                                                             ChannelHandlerContext ctx) {
            return null;
        }

        @Override
        public Matcher requestMatcher() {
            return null;
        }
    }

    private static class BasicHardcodedVoidImpl extends StandardEndpoint<Void, Void> {
        @Override
        public CompletableFuture<ResponseInfo<Void>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor,
                                                             ChannelHandlerContext ctx) {
            return null;
        }

        @Override
        public Matcher requestMatcher() {
            return null;
        }
    }

    private static class BasicHardcodedRawTypeImpl extends StandardEndpoint {
        @Override
        public CompletableFuture<ResponseInfo> execute(RequestInfo request, Executor longRunningTaskExecutor,
                                                             ChannelHandlerContext ctx) {
            return null;
        }

        @Override
        public Matcher requestMatcher() {
            return null;
        }
    }

    private static class FooObject {
        public String foo;
        public String bar;
    }
}