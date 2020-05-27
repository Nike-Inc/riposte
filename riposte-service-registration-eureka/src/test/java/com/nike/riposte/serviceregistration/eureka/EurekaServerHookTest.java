package com.nike.riposte.serviceregistration.eureka;

import org.junit.Before;
import org.junit.Test;
import com.nike.riposte.testutils.Whitebox;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests the functionality of {@link EurekaServerHook}.
 */
public class EurekaServerHookTest {

    private EurekaServerHook hook;
    private EurekaHandler eurekaHandlerMock;

    @Before
    public void beforeMethod() {
        eurekaHandlerMock = mock(EurekaHandler.class);
        hook = new EurekaServerHook(() -> true, () -> null);
        Whitebox.setInternalState(hook, "eurekaHandler", eurekaHandlerMock);
    }

    @Test
    public void constructor_creates_eurekaHandler_with_passed_in_args() {
        // given
        Supplier<Boolean> eurekaIsDisabledPropertySupplier = mock(Supplier.class);
        Supplier<String> datacenterTypePropertySupplier = mock(Supplier.class);

        // when
        EurekaServerHook instance = new EurekaServerHook(eurekaIsDisabledPropertySupplier,
                                                         datacenterTypePropertySupplier);

        // then
        assertThat(instance.eurekaHandler.eurekaIsDisabledPropertySupplier).isSameAs(eurekaIsDisabledPropertySupplier);
        assertThat(instance.eurekaHandler.datacenterTypePropertySupplier).isSameAs(datacenterTypePropertySupplier);
    }

    @Test
    public void executePostServerStartupHook_calls_register_on_eurekaHandler() {
        // when
        hook.executePostServerStartupHook(null, null);

        // then
        verify(eurekaHandlerMock).register();
    }

    @Test
    public void executePostServerStartupHook_throws_EurekaException_if_eurekaHandler_explodes() {
        // given
        RuntimeException handlerEx = new RuntimeException("kaboom");
        doThrow(handlerEx).when(eurekaHandlerMock).register();

        // when
        Throwable ex = catchThrowable(() -> hook.executePostServerStartupHook(null, null));

        // then
        verify(eurekaHandlerMock).register();
        assertThat(ex)
            .isInstanceOf(EurekaException.class)
            .hasCause(handlerEx);
    }

    @Test
    public void executeServerShutdownHook_calls_updateStatus_with_DOWN_on_eurekaHandler() {
        // when
        hook.executeServerShutdownHook(null, null);

        // then
        verify(eurekaHandlerMock).updateStatus(EurekaHandler.ServiceStatus.DOWN);
    }

    @Test
    public void executeServerShutdownHook_throws_EurekaException_if_eurekaHandler_explodes() {
        // given
        RuntimeException handlerEx = new RuntimeException("kaboom");
        doThrow(handlerEx).when(eurekaHandlerMock).updateStatus(EurekaHandler.ServiceStatus.DOWN);

        // when
        Throwable ex = catchThrowable(() -> hook.executeServerShutdownHook(null, null));

        // then
        verify(eurekaHandlerMock).updateStatus(EurekaHandler.ServiceStatus.DOWN);
        assertThat(ex)
            .isInstanceOf(EurekaException.class)
            .hasCause(handlerEx);
    }
}
