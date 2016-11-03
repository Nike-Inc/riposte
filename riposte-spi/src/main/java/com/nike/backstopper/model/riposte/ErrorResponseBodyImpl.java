package com.nike.backstopper.model.riposte;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.backstopper.model.DefaultErrorDTO;
import com.nike.riposte.server.error.handler.ErrorResponseBody;

import java.util.Collection;

/**
 * Adapter that allows {@link DefaultErrorContractDTO} (the default Backstopper error contract) to be used
 * as a Riposte {@link ErrorResponseBody} for a Riposte project.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class ErrorResponseBodyImpl extends DefaultErrorContractDTO implements ErrorResponseBody {

    // Here for deserialization support only - usage in real code should involve one of the other constructors since
    //      this class is immutable
    protected ErrorResponseBodyImpl() {
        super();
    }

    public ErrorResponseBodyImpl(DefaultErrorContractDTO copy) {
        super(copy);
    }

    public ErrorResponseBodyImpl(String error_id, Collection<ApiError> apiErrors) {
        super(error_id, apiErrors);
    }

    public ErrorResponseBodyImpl(String error_id, Collection<DefaultErrorDTO> errorsToCopy, Void passInNullForThisArg) {
        super(error_id, errorsToCopy, passInNullForThisArg);
    }

    @Override
    public String errorId() {
        return error_id;
    }
}
