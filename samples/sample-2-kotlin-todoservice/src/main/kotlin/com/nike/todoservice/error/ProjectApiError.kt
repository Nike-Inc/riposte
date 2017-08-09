package com.nike.todoservice.error

import com.nike.backstopper.apierror.ApiError
import com.nike.backstopper.apierror.ApiErrorBase
import com.nike.todoservice.endpoints.TodoItemsEndpoint
import io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND
import java.util.UUID

/**
 * Contains the application-specific errors that can occur. Each enum value maps an application-specific error to the
 * appropriate HTTP status code, and contains an appropriate human-readable message.
 *
 * These can be manually thrown with [com.nike.backstopper.exception.ApiException], or they can be used as the
 * `message` for any JSR 303 (Java Bean Validation) annotation (e.g. [javax.validation.constraints.NotNull.message] and
 * if that violation is triggered it will be converted by the Backstopper-powered error handling system into the
 * appropriate error instance below.
 *
 * NOTE: These codes are intended to be project-specific. Feel free to rename this class to something specific to the
 * project, e.g. MyProjectApiError.
 */
enum class ProjectApiError(private val delegate: ApiError) : ApiError {

    /**
     * These are just example errors. Each project should delete this and define their own project specific errors. But
     * take a look at how they are used in [TodoItemsEndpoint] first so you know how the validation and error
     * handling system works.
     */
    TODO_ITEM_NOT_FOUND_1(99160, "The requested TODO was not found", NOT_FOUND.code());

    @Suppress("unused")
    constructor(errorCode: Int, message: String, httpStatusCode: Int, metadata: Map<String, Any>? = null):
            this(ApiErrorBase(
                    "delegated-to-enum-wrapper-" + UUID.randomUUID().toString(), errorCode, message, httpStatusCode,
                    metadata
            ))

    override fun getName(): String {
        return this.name
    }

    override fun getErrorCode(): String {
        return delegate.errorCode
    }

    override fun getMessage(): String {
        return delegate.message
    }

    override fun getMetadata(): Map<String, Any> {
        return delegate.metadata
    }

    override fun getHttpStatusCode(): Int {
        return delegate.httpStatusCode
    }

}
