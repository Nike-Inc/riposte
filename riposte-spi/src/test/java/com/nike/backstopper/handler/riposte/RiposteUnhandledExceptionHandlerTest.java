package com.nike.backstopper.handler.riposte;

import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.apierror.testutil.ProjectApiErrorsForTesting;
import com.nike.backstopper.handler.ApiExceptionHandlerUtils;
import com.nike.backstopper.handler.ErrorResponseInfo;
import com.nike.backstopper.handler.RequestInfoForLogging;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.backstopper.model.riposte.ErrorResponseBodyImpl;
import com.nike.backstopper.model.riposte.ErrorResponseInfoImpl;
import com.nike.internal.util.MapBuilder;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.http.RequestInfo;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import com.nike.riposte.testutils.Whitebox;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link RiposteUnhandledExceptionHandler}
 */
public class RiposteUnhandledExceptionHandlerTest {

    private ProjectApiErrors projectApiErrors = ProjectApiErrorsForTesting.withProjectSpecificData(null, null);
    private ApiExceptionHandlerUtils utils = ApiExceptionHandlerUtils.DEFAULT_IMPL;
    private RiposteUnhandledExceptionHandler adapterSpy;

    @Before
    public void beforeMethod() {
        adapterSpy = spy(new RiposteUnhandledExceptionHandler(projectApiErrors, utils));
    }

    @Test
    public void constructorWorksIfPassedValidValues() {
        RiposteUnhandledExceptionHandler myAdapter = new RiposteUnhandledExceptionHandler(projectApiErrors, utils);
        assertThat(Whitebox.getInternalState(myAdapter, "projectApiErrors"), is(projectApiErrors));
        assertThat(Whitebox.getInternalState(myAdapter, "utils"), is(utils));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorShouldBarfIfPassedNullProjectApiErrors() {
        new RiposteUnhandledExceptionHandler(null, utils);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorShouldBarfIfPassedNullUtils() {
        new RiposteUnhandledExceptionHandler(projectApiErrors, null);
    }

    @Test
    public void prepareFrameworkResponseUsesErrorResponseBodyNettyAdapterWrapper() {
        RiposteUnhandledExceptionHandler myAdapter = new RiposteUnhandledExceptionHandler(projectApiErrors, utils);
        DefaultErrorContractDTO errorContract = new DefaultErrorContractDTO(UUID.randomUUID().toString(), Arrays.asList(projectApiErrors.getUnauthorizedApiError(),
                                                                                                    projectApiErrors.getMalformedRequestApiError()));
        ErrorResponseBody result = myAdapter.prepareFrameworkRepresentation(errorContract, 42, null, null, null);
        assertThat(result, instanceOf(ErrorResponseBodyImpl.class));
        ErrorResponseBodyImpl adapterResult = (ErrorResponseBodyImpl)result;
        assertThat(adapterResult.error_id, is(errorContract.error_id));
        assertThat(adapterResult.errors, is(errorContract.errors));
    }

    @Test
    public void handleErrorFromNettyInterfaceWrapsRequestInfoWithAdapterBeforeContinuing() {
        com.nike.backstopper.handler.ErrorResponseInfo<ErrorResponseBody> backstopperResponse = new ErrorResponseInfo<>(42, mock(ErrorResponseBody.class), Collections.emptyMap());
        doReturn(backstopperResponse).when(adapterSpy).handleException(any(Throwable.class), any(RequestInfoForLogging.class));

        RequestInfo requestInfoMock = mock(RequestInfo.class);
        adapterSpy.handleError(new Exception(), requestInfoMock);

        ArgumentCaptor<RequestInfoForLogging> requestInfoForLoggingArgumentCaptor = ArgumentCaptor.forClass(RequestInfoForLogging.class);
        verify(adapterSpy).handleException(any(Throwable.class), requestInfoForLoggingArgumentCaptor.capture());

        RequestInfoForLogging passedArg = requestInfoForLoggingArgumentCaptor.getValue();
        assertThat(passedArg, instanceOf(RequestInfoForLoggingRiposteAdapter.class));

        RequestInfo embeddedRequestInfoInWrapper = (RequestInfo) Whitebox.getInternalState(passedArg, "request");
        assertThat(embeddedRequestInfoInWrapper, sameInstance(requestInfoMock));
    }

    @Test
    public void handleErrorFromNettyInterfaceReturnsWrapperAroundBackstopperHandleExceptionReturnValue() {
        ErrorResponseBody errorResponseBodyMock = mock(ErrorResponseBody.class);
        Map<String, List<String>> headersMap = MapBuilder.<String, List<String>>builder().put("headerName", Arrays.asList("hval1", "hval2")).build();
        com.nike.backstopper.handler.ErrorResponseInfo<ErrorResponseBody> backstopperResponse = new ErrorResponseInfo<>(42, errorResponseBodyMock, headersMap);
        doReturn(backstopperResponse).when(adapterSpy).handleException(any(Throwable.class), any(RequestInfoForLogging.class));

        com.nike.riposte.server.error.handler.ErrorResponseInfo riposteErrorResponseInfo = adapterSpy.handleError(new Exception(), mock(RequestInfo.class));
        assertThat(riposteErrorResponseInfo, instanceOf(ErrorResponseInfoImpl.class));
        assertThat(riposteErrorResponseInfo.getErrorHttpStatusCode(), is(backstopperResponse.httpStatusCode));
        assertThat(riposteErrorResponseInfo.getErrorResponseBody(), is(errorResponseBodyMock));
        assertThat(riposteErrorResponseInfo.getExtraHeadersToAddToResponse(), is(headersMap));
    }

}