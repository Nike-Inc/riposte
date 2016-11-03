package com.nike.backstopper.handler.riposte;

import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.apierror.testutil.ProjectApiErrorsForTesting;
import com.nike.backstopper.handler.ApiExceptionHandlerUtils;
import com.nike.backstopper.handler.ErrorResponseInfo;
import com.nike.backstopper.handler.RequestInfoForLogging;
import com.nike.backstopper.handler.UnexpectedMajorExceptionHandlingError;
import com.nike.backstopper.handler.listener.ApiExceptionHandlerListener;
import com.nike.backstopper.handler.listener.impl.GenericApiExceptionHandlerListener;
import com.nike.backstopper.handler.riposte.listener.impl.BackstopperRiposteFrameworkErrorHandlerListener;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.backstopper.model.riposte.ErrorResponseBodyImpl;
import com.nike.backstopper.model.riposte.ErrorResponseInfoImpl;
import com.nike.internal.util.MapBuilder;
import com.nike.riposte.server.error.exception.UnexpectedMajorErrorHandlingError;
import com.nike.riposte.server.error.handler.ErrorResponseBody;
import com.nike.riposte.server.http.RequestInfo;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link RiposteApiExceptionHandler}
 */
public class RiposteApiExceptionHandlerTest {

    private ProjectApiErrors projectApiErrors = ProjectApiErrorsForTesting.withProjectSpecificData(null, null);
    private List<ApiExceptionHandlerListener> validListenerList = Arrays.asList(new GenericApiExceptionHandlerListener(),
                                                                                new BackstopperRiposteFrameworkErrorHandlerListener(projectApiErrors));
    private ApiExceptionHandlerUtils utils = ApiExceptionHandlerUtils.DEFAULT_IMPL;
    private RiposteApiExceptionHandler adapterSpy;

    @Before
    public void beforeMethod() {
        adapterSpy = spy(new RiposteApiExceptionHandler(projectApiErrors, validListenerList, utils));
    }

    @Test
    public void constructorWorksIfPassedValidValues() {
        // when
        RiposteApiExceptionHandler
            myAdapter = new RiposteApiExceptionHandler(projectApiErrors, validListenerList, utils);

        // then
        List<ApiExceptionHandlerListener> actualListeners =
            (List<ApiExceptionHandlerListener>) Whitebox.getInternalState(myAdapter, "apiExceptionHandlerListenerList");
        ProjectApiErrors actualProjectApiErrors =
            (ProjectApiErrors) Whitebox.getInternalState(myAdapter, "projectApiErrors");
        ApiExceptionHandlerUtils actualUtils = (ApiExceptionHandlerUtils) Whitebox.getInternalState(myAdapter, "utils");
        assertThat(actualListeners, is(validListenerList));
        assertThat(actualProjectApiErrors, is(projectApiErrors));
        assertThat(actualUtils, is(utils));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorShouldBarfIfPassedNullListenersList() {
        new RiposteApiExceptionHandler(projectApiErrors, null, utils);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorShouldBarfIfPassedNullProjectApiErrors() {
        new RiposteApiExceptionHandler(null, validListenerList, utils);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorShouldBarfIfPassedNullApiExceptionHandlerUtils() {
        new RiposteApiExceptionHandler(projectApiErrors, validListenerList, null);
    }

    @Test
    public void prepareFrameworkResponseUsesErrorResponseBodyNettyAdapterWrapper() {
        RiposteApiExceptionHandler
            myAdapter = new RiposteApiExceptionHandler(projectApiErrors, validListenerList, utils);
        DefaultErrorContractDTO errorContract = new DefaultErrorContractDTO(UUID.randomUUID().toString(), Arrays.asList(projectApiErrors.getUnauthorizedApiError(),
                                                                                          projectApiErrors.getMalformedRequestApiError()));
        ErrorResponseBody result = myAdapter.prepareFrameworkRepresentation(errorContract, 42, null, null, null);
        assertThat(result, instanceOf(ErrorResponseBodyImpl.class));
        ErrorResponseBodyImpl adapterResult = (ErrorResponseBodyImpl)result;
        assertThat(adapterResult.error_id, is(errorContract.error_id));
        assertThat(adapterResult.errors, is(errorContract.errors));
    }

    @Test
    public void maybeHandleErrorFromNettyInterfaceReturnsNullIfBackstopperMaybeHandleExceptionReturnsNull() throws UnexpectedMajorExceptionHandlingError, UnexpectedMajorErrorHandlingError {
        doReturn(null).when(adapterSpy).maybeHandleException(any(Throwable.class), any(RequestInfoForLogging.class));
        RequestInfo requestInfoMock = mock(RequestInfo.class);
        assertThat(adapterSpy.maybeHandleError(new Exception(), requestInfoMock), nullValue());
    }

    @Test
    public void maybeHandleErrorFromNettyInterfaceWrapsRequestInfoWithAdapterBeforeContinuing() throws UnexpectedMajorExceptionHandlingError, UnexpectedMajorErrorHandlingError {
        doReturn(null).when(adapterSpy).maybeHandleException(any(Throwable.class), any(RequestInfoForLogging.class));
        RequestInfo requestInfoMock = mock(RequestInfo.class);
        adapterSpy.maybeHandleError(new Exception(), requestInfoMock);
        ArgumentCaptor<RequestInfoForLogging> requestInfoForLoggingArgumentCaptor = ArgumentCaptor.forClass(RequestInfoForLogging.class);
        verify(adapterSpy).maybeHandleException(any(Throwable.class), requestInfoForLoggingArgumentCaptor.capture());
        RequestInfoForLogging passedArg = requestInfoForLoggingArgumentCaptor.getValue();
        assertThat(passedArg, instanceOf(RequestInfoForLoggingRiposteAdapter.class));
        RequestInfo embeddedRequestInfoInWrapper = (RequestInfo) Whitebox.getInternalState(passedArg, "request");
        assertThat(embeddedRequestInfoInWrapper, sameInstance(requestInfoMock));
    }

    @Test
    public void maybeHandleErrorFromNettyInterfaceReturnsWrapperAroundBackstopperMaybeHandleExceptionReturnValue() throws UnexpectedMajorExceptionHandlingError, UnexpectedMajorErrorHandlingError {
        ErrorResponseBody errorResponseBodyMock = mock(ErrorResponseBody.class);
        Map<String, List<String>> headersMap = MapBuilder.<String, List<String>>builder().put("headerName", Arrays.asList("hval1", "hval2")).build();
        com.nike.backstopper.handler.ErrorResponseInfo<ErrorResponseBody> backstopperResponse = new ErrorResponseInfo<>(42, errorResponseBodyMock, headersMap);
        doReturn(backstopperResponse).when(adapterSpy).maybeHandleException(any(Throwable.class), any(RequestInfoForLogging.class));

        com.nike.riposte.server.error.handler.ErrorResponseInfo riposteErrorResponseInfo = adapterSpy.maybeHandleError(new Exception(), mock(RequestInfo.class));
        assertThat(riposteErrorResponseInfo, instanceOf(ErrorResponseInfoImpl.class));
        assertThat(riposteErrorResponseInfo.getErrorHttpStatusCode(), is(backstopperResponse.httpStatusCode));
        assertThat(riposteErrorResponseInfo.getErrorResponseBody(), is(errorResponseBodyMock));
        assertThat(riposteErrorResponseInfo.getExtraHeadersToAddToResponse(), is(headersMap));
    }

    // Note the difference between UnexpectedMajor**Exception**HandlingError and UnexpectedMajor**Error**HandlingError. I know, I know, confusing and annoying. Adapters can be like that.
    @Test(expected = UnexpectedMajorErrorHandlingError.class)
    public void maybeHandleErrorExplosionThrowsUnexpectedMajorErrorHandlingError() throws UnexpectedMajorExceptionHandlingError, UnexpectedMajorErrorHandlingError {
        UnexpectedMajorExceptionHandlingError innerExplosion = new UnexpectedMajorExceptionHandlingError("intentional kaboom", new Exception());
        doThrow(innerExplosion).when(adapterSpy).maybeHandleException(any(Throwable.class), any(RequestInfoForLogging.class));
        RequestInfo requestInfoMock = mock(RequestInfo.class);
        adapterSpy.maybeHandleError(new Exception(), requestInfoMock);
    }
}