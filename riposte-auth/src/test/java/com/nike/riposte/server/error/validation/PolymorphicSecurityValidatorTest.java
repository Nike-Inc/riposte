package com.nike.riposte.server.error.validation;

import com.nike.riposte.server.error.exception.Unauthorized401Exception;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

@RunWith(DataProviderRunner.class)
public class PolymorphicSecurityValidatorTest {

    Endpoint mockEndpoint;
    Endpoint mockEndpoint2;
    Endpoint mockEndpoint3;

    RequestSecurityValidator innerValidatorOne;
    RequestSecurityValidator innerValidatorTwo;
    PolymorphicSecurityValidator validator;

    public void setupMultipleEndpointsAndMultipleValidators() {
        mockEndpoint = mock(Endpoint.class);
        mockEndpoint2 = mock(Endpoint.class);
        mockEndpoint3 = mock(Endpoint.class);

        innerValidatorOne = mock(RequestSecurityValidator.class);
        doReturn(Arrays.asList(mockEndpoint, mockEndpoint2)).when(innerValidatorOne).endpointsToValidate();
        doReturn(true).when(innerValidatorOne).isFastEnoughToRunOnNettyWorkerThread();

        innerValidatorTwo = mock(RequestSecurityValidator.class);
        doReturn(Arrays.asList(mockEndpoint2, mockEndpoint3)).when(innerValidatorTwo).endpointsToValidate();
        doReturn(true).when(innerValidatorTwo).isFastEnoughToRunOnNettyWorkerThread();

        List<RequestSecurityValidator> validatorList = new ArrayList<>();
        validatorList.add(innerValidatorOne);
        validatorList.add(innerValidatorTwo);
        validator = new PolymorphicSecurityValidator(validatorList);
    }

    @Test
    public void constructorNullList() {
        assertThat(new PolymorphicSecurityValidator(null).validationMap.size(), is(0));
    }

    @Test
    public void constructorEmptyList() {
        assertThat(new PolymorphicSecurityValidator(new ArrayList<>()).validationMap.size(), is(0));
    }

    @Test
    public void constructorSingleValidator() {
        Endpoint mockEndpoint = mock(Endpoint.class);
        RequestSecurityValidator innerValidator = mock(RequestSecurityValidator.class);
        doReturn(Collections.singletonList(mockEndpoint)).when(innerValidator).endpointsToValidate();
        List<RequestSecurityValidator> validatorList = new ArrayList<>();
        validatorList.add(innerValidator);
        Map<Endpoint<?>, List<RequestSecurityValidator>> validationMap =
            new PolymorphicSecurityValidator(validatorList).validationMap;

        assertThat(validationMap.size(), is(1));
        assertThat(validationMap.get(mockEndpoint).size(), is(1));
        assertThat(validationMap.get(mockEndpoint).get(0), is(innerValidator));
    }

    @Test
    public void constructorMultipleValidators() {
        Endpoint mockEndpoint = mock(Endpoint.class);

        RequestSecurityValidator innerVal1 = mock(RequestSecurityValidator.class);
        doReturn(Collections.singletonList(mockEndpoint)).when(innerVal1).endpointsToValidate();

        RequestSecurityValidator innerVal2 = mock(RequestSecurityValidator.class);
        doReturn(Collections.singletonList(mockEndpoint)).when(innerVal2).endpointsToValidate();

        List<RequestSecurityValidator> validatorList = new ArrayList<>();
        validatorList.add(innerVal1);
        validatorList.add(innerVal2);
        Map<Endpoint<?>, List<RequestSecurityValidator>> validationMap =
            new PolymorphicSecurityValidator(validatorList).validationMap;

        assertThat(validationMap.size(), is(1));
        assertThat(validationMap.get(mockEndpoint).size(), is(2));
        assertThat(validationMap.get(mockEndpoint).get(0), is(innerVal1));
        assertThat(validationMap.get(mockEndpoint).get(1), is(innerVal2));

    }

    @Test
    public void constructorMultipleEndpoints() {
        setupMultipleEndpointsAndMultipleValidators();
        Map<Endpoint<?>, List<RequestSecurityValidator>> validationMap = validator.validationMap;

        assertThat(validationMap.size(), is(3));
        assertThat(validationMap.get(mockEndpoint).size(), is(1));
        assertThat(validationMap.get(mockEndpoint).get(0), is(innerValidatorOne));

        assertThat(validationMap.get(mockEndpoint2).size(), is(2));
        assertThat(validationMap.get(mockEndpoint2).get(0), is(innerValidatorOne));
        assertThat(validationMap.get(mockEndpoint2).get(1), is(innerValidatorTwo));

        assertThat(validationMap.get(mockEndpoint3).size(), is(1));
        assertThat(validationMap.get(mockEndpoint3).get(0), is(innerValidatorTwo));
    }

    @Test
    public void endpointsToValidateTest() {
        setupMultipleEndpointsAndMultipleValidators();
        Collection<Endpoint<?>> endpoints = validator.endpointsToValidate();

        assertThat(endpoints.size(), is(3));
        assertThat(endpoints.contains(mockEndpoint), is(true));
        assertThat(endpoints.contains(mockEndpoint2), is(true));
        assertThat(endpoints.contains(mockEndpoint3), is(true));
    }

    @Test
    public void unauthenticatedEndpointTest() {
        setupMultipleEndpointsAndMultipleValidators();
        validator.validateSecureRequestForEndpoint(mock(RequestInfo.class), mock(Endpoint.class));
    }

    @Test
    public void firstValidatorFailsButSecondPassesRequiredTest() {
        setupMultipleEndpointsAndMultipleValidators();
        doThrow(new Unauthorized401Exception(null, null, null)).when(innerValidatorOne)
                                                               .validateSecureRequestForEndpoint(any(RequestInfo.class),
                                                                                                 any(Endpoint.class));
        validator.validateSecureRequestForEndpoint(mock(RequestInfo.class), mockEndpoint2);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void multipleValidatorsFailTest() {
        setupMultipleEndpointsAndMultipleValidators();
        doThrow(new Unauthorized401Exception(null, null, null)).when(innerValidatorOne)
                                                               .validateSecureRequestForEndpoint(any(RequestInfo.class),
                                                                                                 any(Endpoint.class));
        doThrow(new Unauthorized401Exception(null, null, null)).when(innerValidatorTwo)
                                                               .validateSecureRequestForEndpoint(any(RequestInfo.class),
                                                                                                 any(Endpoint.class));
        validator.validateSecureRequestForEndpoint(mock(RequestInfo.class), mockEndpoint2);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void firstAndOnlyValidatorFails() {
        setupMultipleEndpointsAndMultipleValidators();
        doThrow(new Unauthorized401Exception(null, null, null)).when(innerValidatorOne)
                                                               .validateSecureRequestForEndpoint(any(RequestInfo.class),
                                                                                                 any(Endpoint.class));
        validator.validateSecureRequestForEndpoint(mock(RequestInfo.class), mockEndpoint);
    }

    @Test
    public void firstAndOnlyValidatorPasses() {
        setupMultipleEndpointsAndMultipleValidators();
        validator.validateSecureRequestForEndpoint(mock(RequestInfo.class), mockEndpoint);
    }

    @DataProvider(value = {
        "null   |   true",
        "true   |   false",
        "false  |   true"
    }, splitBy = "\\|")
    @Test
    public void isFastEnoughToRunOnNettyWorkerThread_depends_on_the_underlying_individual_validators(
        Boolean includeSlowValidator, boolean expectIsFastEnoughToRunOnNettyWorkerThread
    ) {
        // given
        setupMultipleEndpointsAndMultipleValidators();
        if (includeSlowValidator != null && includeSlowValidator)
            doReturn(false).when(innerValidatorTwo).isFastEnoughToRunOnNettyWorkerThread();
        List<RequestSecurityValidator> validators = (includeSlowValidator == null)
                                                    ? null
                                                    : Arrays.asList(innerValidatorOne, innerValidatorTwo);

        // when
        PolymorphicSecurityValidator instance = new PolymorphicSecurityValidator(validators);

        // then
        assertThat(instance.isFastEnoughToRunOnNettyWorkerThread(), is(expectIsFastEnoughToRunOnNettyWorkerThread));
    }

    @DataProvider(value = {
        "true",
        "false"
    }, splitBy = "\\|")
    @Test
    public void validateSecureRequestForEndpoint_does_nothing_if_validationMap_returns_null_or_empty(boolean useNull) {
        // given
        setupMultipleEndpointsAndMultipleValidators();
        List<RequestSecurityValidator> mappedValidators = (useNull) ? null : Collections.emptyList();
        validator.validationMap.put(mockEndpoint, mappedValidators);
        reset(innerValidatorOne);
        reset(innerValidatorTwo);

        // when
        validator.validateSecureRequestForEndpoint(mock(RequestInfo.class), mockEndpoint);

        // then
        verifyNoInteractions(innerValidatorOne);
        verifyNoInteractions(innerValidatorTwo);
    }
}
