package com.nike.riposte.server.channelpipeline;

import com.nike.riposte.server.http.HttpProcessingState;

import org.assertj.core.api.Assertions;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link com.nike.riposte.server.channelpipeline.ChannelAttributes}
 */
public class ChannelAttributesTest {

    @Test
    public void getHttpProcessingStateForChannel_works_as_expected() {
        // given
        ChannelHandlerContext ctxMock = mock(ChannelHandlerContext.class);
        Channel channelMock = mock(Channel.class);
        @SuppressWarnings("unchecked")
        Attribute<HttpProcessingState> magicAttrMock = mock(Attribute.class);

        // and
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(magicAttrMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);

        // expect
        assertThat(ChannelAttributes.getHttpProcessingStateForChannel(ctxMock), is(magicAttrMock));
    }

    @Test
    public void exercise_private_constructor_for_code_coverage() throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        // given
        Constructor<ChannelAttributes> constructor = ChannelAttributes.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        // when
        ChannelAttributes channelAttributes = constructor.newInstance();

        // expect
        assertThat(channelAttributes, notNullValue());
    }

    @Test
    public void processingStateClassAndKeyPair_setValue_throws_UnsupportedOperationException() {
        // given
        ChannelAttributes.ProcessingStateClassAndKeyPair instance =
            new ChannelAttributes.ProcessingStateClassAndKeyPair(null, null);

        // when
        Throwable ex = catchThrowable(() -> instance.setValue(AttributeKey.newInstance("someattr")));

        // then
        Assertions.assertThat(ex).isInstanceOf(UnsupportedOperationException.class);
    }

}