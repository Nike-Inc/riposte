package com.nike.backstopper.service.riposte;

import com.nike.backstopper.service.ClientDataValidationService;
import com.nike.riposte.server.http.RequestInfo;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link BackstopperRiposteValidatorAdapter}
 */
public class BackstopperRiposteValidatorAdapterTest {

    private ClientDataValidationService validationServiceMock;
    private RequestInfo requestInfoMock;
    private Object requestContent = new Object();
    private BackstopperRiposteValidatorAdapter adapter;

    @Before
    public void beforeMethod() {
        requestInfoMock = mock(RequestInfo.class);
        doReturn(requestContent).when(requestInfoMock).getContent();
        validationServiceMock = mock(ClientDataValidationService.class);
        adapter = new BackstopperRiposteValidatorAdapter(validationServiceMock);
    }

    @Test
    public void validateRequestContentSingleArgDelegatesToInternalValidationService() {
        adapter.validateRequestContent(requestInfoMock);
        verify(validationServiceMock).validateObjectsFailFast(requestContent);
    }

    @Test
    public void validateRequestContentDoubleArgDelegatesToInternalValidationService() {
        Class<?>[] validationGroups = new Class[] {String.class, Integer.class};
        adapter.validateRequestContent(requestInfoMock, validationGroups);
        verify(validationServiceMock).validateObjectsWithGroupsFailFast(validationGroups, requestContent);
    }
}