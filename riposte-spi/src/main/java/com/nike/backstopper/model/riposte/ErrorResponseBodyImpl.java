package com.nike.backstopper.model.riposte;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.backstopper.model.DefaultErrorDTO;
import com.nike.riposte.server.error.handler.ErrorResponseBody;

import org.jetbrains.annotations.NotNull;

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

    public ErrorResponseBodyImpl(@NotNull DefaultErrorContractDTO copy) {
        super(ensureNonNullDtoAndErrorId(copy));
    }

    public ErrorResponseBodyImpl(@NotNull String error_id, Collection<ApiError> apiErrors) {
        super(error_id, apiErrors);
        //noinspection ConstantConditions
        if (error_id == null) {
            throw new IllegalArgumentException("error_id cannot be null.");
        }
    }

    public ErrorResponseBodyImpl(@NotNull String error_id, Collection<DefaultErrorDTO> errorsToCopy, Void passInNullForThisArg) {
        super(error_id, errorsToCopy, passInNullForThisArg);
        //noinspection ConstantConditions
        if (error_id == null) {
            throw new IllegalArgumentException("error_id cannot be null.");
        }
    }

    private static DefaultErrorContractDTO ensureNonNullDtoAndErrorId(DefaultErrorContractDTO copy) {
        if (copy == null) {
            throw new IllegalArgumentException("The DefaultErrorContractDTO copy arg cannot be null.");
        }

        if (copy.error_id == null) {
            throw new IllegalArgumentException("The DefaultErrorContractDTO.error_id value cannot be null.");
        }

        return copy;
    }

    @Override
    public @NotNull String errorId() {
        return error_id;
    }
}
