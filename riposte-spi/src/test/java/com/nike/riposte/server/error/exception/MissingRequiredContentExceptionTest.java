package com.nike.riposte.server.error.exception;

import com.nike.backstopper.handler.riposte.listener.impl.BackstopperRiposteFrameworkErrorHandlerListenerTest;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.util.Matcher;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import io.netty.handler.codec.http.HttpMethod;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@RunWith(DataProviderRunner.class)
public class MissingRequiredContentExceptionTest {

    @Test
    public void no_arg_constructor_works_as_expected() {
        MissingRequiredContentException ex = new MissingRequiredContentException();

        assertThat(ex.endpointClassName).isEqualTo("null");
        assertThat(ex.method).isEqualTo("null");
        assertThat(ex.path).isEqualTo("null");
    }

    @Test
    public void three_arg_constructor_works_as_expected() {
        // given
        String path = "/path";
        String method = "POST";
        String endpointClassName = "endpoint";

        // when
        MissingRequiredContentException ex = new MissingRequiredContentException(path, method, endpointClassName);

        // then
        assertThat(ex.endpointClassName).isEqualTo(endpointClassName);
        assertThat(ex.method).isEqualTo(method);
        assertThat(ex.path).isEqualTo(path);
    }

    @Test
    public void two_arg_constructor_works_as_expected_null_inputs() {
        // when
        MissingRequiredContentException ex = new MissingRequiredContentException(null, null);

        // then
        assertThat(ex.endpointClassName).isEqualTo("null");
        assertThat(ex.method).isEqualTo("null");
        assertThat(ex.path).isEqualTo("null");
    }

    @DataProvider(value = {
            "false, false",
            "true, true"
    })
    @Test
    public void two_arg_constructor_works_as_expected(boolean nullMethod, boolean nullEndpoint) {
        // given
        RequestInfo<?> requestInfo = mock(RequestInfo.class);
        doReturn("/path").when(requestInfo).getPath();
        Endpoint<String> endpoint = new MissingRequiredContentExceptionTest.TestEndpoint();
        if (nullEndpoint) {
            endpoint = null;
        }
        if (!nullMethod) {
            doReturn(HttpMethod.POST).when(requestInfo).getMethod();
        }

        // when
        MissingRequiredContentException ex = new MissingRequiredContentException(requestInfo, endpoint);

        // then
        assertThat(ex.path).isEqualTo("/path");
        if (nullMethod) {
            assertThat(ex.method).isEqualTo("null");
        } else {
            assertThat(ex.method).isEqualTo("POST");
        }
        if (nullEndpoint) {
            assertThat(ex.endpointClassName).isEqualTo("null");
        } else {
            assertThat(ex.endpointClassName).isEqualTo("TestEndpoint");
        }
    }

    class TestEndpoint implements Endpoint<String> {

        @Override
        public @NotNull Matcher requestMatcher() {
            return null;
        }
    }
}
