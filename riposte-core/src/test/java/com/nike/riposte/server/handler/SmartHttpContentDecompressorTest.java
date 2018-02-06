package com.nike.riposte.server.handler;

import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.testutils.TestUtil;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.util.reflection.Whitebox;

import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.embedded.EmbeddedChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Tests the functionality of {@link SmartHttpContentDecompressor}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class SmartHttpContentDecompressorTest {

    @Test
    public void default_constructor_works_as_expected() {
        // when
        SmartHttpContentDecompressor decompressor = new SmartHttpContentDecompressor();

        // then
        assertThat(Whitebox.getInternalState(decompressor, "strict")).isEqualTo(false);
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void single_arg_constructor_works_as_expected(boolean strict) {
        // when
        SmartHttpContentDecompressor decompressor = new SmartHttpContentDecompressor(strict);

        // then
        assertThat(Whitebox.getInternalState(decompressor, "strict")).isEqualTo(strict);
    }

    private enum NewContentDecoderScenario {
        GZIP("gzip", true, true),
        X_GZIP("x-gzip", true, true),
        DEFLATE("deflate", true, true),
        X_DEFLATE("x-deflate", true, true),
        CONTENT_ENCODING_THAT_DOES_NOT_REPRESENT_COMPRESSED_PAYLOAD("foo", true, false),
        ENDPOINT_DOES_NOT_WANT_DECOMPRESS("gzip", false, false),
        NULL_ENDPOINT("gzip", null, true);

        public final String contentEncoding;
        public final Endpoint<?> endpoint;
        public final boolean expectValidDecompressor;

        NewContentDecoderScenario(String contentEncoding, Boolean endpointWantsDecompression,
                                  boolean expectValidDecompressor) {
            this.contentEncoding = contentEncoding;
            this.expectValidDecompressor = expectValidDecompressor;
            Endpoint<?> endpoint = null;
            if (endpointWantsDecompression != null) {
                endpoint = mock(Endpoint.class);
                doReturn(endpointWantsDecompression).when(endpoint).isDecompressRequestPayloadAllowed(any());
            }
            this.endpoint = endpoint;
        }
    }

    @DataProvider(value = {
        "GZIP",
        "X_GZIP",
        "DEFLATE",
        "X_DEFLATE",
        "CONTENT_ENCODING_THAT_DOES_NOT_REPRESENT_COMPRESSED_PAYLOAD",
        "ENDPOINT_DOES_NOT_WANT_DECOMPRESS",
        "NULL_ENDPOINT"
    })
    @Test
    public void newContentDecoder_works_as_expected(NewContentDecoderScenario scenario) throws Exception {
        // given
        SmartHttpContentDecompressor decompressor = new SmartHttpContentDecompressor();
        TestUtil.ChannelHandlerContextMocks mocks = TestUtil.mockChannelHandlerContext();
        Whitebox.setInternalState(decompressor, "ctx", mocks.mockContext);
        ChannelMetadata channelMetadata = new ChannelMetadata(false);
        ChannelConfig channelConfigMock = mock(ChannelConfig.class);

        doReturn(scenario.endpoint).when(mocks.mockHttpProcessingState).getEndpointForExecution();
        doReturn(channelMetadata).when(mocks.mockChannel).metadata();
        doReturn(channelConfigMock).when(mocks.mockChannel).config();

        // when
        EmbeddedChannel result = decompressor.newContentDecoder(scenario.contentEncoding);

        // then
        if (scenario.expectValidDecompressor) {
            assertThat(result).isNotNull();
        }
        else {
            assertThat(result).isNull();
        }
    }

}