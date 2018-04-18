package com.nike.backstopper.model.riposte;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.testutil.BarebonesCoreApiErrorForTesting;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.backstopper.model.DefaultErrorDTO;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Unit test for {@link ErrorResponseBodyImpl}
 */
public class ErrorResponseBodyImplTest {

    @Test
    public void blankConstructorWorks() {
        ErrorResponseBodyImpl adapter = new ErrorResponseBodyImpl();
        assertThat(adapter.error_id, nullValue());
        assertThat(adapter.errorId(), is(adapter.error_id));
        assertThat(adapter.errors, notNullValue());
        assertThat(adapter.errors.isEmpty(), is(true));
    }

    private void verifyAdapter(ErrorResponseBodyImpl adapter, String expectedErrorId, List<ApiError> expectedErrors) {
        assertThat(adapter.error_id, is(expectedErrorId));
        assertThat(adapter.errorId(), is(expectedErrorId));
        assertThat(adapter.errors.size(), is(expectedErrors.size()));
        for (int i = 0; i < expectedErrors.size(); i++) {
            ApiError apiError = expectedErrors.get(i);
            DefaultErrorDTO errorView = adapter.errors.get(i);
            assertThat(errorView.code, is(apiError.getErrorCode()));
            assertThat(errorView.message, is(apiError.getMessage()));
        }
    }

    @Test
    public void doubleArgConstructorWorks() {
        String errorUuid = UUID.randomUUID().toString();
        List<ApiError> errorsList = Arrays.asList(BarebonesCoreApiErrorForTesting.NO_ACCEPTABLE_REPRESENTATION, BarebonesCoreApiErrorForTesting.UNSUPPORTED_MEDIA_TYPE);
        ErrorResponseBodyImpl adapter = new ErrorResponseBodyImpl(errorUuid, errorsList);
        verifyAdapter(adapter, errorUuid, errorsList);
    }

    @Test
    public void tripleArgConstructorWorks() {
        // given
        String errorUuid = UUID.randomUUID().toString();
        List<ApiError> apiErrorList = Arrays.asList(BarebonesCoreApiErrorForTesting.NO_ACCEPTABLE_REPRESENTATION, BarebonesCoreApiErrorForTesting.UNSUPPORTED_MEDIA_TYPE);
        List<DefaultErrorDTO> errorsList = apiErrorList.stream().map(DefaultErrorDTO::new).collect(Collectors.toList());

        // when
        ErrorResponseBodyImpl adapter = new ErrorResponseBodyImpl(errorUuid, errorsList, null);

        // then
        verifyAdapter(adapter, errorUuid, apiErrorList);
    }

    @Test
    public void errorResponseViewWrapperConstructorWorks() {
        String errorUuid = UUID.randomUUID().toString();
        List<ApiError> errorsList = Arrays.asList(BarebonesCoreApiErrorForTesting.NO_ACCEPTABLE_REPRESENTATION, BarebonesCoreApiErrorForTesting.UNSUPPORTED_MEDIA_TYPE);
        DefaultErrorContractDTO errorContract = new DefaultErrorContractDTO(errorUuid, errorsList);
        ErrorResponseBodyImpl adapter = new ErrorResponseBodyImpl(errorContract);
        verifyAdapter(adapter, errorUuid, errorsList);
    }
}